#!/usr/bin/env python3
"""Convert the portable assets/data portion of a Fabric JAR into 26.3 packs.

This tool never runs Java code, Fabric Loader or Mixins. It can fully convert
only data/resource-only JARs; code-bearing mods receive a partial extraction
report and still need a native Horizon port for their behavior.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

try:
    from .fabric_bridge_audit import audit_jar
except ImportError:  # Direct script execution keeps the tools/ directory on sys.path.
    from fabric_bridge_audit import audit_jar

MAX_ENTRIES = 20_000
MAX_ENTRY_BYTES = 128 * 1024 * 1024
MAX_OUTPUT_BYTES = 512 * 1024 * 1024
MOD_ID_RE = re.compile(r"^[a-z0-9_.-]{1,128}$")
SAFE_FILENAME_RE = re.compile(r"[^A-Za-z0-9._-]+")

TARGET_FORMATS = {
    "26.3": {"resource": [97, 1], "data": [121, 0]},
}


class ConversionError(ValueError):
    """A JAR cannot safely be converted."""


class TraceWriter:
    """Append-only JSONL diagnostics; never records resource contents."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._file = self.path.open("w", encoding="utf-8")
        self.event("trace_started", path=str(self.path), format="jsonl", schema_version=1)

    def event(self, event: str, **details: Any) -> None:
        row = {
            "timestamp_utc": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "component": "fabric_converter",
            "event": event,
            **details,
        }
        self._file.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
        self._file.flush()

    def close(self) -> None:
        self.event("trace_finished")
        self._file.close()


def _safe_relative_entry(name: str) -> str:
    if not name or name.startswith("/") or "\\" in name or "\x00" in name:
        raise ConversionError(f"unsafe archive path: {name!r}")
    raw_parts = name.split("/")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in ("", ".", "..") for part in raw_parts):
        raise ConversionError(f"unsafe archive path: {name!r}")
    normalized = path.as_posix()
    if not (normalized.startswith("assets/") or normalized.startswith("data/")):
        raise ConversionError(f"unexpected path passed to pack writer: {name!r}")
    return normalized


def _base_name(mod_id: str, version: str, suffix: str) -> str:
    mod = SAFE_FILENAME_RE.sub("_", mod_id).strip("._-") or "fabric_mod"
    ver = SAFE_FILENAME_RE.sub("_", version).strip("._-") or "unknown"
    return f"{mod}-{ver}-{suffix}.zip"[:180]


def _available_path(output_dir: Path, name: str) -> Path:
    candidate = output_dir / name
    if not candidate.exists():
        return candidate
    stem, suffix = candidate.stem, candidate.suffix
    index = 2
    while True:
        alternative = output_dir / f"{stem}_{index}{suffix}"
        if not alternative.exists():
            return alternative
        index += 1


def _pack_metadata(description: str, version: list[int]) -> bytes:
    # Since Minecraft 1.21.9, min_format/max_format are used instead of
    # pack_format. These values target the pinned Java Edition 26.3 runtime.
    payload = {
        "pack": {
            "description": description,
            "min_format": version,
            "max_format": version,
        }
    }
    return (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _write_pack(source: zipfile.ZipFile, entries: list[zipfile.ZipInfo],
                destination: Path, *, roots: tuple[str, ...], description: str,
                pack_format: list[int]) -> int:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp = destination.with_name(destination.name + ".part")
    total = 0
    seen: set[str] = set()
    try:
        with zipfile.ZipFile(temp, "w", compression=zipfile.ZIP_DEFLATED,
                             compresslevel=6, allowZip64=True) as output:
            output.writestr("pack.mcmeta", _pack_metadata(description, pack_format))
            for info in entries:
                if info.is_dir():
                    continue
                raw_name = info.filename
                if not raw_name.startswith(roots):
                    continue
                name = _safe_relative_entry(raw_name)
                if name in seen:
                    raise ConversionError(f"duplicate resource path: {name}")
                seen.add(name)
                if info.file_size < 0 or info.file_size > MAX_ENTRY_BYTES:
                    raise ConversionError(f"entry exceeds size limit: {name}")
                if total + info.file_size > MAX_OUTPUT_BYTES:
                    raise ConversionError("converted resources exceed the 512 MiB output limit")
                copied = 0
                with source.open(info, "r") as src, output.open(name, "w") as dst:
                    while True:
                        chunk = src.read(64 * 1024)
                        if not chunk:
                            break
                        copied += len(chunk)
                        if copied > info.file_size or total + copied > MAX_OUTPUT_BYTES:
                            raise ConversionError(f"entry size limit exceeded while reading: {name}")
                        dst.write(chunk)
                if copied != info.file_size:
                    raise ConversionError(f"entry size mismatch while reading: {name}")
                total += copied
        temp.replace(destination)
        return total
    except Exception:
        temp.unlink(missing_ok=True)
        destination.unlink(missing_ok=True)
        raise


def convert_jar(
    jar_path: Path,
    output_dir: Path,
    *,
    minecraft_version: str = "26.3",
    trace: TraceWriter | None = None,
) -> dict[str, Any]:
    jar_path = Path(jar_path)
    output_dir = Path(output_dir)

    def emit(event: str, **details: Any) -> None:
        if trace is not None:
            trace.event(event, jar=str(jar_path), **details)

    emit("mod_conversion_started", target_version=minecraft_version)
    if minecraft_version not in TARGET_FORMATS:
        error = f"unsupported target version {minecraft_version!r}"
        emit("mod_conversion_failed", stage="version_check", error=error)
        return {"path": str(jar_path), "status": "error", "error": error}
    emit("static_audit_started")
    audit = audit_jar(jar_path)
    if "error" in audit:
        emit("static_audit_failed", error=audit["error"])
        return {"path": str(jar_path), "status": "error", "error": audit["error"]}
    metadata = audit["metadata"]
    archive_report = audit["archive"]
    emit(
        "static_audit_completed",
        sha256=audit.get("sha256"),
        mod_id=metadata.get("id"),
        mod_version=metadata.get("version"),
        class_count=archive_report.get("class_count", 0),
        entrypoint_groups=sorted(metadata.get("entrypoints", {}).keys())
            if isinstance(metadata.get("entrypoints"), dict) else [],
        mixin_configs=archive_report.get("mixin_config_paths", []),
        access_wideners=archive_report.get("access_widener_paths", []),
        fabric_api_references=audit.get("code_references", {}).get("fabric_reference_count", 0),
        minecraft_references=audit.get("code_references", {}).get("minecraft_reference_count", 0),
    )
    mod_id = str(metadata.get("id", "")).strip()
    if not MOD_ID_RE.fullmatch(mod_id):
        error = "fabric.mod.json must contain a lowercase, valid mod ID"
        emit("mod_conversion_failed", stage="metadata_validation", error=error)
        return {"path": str(jar_path), "status": "error", "error": error}
    version = str(metadata.get("version", "unknown")).strip() or "unknown"
    display_name = str(metadata.get("name", mod_id)).strip() or mod_id
    emit("metadata_validated", mod_id=mod_id, mod_version=version, display_name=display_name)
    made_outputs: list[Path] = []
    try:
        with zipfile.ZipFile(jar_path, "r") as source:
            entries = source.infolist()
            emit("archive_scan_started", entry_count=len(entries), max_entries=MAX_ENTRIES)
            if len(entries) > MAX_ENTRIES:
                raise ConversionError(f"archive has more than {MAX_ENTRIES} entries")
            # Validate every asset/data path before writing anything, even if only
            # one of the pack types is ultimately emitted.
            selected: list[zipfile.ZipInfo] = []
            seen_paths: set[str] = set()
            assets = data = 0
            for info in entries:
                if info.is_dir():
                    emit("archive_entry_skipped", entry=info.filename, reason="directory")
                    continue
                name = info.filename
                if not name.startswith(("assets/", "data/")):
                    if name.endswith(".class"):
                        classification = "java_class_not_executed_or_converted"
                    elif name == "fabric.mod.json":
                        classification = "fabric_metadata"
                    elif name.endswith((".mixins.json", ".accesswidener")):
                        classification = "java_hook_metadata_not_converted"
                    else:
                        classification = "non_pack_file"
                    emit("archive_entry_skipped", entry=name, size_bytes=info.file_size,
                         classification=classification, reason="outside assets/ and data/")
                    continue
                if name.startswith(("assets/", "data/")):
                    safe = _safe_relative_entry(name)
                    if safe in seen_paths:
                        raise ConversionError(f"duplicate resource path: {safe}")
                    seen_paths.add(safe)
                    if info.file_size < 0 or info.file_size > MAX_ENTRY_BYTES:
                        raise ConversionError(f"entry exceeds size limit: {safe}")
                    selected.append(info)
                    root = "assets" if safe.startswith("assets/") else "data"
                    emit("resource_selected", entry=safe, root=root, size_bytes=info.file_size,
                         compressed_bytes=info.compress_size, crc32=f"{info.CRC:08x}")
                    if root == "assets":
                        assets += 1
                    else:
                        data += 1
            emit("archive_scan_completed", resource_entries=assets + data,
                 asset_entries=assets, data_entries=data,
                 skipped_entries=len(entries) - assets - data)
            if not assets and not data:
                raise ConversionError("JAR contains no assets/ or data/ resources to convert")

            output_dir.mkdir(parents=True, exist_ok=True)
            formats = TARGET_FORMATS[minecraft_version]
            outputs: list[dict[str, Any]] = []
            if assets:
                filename = _base_name(mod_id, version, "resources")
                target = _available_path(output_dir, filename)
                emit("pack_write_started", pack_type="resource_pack", destination=str(target),
                     source_entry_count=assets)
                byte_count = _write_pack(
                    source, selected, target, roots=("assets/",),
                    description=f"{display_name} resources extracted from Fabric JAR; Java code is not included",
                    pack_format=formats["resource"],
                )
                made_outputs.append(target)
                outputs.append({"type": "resource_pack", "path": str(target),
                                "files": assets, "uncompressed_bytes": byte_count})
                emit("pack_write_completed", pack_type="resource_pack", destination=str(target),
                     files=assets, uncompressed_bytes=byte_count)
            if data:
                filename = _base_name(mod_id, version, "datapack")
                target = _available_path(output_dir, filename)
                emit("pack_write_started", pack_type="data_pack", destination=str(target),
                     source_entry_count=data)
                byte_count = _write_pack(
                    source, selected, target, roots=("data/",),
                    description=f"{display_name} data extracted from Fabric JAR; Java code is not included",
                    pack_format=formats["data"],
                )
                made_outputs.append(target)
                outputs.append({"type": "data_pack", "path": str(target),
                                "files": data, "uncompressed_bytes": byte_count})
                emit("pack_write_completed", pack_type="data_pack", destination=str(target),
                     files=data, uncompressed_bytes=byte_count)
    except (OSError, zipfile.BadZipFile, RuntimeError, ConversionError) as error:
        for output in made_outputs:
            output.unlink(missing_ok=True)
        emit("mod_conversion_failed", stage="archive_scan_or_pack_write", error=str(error))
        return {"path": str(jar_path), "status": "error", "error": str(error)}

    entrypoints = metadata.get("entrypoints", {})
    has_entrypoints = isinstance(entrypoints, dict) and any(bool(value) for value in entrypoints.values())
    has_mixins = bool(metadata.get("declared_mixins")) or bool(archive_report.get("mixin_config_paths")) \
        or bool(audit["code_references"].get("mixin_reference_count"))
    has_access_widener = bool(metadata.get("declared_access_widener")) \
        or bool(archive_report.get("access_widener_paths"))
    has_java_code = archive_report.get("class_count", 0) > 0
    resource_only = not (has_java_code or has_entrypoints or has_mixins or has_access_widener)
    status = "resource_only" if resource_only else "partial_resources_only"
    warnings = [
        "Extracted assets/data do not recreate registries, blocks/items/entities or behavior supplied by Java code.",
        "Review the generated packs in-game; resource and data files can still reference modded identifiers.",
    ]
    if not resource_only:
        warnings.insert(0,
            "Java classes, entrypoints, Mixins and/or access wideners are not converted. This output is not a complete mod.")
    emit("mod_conversion_completed", status=status, resource_only=resource_only,
         class_count=archive_report.get("class_count", 0), has_entrypoints=has_entrypoints,
         has_mixins=has_mixins, has_access_widener=has_access_widener,
         output_count=len(outputs), output_types=[item["type"] for item in outputs],
         warnings=warnings)
    return {
        "path": str(jar_path),
        "sha256": audit["sha256"],
        "mod": {"id": mod_id, "name": display_name, "version": version},
        "minecraft_version": minecraft_version,
        "status": status,
        "source_class_count": archive_report.get("class_count", 0),
        "source_has_entrypoints": has_entrypoints,
        "source_has_mixins": has_mixins,
        "source_has_access_widener": has_access_widener,
        "converted_outputs": outputs,
        "warnings": warnings,
        "automatic_conversion_scope": (
            "portable assets/data only; no Java bytecode execution or Fabric Loader emulation"
        ),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Extract portable assets/data from Fabric JARs into Minecraft 26.3 packs."
    )
    parser.add_argument("jars", nargs="+", type=Path, help="Fabric JAR file(s)")
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="directory for generated resource/data pack ZIPs")
    parser.add_argument("--minecraft-version", default="26.3",
                        choices=sorted(TARGET_FORMATS), help="target Minecraft version")
    parser.add_argument("--report", type=Path, help="optional JSON conversion report path")
    parser.add_argument(
        "--trace-log", type=Path,
        help="JSONL trace path (defaults to fabric-conversion-trace.jsonl inside --output-dir)",
    )
    args = parser.parse_args(argv)

    trace_path = args.trace_log or (args.output_dir / "fabric-conversion-trace.jsonl")
    trace = TraceWriter(trace_path)
    try:
        trace.event("conversion_run_started", target_version=args.minecraft_version,
                    jar_count=len(args.jars), jars=[str(path) for path in args.jars])
        results = [
            convert_jar(path, args.output_dir, minecraft_version=args.minecraft_version, trace=trace)
            for path in args.jars
        ]
        report = {
            "schema_version": 2,
            "results": results,
            "trace_log": str(trace_path),
            "conversion_scope": "assets/data only; Java classes and executable behavior are not converted",
        }
        trace.event("conversion_run_completed",
                    successful=sum(item.get("status") != "error" for item in results),
                    failed=sum(item.get("status") == "error" for item in results))
    finally:
        trace.close()
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    print(f"Fabric conversion trace: {trace_path}", file=sys.stderr)
    return 1 if any(item.get("status") == "error" for item in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
