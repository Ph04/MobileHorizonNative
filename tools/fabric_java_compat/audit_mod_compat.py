#!/usr/bin/env python3
"""Static compatibility audit for an original Fabric mod JAR.

This tool never loads or executes mod bytecode. It inventories class references,
entrypoints, mixin configurations, access wideners, dependencies and declared
nested JARs, then compares referenced symbols with the official 26.3 ABI catalogs
and the explicitly implemented Horizon shim surface.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
MAX_ENTRY_BYTES = 16 * 1024 * 1024
MAX_ENTRIES = 100_000
MAX_NESTED_JARS = 64
MAX_NESTED_BYTES = 32 * 1024 * 1024
MAX_TOTAL_NESTED_BYTES = 128 * 1024 * 1024
MAX_JSON_BYTES = 1024 * 1024
CLASS_REF = re.compile(rb"(?:net/minecraft|net/fabricmc/fabric/api|org/spongepowered)/[A-Za-z0-9_$/]+")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_archive_path(name: str) -> bool:
    if not name or name.startswith(("/", "\\")) or "\\" in name or "\x00" in name:
        return False
    return all(part not in ("", ".", "..") for part in name.split("/"))


def class_version(data: bytes, name: str) -> int:
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError(f"invalid Java class file: {name}")
    minor, major = struct.unpack_from(">HH", data, 4)
    if major < 45 or major > 100:
        raise ValueError(f"unsupported class-file major version {major}: {name}")
    return major


def catalog_names(path: Path | None) -> set[str]:
    if path is None:
        return set()
    document = json.loads(path.read_text(encoding="utf-8"))
    source = document.get("classes", [])
    if isinstance(source, dict):
        rows = [{"name": key} for key in source]
    elif isinstance(source, list):
        rows = source
    else:
        rows = []
    result = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        name = row.get("name") or row.get("class_name") or row.get("class")
        if isinstance(name, str) and name:
            result.add(name.replace("/", "."))
    return result


def audit_mod(
    mod_jar: Path,
    minecraft_abi: Path | None = None,
    fabric_api_abi: Path | None = None,
    shim_manifest: Path | None = None,
) -> dict[str, Any]:
    if not mod_jar.is_file() or mod_jar.stat().st_size <= 0 or mod_jar.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError("mod JAR is missing, empty, or larger than 128 MiB")

    mc_catalog = catalog_names(minecraft_abi)
    api_catalog = catalog_names(fabric_api_abi)
    surface = json.loads(shim_manifest.read_text(encoding="utf-8")) if shim_manifest else {}
    implemented_mc = {str(v).replace("/", ".") for v in surface.get("implemented_minecraft_classes", [])}
    implemented_api = {str(v).replace("/", ".") for v in surface.get("implemented_fabric_api_classes", [])}
    native_ops = list(surface.get("native_bridge_operations", []))
    native_events = list(surface.get("native_events", []))

    class_refs: set[str] = set()
    api_refs: set[str] = set()
    class_files: list[str] = []
    major_versions: list[int] = []
    mixin_configs: list[dict[str, Any]] = []
    access_widener_entries: list[str] = []
    dependencies: dict[str, Any] = {}
    entrypoints: dict[str, list[str]] = {}
    nested_jar_names: list[str] = []
    total_nested_bytes = 0
    visited_nested = 0
    archive_bytes = 0
    mod_metadata: dict[str, Any] = {}
    digest = hashlib.sha256()

    def scan_archive(archive: zipfile.ZipFile, label: str, depth: int = 0) -> None:
        nonlocal archive_bytes, total_nested_bytes, visited_nested, mod_metadata
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ValueError(f"{label} has more than {MAX_ENTRIES} entries")
        names = {info.filename for info in infos}
        metadata_info = archive.getinfo("fabric.mod.json") if "fabric.mod.json" in names else None
        metadata: dict[str, Any] = {}
        if metadata_info is not None:
            if metadata_info.file_size <= 0 or metadata_info.file_size > MAX_JSON_BYTES:
                raise ValueError(f"invalid fabric.mod.json size in {label}")
            parsed = json.loads(archive.read(metadata_info).decode("utf-8"))
            if not isinstance(parsed, dict):
                raise ValueError(f"fabric.mod.json is not an object in {label}")
            metadata = parsed
            if depth == 0:
                mod_metadata = metadata

        for info in infos:
            if info.is_dir():
                continue
            name = info.filename
            if not safe_archive_path(name):
                raise ValueError(f"unsafe ZIP path in {label}: {name!r}")
            if info.file_size < 0 or info.file_size > MAX_ENTRY_BYTES and name.endswith(".class"):
                raise ValueError(f"entry outside accepted size range in {label}: {name}")
            if name.endswith(".class"):
                if info.file_size <= 0 or info.file_size > MAX_ENTRY_BYTES:
                    raise ValueError(f"class file outside accepted size range: {name}")
                raw = archive.read(info)
                major_versions.append(class_version(raw, f"{label}!/{name}"))
                class_files.append(name)
                for match in CLASS_REF.findall(raw):
                    normalized = match.decode("ascii", "ignore").replace("/", ".")
                    if normalized.startswith("net.minecraft."):
                        class_refs.add(normalized)
                    elif normalized.startswith("net.fabricmc.fabric.api."):
                        api_refs.add(normalized)
            elif name.endswith(".accesswidener") or name.endswith(".accessor"):
                if info.file_size > MAX_JSON_BYTES:
                    raise ValueError(f"access widener is too large: {name}")
                text = archive.read(info).decode("utf-8", "replace")
                if name.endswith(".accesswidener"):
                    for raw_line in text.splitlines():
                        line = raw_line.strip()
                        if line and not line.startswith("#") and not line.startswith("accessWidener "):
                            access_widener_entries.append(line)
            elif name.endswith(".mixins.json") or name.endswith(".mixin.json"):
                if info.file_size > MAX_JSON_BYTES:
                    raise ValueError(f"mixin config is too large: {name}")
                config = json.loads(archive.read(info).decode("utf-8"))
                if isinstance(config, dict):
                    package = str(config.get("package", ""))
                    declared = []
                    for group in ("mixins", "client", "server"):
                        values = config.get(group, [])
                        if isinstance(values, list):
                            for value in values:
                                if isinstance(value, str):
                                    fqcn = (package + "." + value.replace("/", ".")) if package else value.replace("/", ".")
                                    declared.append({"group": group, "class": fqcn})
                    mixin_configs.append({
                        "path": name,
                        "required": bool(config.get("required", False)),
                        "package": package,
                        "declared_mixins": declared,
                    })

        # Nested JARs are recursively inspected; the embedded archive bytes are never executed.
        jars = metadata.get("jars", []) if isinstance(metadata, dict) else []
        if jars:
            if depth >= 8:
                raise ValueError(f"nested JAR depth limit exceeded in {label}")
            if not isinstance(jars, list):
                raise ValueError(f"invalid nested JAR declarations in {label}")
            for declaration in jars:
                if not isinstance(declaration, dict) or not isinstance(declaration.get("file"), str):
                    raise ValueError(f"invalid nested JAR declaration in {label}")
                nested_name = declaration["file"]
                if not safe_archive_path(nested_name) or not nested_name.lower().endswith(".jar"):
                    raise ValueError(f"unsafe nested JAR path: {nested_name!r}")
                if nested_name not in names:
                    raise ValueError(f"declared nested JAR is missing: {nested_name}")
                nested_info = archive.getinfo(nested_name)
                if nested_info.file_size <= 0 or nested_info.file_size > MAX_NESTED_BYTES:
                    raise ValueError(f"nested JAR outside size limit: {nested_name}")
                visited_nested += 1
                total_nested_bytes += nested_info.file_size
                if visited_nested > MAX_NESTED_JARS or total_nested_bytes > MAX_TOTAL_NESTED_BYTES:
                    raise ValueError("nested JAR limits exceeded")
                nested_jar_names.append(f"{label}!/{nested_name}")
                raw = archive.read(nested_info)
                try:
                    import io
                    with zipfile.ZipFile(io.BytesIO(raw), "r") as nested:
                        scan_archive(nested, f"{label}!/{nested_name}", depth + 1)
                except zipfile.BadZipFile as error:
                    raise ValueError(f"declared nested entry is not a valid JAR: {nested_name}") from error

    with mod_jar.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    try:
        with zipfile.ZipFile(mod_jar, "r") as archive:
            scan_archive(archive, mod_jar.name)
    except zipfile.BadZipFile as error:
        raise ValueError("mod file is not a valid ZIP/JAR") from error

    mod_id = str(mod_metadata.get("id", ""))
    if not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", mod_id):
        raise ValueError("fabric.mod.json has an invalid mod id")
    for group, value in (mod_metadata.get("entrypoints") or {}).items():
        normalized = []
        values = value if isinstance(value, list) else [value]
        for entry in values:
            if isinstance(entry, str):
                normalized.append(entry)
            elif isinstance(entry, dict) and isinstance(entry.get("value"), str):
                normalized.append(entry["value"])
        entrypoints[str(group)] = normalized
    depends = mod_metadata.get("depends", {})
    if isinstance(depends, dict):
        dependencies = {str(key): value for key, value in depends.items()}

    mc_exists_official = sorted(name for name in class_refs if not mc_catalog or name in mc_catalog)
    mc_missing_official = sorted(name for name in class_refs if mc_catalog and name not in mc_catalog)
    mc_missing_shims = sorted(name for name in class_refs if name not in implemented_mc)
    api_exists_official = sorted(name for name in api_refs if not api_catalog or name in api_catalog)
    api_missing_official = sorted(name for name in api_refs if api_catalog and name not in api_catalog)
    api_missing_shims = sorted(name for name in api_refs if name not in implemented_api)

    features = []
    all_refs = class_refs | api_refs
    if any("BuiltInRegistries" in name or name.endswith(".Registry") for name in all_refs):
        features.append("registry_registration")
    if any(".DataComponentType" in name for name in all_refs):
        features.append("custom_data_components")
    if any(any(part in name for part in ("PayloadTypeRegistry", "ServerPlayNetworking", "ClientPlayNetworking", "CustomPacketPayload")) for name in all_refs):
        features.append("custom_payload_networking")
    if any(".client.gui." in name or ".client.renderer." in name for name in class_refs):
        features.append("client_gui_and_rendering")
    if any(any(part in name for part in ("BiomeSource", "ChunkGenerator", "ServerChunkCache", "ServerLevel", "BiomeResolver")) for name in class_refs):
        features.append("biome_world_generation")
    if mixin_configs:
        features.append("mixins")
    if access_widener_entries:
        features.append("access_widener")

    blockers = []
    if mc_missing_shims:
        blockers.append({
            "id": "missing_minecraft_java_shims",
            "count": len(mc_missing_shims),
            "message": "Referenced Minecraft classes do not have equivalent Java shim classes yet.",
        })
    if api_missing_shims:
        blockers.append({
            "id": "missing_fabric_api_adapters",
            "count": len(api_missing_shims),
            "message": "Referenced Fabric API classes are not implemented as Java adapters to Horizon hooks.",
        })
    if mixin_configs:
        blockers.append({
            "id": "mixins_not_mapped_to_native_hooks",
            "count": sum(len(item["declared_mixins"]) for item in mixin_configs),
            "message": "Mixin declarations require supported class transformations or explicit Horizon native-hook adapters.",
        })
    if access_widener_entries:
        blockers.append({
            "id": "access_widener_not_applied",
            "count": len(access_widener_entries),
            "message": "Access widener entries have not been mapped to compatible Java shims or native hooks.",
        })
    if "registry_registration" in features:
        blockers.append({"id":"registry_adapters_missing","count":1,"message":"Native block/item/data-component registration is not yet exposed through Java-compatible registries."})
    if "custom_payload_networking" in features:
        blockers.append({"id":"custom_payload_adapters_missing","count":1,"message":"Fabric custom-payload registration and handlers are not bridged to Horizon's network layer."})
    if "client_gui_and_rendering" in features:
        blockers.append({"id":"client_gui_adapters_missing","count":1,"message":"Minecraft client GUI and render APIs are not present as Java-compatible objects."})
    if "biome_world_generation" in features:
        blockers.append({"id":"biome_query_adapters_missing","count":1,"message":"Native biome/world-generation queries are not exposed through the expected Minecraft Java APIs."})

    return {
        "schema_version": 1,
        "tool": "MobileHorizonNative Fabric Compatibility Audit",
        "audit_only": True,
        "executed_mod_code": False,
        "mod": {
            "id": mod_id,
            "name": mod_metadata.get("name", mod_id),
            "version": str(mod_metadata.get("version", "unknown")),
            "sha256": digest.hexdigest(),
            "size_bytes": mod_jar.stat().st_size,
            "class_count": len(class_files),
            "max_class_major": max(major_versions, default=0),
            "entrypoints": entrypoints,
            "dependencies": dependencies,
            "nested_jars": nested_jar_names,
        },
        "target_minecraft": str(surface.get("target_minecraft", "26.3")),
        "official_minecraft_references": mc_exists_official,
        "missing_from_official_minecraft_abi": mc_missing_official,
        "missing_minecraft_java_shims": mc_missing_shims,
        "fabric_api_references": api_exists_official,
        "missing_from_official_fabric_api_abi": api_missing_official,
        "missing_fabric_api_adapters": api_missing_shims,
        "mixins": mixin_configs,
        "access_widener_entries": access_widener_entries,
        "required_feature_families": sorted(set(features)),
        "native_bridge_operations_available": native_ops,
        "native_events_available": native_events,
        "blockers": blockers,
        "compatibility_status": "BLOCKED" if blockers or mc_missing_official or api_missing_official else "STATIC_AUDIT_ONLY",
        "note": "A clean static audit is not proof that the mod runs. Runtime class behavior, event ordering, Mixin effects and gameplay must be tested in an isolated real Loader run and inside Horizon.",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mod", type=Path, required=True)
    parser.add_argument("--minecraft-abi", type=Path)
    parser.add_argument("--fabric-api-abi", type=Path)
    parser.add_argument("--shim-manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit_mod(args.mod, args.minecraft_abi, args.fabric_api_abi, args.shim_manifest)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"Static compatibility audit: {report['mod']['id']} {report['mod']['version']}; "
        f"{report['mod']['class_count']} classes; "
        f"{len(report['official_minecraft_references'])} Minecraft references; "
        f"{len(report['fabric_api_references'])} Fabric API references; "
        f"{len(report['mixins'])} mixin configs; "
        f"{len(report['access_widener_entries'])} access widener entries; "
        f"{len(report['blockers'])} blocker categories."
    )
    print(f"Report: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
