#!/usr/bin/env python3
"""Static triage of Fabric JARs for the Horizon Fabric Bridge.

Never extracts or executes a JAR. Reads metadata, resource paths, and Java
class-file constant pools. It cannot prove a mod will convert successfully.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import struct
import sys
import zipfile
from collections import Counter
from pathlib import Path, PurePosixPath
from typing import Any

SCHEMA_VERSION = 2
MAX_ENTRIES = 250_000
MAX_METADATA_BYTES = 2 * 1024 * 1024
MAX_CLASS_BYTES = 16 * 1024 * 1024
MAX_TOTAL_CLASS_BYTES = 256 * 1024 * 1024
MAX_SAMPLES = 100
MAX_NESTED_JARS = 32
MAX_NESTED_JAR_BYTES = 32 * 1024 * 1024
MAX_NESTED_TOTAL_BYTES = 64 * 1024 * 1024
MAX_NESTED_CLASS_BYTES = 128 * 1024 * 1024
MAX_NESTED_ENTRIES = 50_000
MAX_SUPPORTED_CLASS_MAJOR = 69  # Java 25, required by Fabric API 0.165.0+26.3


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



def _load_fabric_api_catalog() -> dict[str, Any]:
    """Load the checked-in catalog generated from FabricMC/fabric-api branch 26.3."""
    catalog_path = Path(__file__).resolve().with_name("fabric_api_26_3_catalog.json")
    try:
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"official Fabric API catalog is missing/invalid: {exc}") from exc
    if catalog.get("minecraft_version") != "26.3" or not isinstance(catalog.get("api_class_to_module"), dict):
        raise RuntimeError("official Fabric API catalog has an unexpected schema or target version")
    return catalog


def _module_for_api_ref(reference: str, catalog: dict[str, Any]) -> str | None:
    """Map a constant-pool class reference to its exact official Fabric API module."""
    prefix = "net/fabricmc/fabric/"
    if not reference.startswith(prefix):
        return None
    internal_name = reference[len(prefix):]
    class_map = catalog.get("api_class_to_module", {})
    module = class_map.get(internal_name)
    if isinstance(module, list):
        # Conflicting ownership must not be hidden as a successful classification.
        return None
    if isinstance(module, str):
        return module
    # Inner classes appear as Outer$Inner in constant pools. The catalog contains
    # source-level top-level/nested classes as separate source files where public.
    outer = internal_name.split("$", 1)[0]
    module = class_map.get(outer)
    return module if isinstance(module, str) else None


def _catalog_module_names(catalog: dict[str, Any]) -> set[str]:
    return {
        item.get("name") for item in catalog.get("modules", [])
        if isinstance(item, dict) and isinstance(item.get("name"), str)
    }


def _inspect_declared_nested_jars(
    archive: zipfile.ZipFile,
    names: set[str],
    metadata: dict[str, Any],
    warnings: list[str],
    catalog: dict[str, Any],
) -> dict[str, Any]:
    """Inspect Fabric Loader's declared nested JARs in memory, without extracting/executing them."""
    declared = metadata.get("jars", [])
    if not isinstance(declared, list):
        warnings.append("fabric.mod.json 'jars' field is not a list")
        declared = []
    output: list[dict[str, Any]] = []
    all_api_refs: set[str] = set()
    nested_class_count = 0
    nested_class_bytes = 0
    nested_total_bytes = 0
    seen_paths: set[str] = set()

    for item in declared[:MAX_NESTED_JARS]:
        entry_path = item.get("file") if isinstance(item, dict) else None
        if not isinstance(entry_path, str) or not entry_path:
            warnings.append("ignored malformed nested JAR declaration in fabric.mod.json")
            output.append({"path": entry_path, "status": "invalid-declaration"})
            continue
        parts = entry_path.split("/")
        safe = (
            not entry_path.startswith("/")
            and "\\" not in entry_path
            and "\x00" not in entry_path
            and all(part not in ("", ".", "..") for part in parts)
        )
        if not safe:
            warnings.append(f"unsafe nested JAR path rejected: {entry_path}")
            output.append({"path": entry_path, "status": "unsafe-path"})
            continue
        if entry_path in seen_paths:
            warnings.append(f"duplicate nested JAR declaration: {entry_path}")
            output.append({"path": entry_path, "status": "duplicate-declaration"})
            continue
        seen_paths.add(entry_path)
        info = archive.getinfo(entry_path) if entry_path in names else None
        if info is None or info.is_dir():
            warnings.append(f"declared nested JAR is missing: {entry_path}")
            output.append({"path": entry_path, "status": "missing"})
            continue
        if info.file_size > MAX_NESTED_JAR_BYTES:
            warnings.append(f"nested JAR exceeds {MAX_NESTED_JAR_BYTES} byte limit: {entry_path}")
            output.append({"path": entry_path, "status": "size-limit", "archive_bytes": info.file_size})
            continue
        if nested_total_bytes + info.file_size > MAX_NESTED_TOTAL_BYTES:
            warnings.append("nested JAR scan stopped at total archive-byte limit")
            output.append({"path": entry_path, "status": "total-size-limit", "archive_bytes": info.file_size})
            break
        nested_total_bytes += info.file_size
        nested_refs: set[str] = set()
        nested_classes = 0
        nested_scanned = 0
        nested_bytes = 0
        nested_versions: Counter[int] = Counter()
        nested_failures = 0
        try:
            raw_nested = archive.read(info)
            with zipfile.ZipFile(io.BytesIO(raw_nested)) as child:
                child_infos = child.infolist()
                if len(child_infos) > MAX_NESTED_ENTRIES:
                    warnings.append(f"nested JAR has too many entries; capped: {entry_path}")
                    child_infos = child_infos[:MAX_NESTED_ENTRIES]
                for child_info in child_infos:
                    if child_info.is_dir() or not child_info.filename.endswith(".class"):
                        continue
                    nested_classes += 1
                    if child_info.file_size > MAX_CLASS_BYTES:
                        nested_failures += 1
                        continue
                    if nested_class_bytes + nested_bytes + child_info.file_size > MAX_NESTED_CLASS_BYTES:
                        warnings.append("nested JAR class scan stopped at total uncompressed-byte limit")
                        break
                    try:
                        parsed = parse_class_constant_pool(child.read(child_info))
                    except (ValueError, struct.error, OSError, zipfile.BadZipFile):
                        nested_failures += 1
                        continue
                    nested_scanned += 1
                    nested_bytes += child_info.file_size
                    nested_versions[parsed["major_version"]] += 1
                    nested_refs.update(
                        ref for ref in parsed["references"]
                        if ref.startswith("net/fabricmc/fabric/api/")
                    )
        except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
            warnings.append(f"cannot inspect nested JAR {entry_path}: {exc}")
            output.append({"path": entry_path, "status": "invalid-zip"})
            continue
        nested_class_bytes += nested_bytes
        nested_class_count += nested_classes
        all_api_refs.update(nested_refs)
        nested_modules = sorted({
            module for ref in nested_refs
            if (module := _module_for_api_ref(ref, catalog)) is not None
        })
        nested_unknown = sorted(ref for ref in nested_refs if _module_for_api_ref(ref, catalog) is None)
        output.append({
            "path": entry_path,
            "status": "inspected",
            "archive_bytes": info.file_size,
            "entry_count": min(len(child_infos), MAX_NESTED_ENTRIES),
            "class_count": nested_classes,
            "scanned_class_count": nested_scanned,
            "class_bytes_scanned": nested_bytes,
            "class_major_versions": dict(sorted(nested_versions.items())),
            "fabric_api_modules": nested_modules,
            "unmapped_fabric_api_refs": nested_unknown[:MAX_SAMPLES],
            "scan_failures": nested_failures,
            "java_code_invoked": False,
        })

    if len(declared) > MAX_NESTED_JARS:
        warnings.append(f"nested JAR declarations capped at {MAX_NESTED_JARS}")
    return {
        "declared_count": len(declared),
        "inspected_count": sum(row.get("status") == "inspected" for row in output),
        "class_count": nested_class_count,
        "class_bytes_scanned": nested_class_bytes,
        "archive_bytes_read": nested_total_bytes,
        "entries": output,
        "fabric_api_refs": sorted(all_api_refs),
        "java_code_invoked": False,
    }

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
    catalog = _load_fabric_api_catalog()
    catalog_modules = _catalog_module_names(catalog)
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
        elif metadata.get("schemaVersion") != 1:
            warnings.append("fabric.mod.json schemaVersion is missing or is not the supported value 1")
        nested_report = _inspect_declared_nested_jars(archive, names, metadata, warnings, catalog)

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

        fabric_api_refs = sorted({ref for ref in all_refs if "net/fabricmc/fabric/api/" in ref and not ref.endswith("/")} | {ref for ref in nested_report["fabric_api_refs"] if not ref.endswith("/")})
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

        fabric_api_modules = Counter()
        fabric_api_module_evidence: dict[str, list[str]] = {}
        unmapped_fabric_api_refs: list[str] = []
        for reference in fabric_api_refs:
            module = _module_for_api_ref(reference, catalog)
            if module is None:
                unmapped_fabric_api_refs.append(reference)
            else:
                fabric_api_modules[module] += 1
                fabric_api_module_evidence.setdefault(module, [])
                if len(fabric_api_module_evidence[module]) < 10:
                    fabric_api_module_evidence[module].append(reference)

        dependency_groups = {
            "required": required_deps,
            "optional": optional_deps,
            "suggested": suggested_deps,
        }
        module_dependencies: dict[str, dict[str, Any]] = {}
        for group_name, dependencies in dependency_groups.items():
            for dependency_name, constraint in dependencies.items():
                if dependency_name == "fabric-api" or dependency_name in catalog_modules:
                    module_dependencies.setdefault(dependency_name, {
                        "constraints": {},
                        "declared_in": [],
                        "aggregate_dependency": dependency_name == "fabric-api",
                    })
                    module_dependencies[dependency_name]["constraints"][group_name] = constraint
                    module_dependencies[dependency_name]["declared_in"].append(group_name)

        class_major_max = max(
            [*java_versions.keys(), *(major for nested in nested_report["entries"]
                for major in (nested.get("class_major_versions") or {}).keys())],
            default=None,
        )
        java_version_compatibility = {
            "max_class_major": class_major_max,
            "max_supported_class_major": MAX_SUPPORTED_CLASS_MAJOR,
            "target_java": 25,
            "compatible_with_target": class_major_max is None or class_major_max <= MAX_SUPPORTED_CLASS_MAJOR,
        }
        if class_major_max is not None and class_major_max > MAX_SUPPORTED_CLASS_MAJOR:
            warnings.append(
                f"bytecode class version {class_major_max} exceeds Java 25 (class major {MAX_SUPPORTED_CLASS_MAJOR}); "
                "recompile/porting or a different target decision is required"
            )
        if unmapped_fabric_api_refs:
            warnings.append(
                f"{len(unmapped_fabric_api_refs)} Fabric API reference(s) do not map to a public source class "
                "in the checked-in Fabric API 26.3 catalog; review them manually"
            )
        if nested_report["declared_count"] and nested_report["inspected_count"] < nested_report["declared_count"]:
            warnings.append("one or more nested JARs were not fully inspected; hidden dependencies may remain")

        if len(classes) == 0 and nested_report["class_count"] == 0 and not entrypoints and not mixin_paths and not access_wideners:
            assessment = (
                "resource-only candidate; every data/ and assets/ format still needs "
                "Horizon format and identifier validation"
            )
        else:
            assessment = (
                "Java entrypoints/classes or bytecode-level hooks are declared; behavior must be "
                "ported to Horizon's native mod API. This report does not execute or convert code."
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
                "java_version_compatibility": java_version_compatibility,
                "nested_jars": nested_report,
                "method_reference_count": method_reference_count,
                "namespaces": sorted(namespaces)[:MAX_SAMPLES],
                "resource_counts": dict(resources),
                "mixin_config_paths": mixin_paths[:MAX_SAMPLES],
                "access_widener_paths": sorted(set(access_wideners))[:MAX_SAMPLES],
            },
            "code_references": {
                "fabric_api_catalog": {
                    "minecraft_version": catalog.get("minecraft_version"),
                    "fabric_api_version": catalog.get("fabric_api_version"),
                    "source_ref": catalog.get("source_ref"),
                    "source_commit": catalog.get("source_commit"),
                    "detected_modules_by_reference_count": dict(sorted(fabric_api_modules.items())),
                    "module_evidence": dict(sorted(fabric_api_module_evidence.items())),
                    "declared_module_dependencies": dict(sorted(module_dependencies.items())),
                    "unmapped_api_reference_count": len(unmapped_fabric_api_refs),
                    "unmapped_api_references": sorted(unmapped_fabric_api_refs)[:MAX_SAMPLES],
                },
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
                "Fabric API module coverage is based on the checked-in official 26.3 public-source catalog; dependency declarations and class references are evidence, not proof of runtime use.",
                "Nested JAR inspection follows only Fabric Loader's declared jars list and is bounded by archive/class size limits.",
                "Mixin/access-widener and direct Minecraft API usages remain static evidence; this tool never applies Java bytecode transformations.",
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
