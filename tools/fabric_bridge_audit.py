#!/usr/bin/env python3
"""Static triage of Fabric JARs for the Horizon Fabric Bridge.

Never extracts or executes a JAR. Reads metadata, resource paths, and Java
class-file constant pools. It cannot prove a mod will convert successfully.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
MAX_ENTRIES = 250_000
MAX_METADATA_BYTES = 2 * 1024 * 1024
MAX_CLASS_BYTES = 16 * 1024 * 1024
MAX_TOTAL_CLASS_BYTES = 256 * 1024 * 1024
MAX_SAMPLES = 100

REFERENCE_RE = re.compile(
    r"(?:net/minecraft|net/fabricmc|org/spongepowered/asm/mixin)/[A-Za-z0-9_/$]+"
)
FEATURE_MARKERS: dict[str, tuple[str, ...]] = {
    "client_lifecycle": ("ClientTickEvents", "ClientLifecycleEvents", "ClientLevelEvents",
                         "ClientChunkEvents", "ClientEntityEvents"),
    "server_lifecycle": ("ServerTickEvents", "ServerLifecycleEvents", "ServerWorldEvents"),
    "rendering": ("HudRenderCallback", "WorldRenderEvents", "EntityRendererRegistry",
                  "BlockEntityRendererRegistry", "ModelLoadingPlugin", "FabricRenderPipeline"),
    "screens_ui": ("ScreenEvents", "ScreenRegistry", "HandledScreens", "DrawContext"),
    "keybindings": ("KeyBindingHelper", "KeyMapping", "KeyBinding"),
    "world_blocks_entities": ("BlockState", "BlockEntity", "EntityType", "BlockView",
                              "ClientWorld", "ServerWorld", "LevelReader"),
    "item_inventory_transfer": ("ItemStack", "Inventory", "ItemStorage", "Storage<",
                                "Transaction", "ItemUsageContext"),
    "networking": ("PayloadTypeRegistry", "ClientPlayNetworking", "ServerPlayNetworking",
                   "ClientConfigurationNetworking", "ServerConfigurationNetworking"),
    "registries": ("Registries", "Registry.register", "RegistryKey", "RegistryBuilder"),
    "worldgen_dimensions": ("BiomeModifications", "BiomeSelectors", "DimensionOptions",
                            "PlacedFeature", "Structure"),
    "sound_particles": ("SoundEvent", "ParticleType", "ParticleFactory"),
    "data_persistence": ("AttachmentType", "PersistentState", "DataAttachment"),
    "mixin_access": ("org/spongepowered/asm/mixin", "Mixin", "Inject", "Redirect",
                     "ModifyArg", "ModifyVariable", "Overwrite", "Accessor", "Invoker"),
}
RESOURCE_BUCKETS = (
    "textures", "models", "blockstates", "languages", "sound_definitions",
    "recipes", "loot_tables", "tags", "worldgen", "structures", "advancements",
    "functions", "predicates", "item_modifiers", "other_assets", "other_data",
)


def parse_class_constant_pool(data: bytes) -> dict[str, Any]:
    """Read class-name and UTF8 constants without loading the class."""
    if len(data) < 10:
        raise ValueError("class file is shorter than its header")
    magic, minor, major, count = struct.unpack_from(">IHHH", data, 0)
    if magic != 0xCAFEBABE:
        raise ValueError("invalid Java class-file magic")
    if count == 0:
        raise ValueError("invalid zero constant-pool count")

    cursor = 10
    cp: list[Any] = [None] * count
    utf8_values: list[str] = []
    tags = Counter()

    def take(size: int) -> bytes:
        nonlocal cursor
        if size < 0 or cursor + size > len(data):
            raise ValueError("truncated constant pool")
        value = data[cursor:cursor + size]
        cursor += size
        return value

    def read_u2() -> int:
        return struct.unpack(">H", take(2))[0]

    index = 1
    while index < count:
        tag = take(1)[0]
        tags[tag] += 1
        if tag == 1:
            size = read_u2()
            value = take(size).decode("utf-8", errors="replace")
            cp[index] = ("utf8", value)
            utf8_values.append(value)
        elif tag == 7:
            cp[index] = ("class", read_u2())
        elif tag in (8, 16, 19, 20):
            cp[index] = ("one-index", read_u2())
        elif tag in (3, 4):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag in (9, 10, 11, 12, 17, 18):
            take(4)
        elif tag == 15:
            take(3)
        else:
            raise ValueError(f"unknown constant-pool tag {tag}")
        index += 1

    class_names: set[str] = set()
    for entry in cp:
        if isinstance(entry, tuple) and entry[0] == "class":
            ref = entry[1]
            if 0 < ref < len(cp):
                name = cp[ref]
                if isinstance(name, tuple) and name[0] == "utf8":
                    class_names.add(name[1])

    references: set[str] = set(class_names)
    for value in utf8_values:
        references.update(REFERENCE_RE.findall(value))
    return {
        "major_version": major,
        "minor_version": minor,
        "class_names": class_names,
        "references": references,
        "utf8_values": utf8_values,
        "constant_pool_tags": dict(tags),
    }


def _metadata_value(archive: zipfile.ZipFile, names: set[str], name: str,
                    warnings: list[str]) -> dict[str, Any]:
    if name not in names:
        return {}
    info = archive.getinfo(name)
    if info.file_size > MAX_METADATA_BYTES:
        warnings.append(f"{name} exceeds the metadata size limit and was not read")
        return {}
    try:
        data = json.loads(archive.read(name).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, OSError, zipfile.BadZipFile) as exc:
        warnings.append(f"could not parse {name}: {exc}")
        return {}
    if not isinstance(data, dict):
        warnings.append(f"{name} is not a JSON object")
        return {}
    return data


def _flatten(value: Any) -> list[str]:
    out: list[str] = []
    if isinstance(value, str):
        out.append(value)
    elif isinstance(value, list):
        for item in value:
            out.extend(_flatten(item))
    elif isinstance(value, dict):
        for key, item in value.items():
            if isinstance(item, str):
                out.append(f"{key}={item}")
            else:
                out.extend(_flatten(item))
    return out


def _resource_bucket(name: str) -> str | None:
    parts = name.split("/")
    if len(parts) < 3:
        return None
    root, kind = parts[0], parts[2]
    if root == "assets":
        if kind in ("textures", "models", "blockstates"):
            return kind
        if kind == "lang":
            return "languages"
        if kind == "sounds.json":
            return "sound_definitions"
        return "other_assets"
    if root != "data":
        return None
    if kind in ("recipe", "recipes"):
        return "recipes"
    if kind in ("loot_table", "loot_tables"):
        return "loot_tables"
    if kind == "tags":
        return "tags"
    if kind == "worldgen":
        return "worldgen"
    if kind in ("structure", "structures"):
        return "structures"
    if kind in ("advancement", "advancements"):
        return "advancements"
    if kind in ("function", "functions"):
        return "functions"
    if kind in ("predicate", "predicates"):
        return "predicates"
    if kind in ("item_modifier", "item_modifiers"):
        return "item_modifiers"
    return "other_data"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _inspect_mixin_configs(
    archive: zipfile.ZipFile,
    names: set[str],
    paths: list[str],
    declared: Any,
    warnings: list[str],
) -> list[dict[str, Any]]:
    """Summarize declared Mixin configs without loading or applying their classes."""
    candidates = set(paths)
    if isinstance(declared, str):
        declared = [declared]
    if isinstance(declared, list):
        for item in declared:
            if isinstance(item, str):
                candidates.add(item)
            elif isinstance(item, dict) and isinstance(item.get("config"), str):
                candidates.add(item["config"])

    out: list[dict[str, Any]] = []
    for path in sorted(candidates):
        if path not in names:
            warnings.append(f"declared Mixin config is missing from JAR: {path}")
            out.append({"path": path, "loaded": False, "error": "file missing"})
            continue
        info = archive.getinfo(path)
        if info.file_size > MAX_METADATA_BYTES:
            warnings.append(f"Mixin config exceeds the metadata size limit: {path}")
            out.append({"path": path, "loaded": False, "error": "file too large"})
            continue
        try:
            value = json.loads(archive.read(path).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError, OSError, zipfile.BadZipFile) as exc:
            warnings.append(f"could not parse Mixin config {path}: {exc}")
            out.append({"path": path, "loaded": False, "error": "invalid JSON"})
            continue
        if not isinstance(value, dict):
            warnings.append(f"Mixin config is not a JSON object: {path}")
            out.append({"path": path, "loaded": False, "error": "not an object"})
            continue

        def class_list(key: str) -> list[str]:
            raw = value.get(key, [])
            if not isinstance(raw, list):
                warnings.append(f"Mixin config {path} has a non-list {key} field")
                return []
            return [entry for entry in raw if isinstance(entry, str)][:MAX_SAMPLES]

        injectors = value.get("injectors", {})
        out.append({
            "path": path,
            "loaded": True,
            "required": value.get("required"),
            "min_version": value.get("minVersion"),
            "package": value.get("package"),
            "plugin": value.get("plugin"),
            "common_mixins": class_list("mixins"),
            "client_mixins": class_list("client"),
            "server_mixins": class_list("server"),
            "default_require": injectors.get("defaultRequire")
                if isinstance(injectors, dict) else None,
            "java_class_transform_applied": False,
        })
    return out


def _inspect_access_wideners(
    archive: zipfile.ZipFile,
    names: set[str],
    paths: list[str],
    warnings: list[str],
) -> list[dict[str, Any]]:
    """Inventory AW directives and suggest native API mappings; never mutates code."""
    out: list[dict[str, Any]] = []
    supported_ops = {
        "accessible", "extendable", "mutable",
        "transitive-accessible", "transitive-extendable", "transitive-mutable",
    }
    for path in sorted(set(paths)):
        if path not in names:
            warnings.append(f"declared Access Widener is missing from JAR: {path}")
            out.append({"path": path, "loaded": False, "error": "file missing", "entries": []})
            continue
        info = archive.getinfo(path)
        if info.file_size > MAX_METADATA_BYTES:
            warnings.append(f"Access Widener exceeds the metadata size limit: {path}")
            out.append({"path": path, "loaded": False, "error": "file too large", "entries": []})
            continue
        try:
            source = archive.read(path).decode("utf-8")
        except (UnicodeDecodeError, OSError, zipfile.BadZipFile) as exc:
            warnings.append(f"could not read Access Widener {path}: {exc}")
            out.append({"path": path, "loaded": False, "error": "not UTF-8", "entries": []})
            continue

        lines = source.splitlines()
        header = next((line.strip() for line in lines if line.strip()), "")
        header_parts = header.split()
        header_valid = len(header_parts) == 3 and header_parts[0] == "accessWidener" \
            and header_parts[1] in ("v1", "v2") and header_parts[2] in ("named", "intermediary", "official")
        if not header_valid:
            warnings.append(f"Access Widener has an invalid or missing header: {path}")
        entries: list[dict[str, Any]] = []
        malformed = 0
        for line_number, original in enumerate(lines, 1):
            line = original.split("#", 1)[0].strip()
            if not line or line.startswith("accessWidener "):
                continue
            parts = line.split()
            if len(parts) < 3 or parts[0] not in supported_ops or parts[1] not in ("class", "field", "method"):
                malformed += 1
                continue
            operation, target_type, owner = parts[:3]
            if target_type == "class":
                if len(parts) != 3:
                    malformed += 1
                    continue
                member = None
                descriptor = None
            else:
                if len(parts) != 5:
                    malformed += 1
                    continue
                member, descriptor = parts[3], parts[4]
            if operation.endswith("accessible"):
                mapping = "replace with an explicit public read/query API where needed"
            elif operation.endswith("mutable"):
                mapping = "add a validated setter only if Horizon owns that state"
            else:
                mapping = "port the extension point to a native trait/callback; visibility alone is insufficient"
            entries.append({
                "line": line_number,
                "operation": operation,
                "target_type": target_type,
                "owner": owner,
                "member": member,
                "descriptor": descriptor,
                "native_migration_hint": mapping,
            })
            if len(entries) >= 2000:
                warnings.append(f"Access Widener directive report capped at 2000 entries: {path}")
                break
        if malformed:
            warnings.append(f"Access Widener {path} has {malformed} unparsed declaration line(s)")
        out.append({
            "path": path,
            "loaded": True,
            "header": header,
            "header_valid": header_valid,
            "entry_count": len(entries),
            "entries": entries,
            "visibility_changes_applied": False,
        })
    return out


def _entrypoint_migration_inventory(entrypoints: dict[str, Any]) -> list[dict[str, Any]]:
    """Describe Fabric entrypoint declarations and their native port boundary."""
    out: list[dict[str, Any]] = []
    for kind, declarations in sorted(entrypoints.items()):
        classes = _flatten(declarations)[:MAX_SAMPLES]
        out.append({
            "kind": str(kind),
            "declared": classes,
            "native_target": "P1 guest init plus explicit event/key/command hooks",
            "status": "manual-port-required",
            "java_class_invoked": False,
        })
    return out


def audit_jar(path: Path) -> dict[str, Any]:
    warnings: list[str] = []
    if not path.is_file():
        return {"path": str(path), "error": "not a regular file"}
    try:
        digest = _sha256(path)
    except OSError as exc:
        return {"path": str(path), "error": f"could not read file: {exc}"}
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as exc:
        return {"path": str(path), "sha256": digest, "error": f"not a readable ZIP/JAR: {exc}"}

    with archive:
        all_infos = archive.infolist()
        entry_count = len(all_infos)
        if entry_count > MAX_ENTRIES:
            warnings.append(f"archive has {entry_count} entries; inspected first {MAX_ENTRIES} only")
            all_infos = all_infos[:MAX_ENTRIES]
        names = {info.filename for info in all_infos}
        metadata = _metadata_value(archive, names, "fabric.mod.json", warnings)
        if not metadata:
            warnings.append("fabric.mod.json is missing or unreadable; Fabric metadata is unverified")

        resources = Counter({key: 0 for key in RESOURCE_BUCKETS})
        classes = [info for info in all_infos if info.filename.endswith(".class")]
        for info in all_infos:
            bucket = _resource_bucket(info.filename)
            if bucket:
                resources[bucket] += 1

        mixin_paths = sorted(
            name for name in names
            if name.lower().endswith((".mixins.json", ".mixin.json"))
            or (name.lower().endswith(".json") and "mixin" in name.lower())
        )
        access_wideners = sorted(
            name for name in names
            if name.lower().endswith((".accesswidener", ".access_widener", ".aw"))
        )
        declared_mixin = metadata.get("mixins", [])
        if isinstance(declared_mixin, str):
            declared_mixin = [declared_mixin]
        if not isinstance(declared_mixin, list):
            declared_mixin = []
        declared_mixin_names: list[str] = []
        for item in declared_mixin:
            if isinstance(item, str):
                declared_mixin_names.append(item)
            elif isinstance(item, dict):
                for key in ("config", "environment"):
                    if isinstance(item.get(key), str):
                        declared_mixin_names.append(f"{key}={item[key]}")
        declared_aw = metadata.get("accessWidener")
        if isinstance(declared_aw, str) and declared_aw and declared_aw not in access_wideners:
            access_wideners.append(declared_aw)

        total_class_bytes = 0
        scanned_classes = 0
        scan_failures: list[str] = []
        all_refs: set[str] = set()
        feature_classes: Counter[str] = Counter()
        java_versions: Counter[int] = Counter()
        method_reference_count = 0
        feature_markers = {
            feature: tuple(marker.lower() for marker in markers)
            for feature, markers in FEATURE_MARKERS.items()
        }

        for info in classes:
            if info.file_size > MAX_CLASS_BYTES:
                warnings.append(f"skipped oversized class file: {info.filename}")
                continue
            if total_class_bytes + info.file_size > MAX_TOTAL_CLASS_BYTES:
                warnings.append("class scan stopped at the total uncompressed-byte limit")
                break
            total_class_bytes += info.file_size
            try:
                scanned = parse_class_constant_pool(archive.read(info))
            except (ValueError, struct.error, OSError, zipfile.BadZipFile) as exc:
                scan_failures.append(f"{info.filename}: {exc}")
                continue
            scanned_classes += 1
            java_versions[scanned["major_version"]] += 1
            all_refs.update(scanned["references"])
            tags = scanned["constant_pool_tags"]
            method_reference_count += tags.get(10, 0) + tags.get(11, 0)
            strings = [value.lower() for value in scanned["utf8_values"]]
            for feature, markers in feature_markers.items():
                if any(marker in value for marker in markers for value in strings):
                    feature_classes[feature] += 1

        namespaces: set[str] = set()
        for name in names:
            parts = name.split("/")
            if len(parts) > 1 and parts[0] in ("assets", "data"):
                if re.fullmatch(r"[a-z0-9_.-]+", parts[1]):
                    namespaces.add(parts[1])

        fabric_api_refs = sorted(ref for ref in all_refs if "net/fabricmc/fabric/api/" in ref)
        loader_refs = sorted(
            ref for ref in all_refs
            if ref.startswith("net/fabricmc/loader/") or ref.startswith("net/fabricmc/api/")
        )
        minecraft_refs = sorted(ref for ref in all_refs if "net/minecraft/" in ref)
        mixin_refs = sorted(ref for ref in all_refs if ref.startswith("org/spongepowered/asm/mixin/"))
        required_deps = metadata.get("depends", {})
        optional_deps = metadata.get("recommends", {})
        suggested_deps = metadata.get("suggests", {})
        conflicts = metadata.get("breaks", {})
        required_deps = required_deps if isinstance(required_deps, dict) else {}
        optional_deps = optional_deps if isinstance(optional_deps, dict) else {}
        suggested_deps = suggested_deps if isinstance(suggested_deps, dict) else {}
        conflicts = conflicts if isinstance(conflicts, dict) else {}
        entrypoints = metadata.get("entrypoints", {})
        entrypoints = entrypoints if isinstance(entrypoints, dict) else {}
        entrypoint_summary = {
            str(key): _flatten(value)[:MAX_SAMPLES] for key, value in entrypoints.items()
        }

        entrypoint_migration = _entrypoint_migration_inventory(entrypoints)
        mixin_inventory = _inspect_mixin_configs(
            archive, names, mixin_paths, metadata.get("mixins", []), warnings
        )
        access_widener_inventory = _inspect_access_wideners(
            archive, names, sorted(set(access_wideners)), warnings
        )

        if len(classes) == 0:
            assessment = (
                "resource-only candidate; every data/ and assets/ format still needs "
                "Horizon format and identifier validation"
            )
        else:
            assessment = (
                "Java code present; this JAR must be ported or adapted to Horizon's native "
                "mod API. This report does not execute or convert the code."
            )
        if mixin_paths or declared_mixin or mixin_refs:
            warnings.append(
                "Mixin metadata/references detected; injections cannot be applied directly "
                "to Horizon's Rust runtime"
            )
        if access_wideners:
            warnings.append(
                "access widener detected; private Minecraft members need a public Horizon "
                "API equivalent or manual port"
            )
        if scan_failures:
            warnings.append(f"{len(scan_failures)} class file(s) could not be scanned")

        return {
            "schema_version": SCHEMA_VERSION,
            "path": str(path),
            "sha256": digest,
            "metadata": {
                "id": metadata.get("id", ""),
                "name": metadata.get("name", ""),
                "version": metadata.get("version", ""),
                "environment": metadata.get("environment", "*"),
                "license": metadata.get("license", ""),
                "minecraft_constraints": required_deps.get("minecraft", ""),
                "fabric_loader_constraints": required_deps.get("fabricloader", ""),
                "required_dependencies": dict(sorted(required_deps.items())),
                "optional_dependencies": dict(sorted(optional_deps.items())),
                "suggested_dependencies": dict(sorted(suggested_deps.items())),
                "breaks": dict(sorted(conflicts.items())),
                "entrypoints": entrypoint_summary,
                "declared_mixins": declared_mixin_names[:MAX_SAMPLES],
                "declared_access_widener": declared_aw or "",
            },
            "archive": {
                "entry_count": entry_count,
                "class_count": len(classes),
                "scanned_class_count": scanned_classes,
                "class_scan_failures": scan_failures[:MAX_SAMPLES],
                "class_bytes_scanned": total_class_bytes,
                "class_major_versions": dict(sorted(java_versions.items())),
                "method_reference_count": method_reference_count,
                "namespaces": sorted(namespaces)[:MAX_SAMPLES],
                "resource_counts": dict(resources),
                "mixin_config_paths": mixin_paths[:MAX_SAMPLES],
                "access_widener_paths": sorted(set(access_wideners))[:MAX_SAMPLES],
            },
            "code_references": {
                "minecraft_classes": minecraft_refs[:MAX_SAMPLES],
                "minecraft_reference_count": len(minecraft_refs),
                "fabric_api_classes": fabric_api_refs[:MAX_SAMPLES],
                "fabric_api_reference_count": len(fabric_api_refs),
                "fabric_loader_classes": loader_refs[:MAX_SAMPLES],
                "fabric_loader_reference_count": len(loader_refs),
                "mixin_classes": mixin_refs[:MAX_SAMPLES],
                "mixin_reference_count": len(mixin_refs),
                "feature_evidence_class_counts": dict(sorted(feature_classes.items())),
            },
            "fabric_compatibility": {
                "entrypoints": entrypoint_migration,
                "mixin_configs": mixin_inventory,
                "access_wideners": access_widener_inventory,
                "java_code_invoked": False,
                "bytecode_transformations_applied": False,
            },
            "assessment": assessment,
            "warnings": warnings,
            "limitations": [
                "Static constant-pool analysis misses reflective references, generated classes, "
                "runtime registrations and code paths not represented by constant-pool strings.",
                "A reference count is evidence for triage, not proof that a feature is required.",
                "No bytecode is decompiled, executed, transformed or translated by this tool.",
            ],
        }


def _collect_inputs(inputs: list[Path]) -> list[Path]:
    found: list[Path] = []
    for input_path in inputs:
        found.extend(sorted(input_path.rglob("*.jar")) if input_path.is_dir() else [input_path])
    unique: list[Path] = []
    seen: set[str] = set()
    for path in found:
        key = str(path.resolve(strict=False))
        if key not in seen:
            seen.add(key)
            unique.append(path)
    return unique


def _markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Fabric JAR audit", "",
        "Static triage only; this report does not claim any JAR is executable or converted.", "",
    ]
    for item in report["results"]:
        if "error" in item:
            lines.extend([f"## {item['path']}", "", f"Error: {item['error']}", ""])
            continue
        meta, archive, refs = item["metadata"], item["archive"], item["code_references"]
        lines.extend([
            f"## {meta.get('name') or meta.get('id') or Path(item['path']).name}", "",
            f"- Path: {item['path']}",
            f"- SHA-256: {item['sha256']}",
            f"- Mod ID/version: {meta.get('id') or '(unknown)'} / {meta.get('version') or '(unknown)'}",
            f"- License: {meta.get('license') or '(unspecified)'}",
            f"- Classes: {archive['class_count']} total, {archive['scanned_class_count']} scanned",
            f"- Direct Minecraft references: {refs['minecraft_reference_count']}",
            f"- Fabric API references: {refs['fabric_api_reference_count']}",
            f"- Loader references: {refs['fabric_loader_reference_count']}",
            f"- Mixin references: {refs['mixin_reference_count']}",
            f"- Assessment: {item['assessment']}", "", "### Resource counts", "",
            "| Kind | Count |", "|---|---:|",
        ])
        lines.extend(f"| {kind} | {count} |" for kind, count in archive["resource_counts"].items())
        lines.extend(["", "### Warnings", ""])
        lines.extend([f"- {warning}" for warning in item["warnings"]] or ["- None recorded."])
        lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Inspect Fabric JAR metadata, assets/data paths and Java class references."
    )
    parser.add_argument("inputs", nargs="+", type=Path, help="JAR file(s) or directories to scan recursively")
    parser.add_argument("--output", type=Path, help="write JSON report to this path")
    parser.add_argument("--markdown", type=Path, help="also write a human-readable Markdown report")
    args = parser.parse_args(argv)
    paths = _collect_inputs(args.inputs)
    if not paths:
        parser.error("no JAR files found in the supplied inputs")
    report = {
        "schema_version": SCHEMA_VERSION,
        "tool": "fabric_bridge_audit.py",
        "results": [audit_jar(path) for path in paths],
    }
    payload = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding="utf-8")
    else:
        sys.stdout.write(payload)
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(_markdown(report), encoding="utf-8")
    return 1 if any("error" in row for row in report["results"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
