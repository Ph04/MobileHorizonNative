#!/usr/bin/env python3
"""Convert the portable assets/data portion of a Fabric JAR into 26.3 packs.

This tool never runs Java code, Fabric Loader or Mixins. It can fully convert
only data/resource-only JARs; code-bearing mods receive a partial extraction
report and still need a native Horizon port for their behavior.
"""
from __future__ import annotations

import argparse
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


def convert_jar(jar_path: Path, output_dir: Path, *, minecraft_version: str = "26.3") -> dict[str, Any]:
    jar_path = Path(jar_path)
    output_dir = Path(output_dir)
    if minecraft_version not in TARGET_FORMATS:
        return {"path": str(jar_path), "status": "error",
                "error": f"unsupported target version {minecraft_version!r}"}
    audit = audit_jar(jar_path)
    if "error" in audit:
        return {"path": str(jar_path), "status": "error", "error": audit["error"]}
    metadata = audit["metadata"]
    archive_report = audit["archive"]
    mod_id = str(metadata.get("id", "")).strip()
    if not MOD_ID_RE.fullmatch(mod_id):
        return {"path": str(jar_path), "status": "error",
                "error": "fabric.mod.json must contain a lowercase, valid mod ID"}
    version = str(metadata.get("version", "unknown")).strip() or "unknown"
    display_name = str(metadata.get("name", mod_id)).strip() or mod_id
    made_outputs: list[Path] = []
    try:
        with zipfile.ZipFile(jar_path, "r") as source:
            entries = source.infolist()
            if len(entries) > MAX_ENTRIES:
                raise ConversionError(f"archive has more than {MAX_ENTRIES} entries")
            # Validate every asset/data path before writing anything, even if only
            # one of the pack types is ultimately emitted.
            selected: list[zipfile.ZipInfo] = []
            seen_paths: set[str] = set()
            assets = data = 0
            for info in entries:
                if info.is_dir():
                    continue
                name = info.filename
                if name.startswith(("assets/", "data/")):
                    safe = _safe_relative_entry(name)
                    if safe in seen_paths:
                        raise ConversionError(f"duplicate resource path: {safe}")
                    seen_paths.add(safe)
                    if info.file_size < 0 or info.file_size > MAX_ENTRY_BYTES:
                        raise ConversionError(f"entry exceeds size limit: {safe}")
                    selected.append(info)
                    if safe.startswith("assets/"):
                        assets += 1
                    else:
                        data += 1
            if not assets and not data:
                raise ConversionError("JAR contains no assets/ or data/ resources to convert")

            output_dir.mkdir(parents=True, exist_ok=True)
            formats = TARGET_FORMATS[minecraft_version]
            outputs: list[dict[str, Any]] = []
            if assets:
                filename = _base_name(mod_id, version, "resources")
                target = _available_path(output_dir, filename)
                byte_count = _write_pack(
                    source, selected, target, roots=("assets/",),
                    description=f"{display_name} resources extracted from Fabric JAR; Java code is not included",
                    pack_format=formats["resource"],
                )
                made_outputs.append(target)
                outputs.append({"type": "resource_pack", "path": str(target),
                                "files": assets, "uncompressed_bytes": byte_count})
            if data:
                filename = _base_name(mod_id, version, "datapack")
                target = _available_path(output_dir, filename)
                byte_count = _write_pack(
                    source, selected, target, roots=("data/",),
                    description=f"{display_name} data extracted from Fabric JAR; Java code is not included",
                    pack_format=formats["data"],
                )
                made_outputs.append(target)
                outputs.append({"type": "data_pack", "path": str(target),
                                "files": data, "uncompressed_bytes": byte_count})
    except (OSError, zipfile.BadZipFile, RuntimeError, ConversionError) as error:
        for output in made_outputs:
            output.unlink(missing_ok=True)
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
    args = parser.parse_args(argv)

    results = [
        convert_jar(path, args.output_dir, minecraft_version=args.minecraft_version)
        for path in args.jars
    ]
    report = {"schema_version": 1, "results": results}
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 1 if any(item.get("status") == "error" for item in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())
