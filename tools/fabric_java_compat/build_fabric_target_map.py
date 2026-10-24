#!/usr/bin/env python3
"""Build a static Fabric API -> Minecraft Java -> Horizon target map.

The tool inspects class-file constant pools and metadata only. It never loads or
executes bytecode. A native-module candidate is not proof of semantic compatibility.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any

MAX_ROOT_BYTES = 128 * 1024 * 1024
MAX_NESTED_BYTES = 32 * 1024 * 1024
MAX_TOTAL_NESTED_BYTES = 128 * 1024 * 1024
MAX_CLASS_BYTES = 16 * 1024 * 1024
MAX_ENTRIES = 100_000
MAX_NESTED_JARS = 128
MAX_CLASSES = 60_000
MAX_JSON_BYTES = 2 * 1024 * 1024
MC_PREFIX = "net/minecraft/"
FABRIC_PREFIX = "net/fabricmc/fabric/"
MC_CLASS_PATTERN = re.compile(r"net/minecraft/[A-Za-z0-9_$/]+")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_archive_path(value: str) -> bool:
    return bool(value) and not value.startswith(("/", "\\")) and "\\" not in value and "\x00" not in value and all(
        part not in ("", ".", "..") for part in value.split("/")
    )


def read_json_bytes(data: bytes, label: str) -> dict[str, Any]:
    if not data or len(data) > MAX_JSON_BYTES:
        raise ValueError(f"{label} is empty or exceeds the JSON size limit")
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid JSON in {label}: {error}") from error
    if not isinstance(value, dict):
        raise ValueError(f"{label} must contain a JSON object")
    return value


def read_archive_json(archive: zipfile.ZipFile, name: str, required: bool = False) -> dict[str, Any] | None:
    try:
        info = archive.getinfo(name)
    except KeyError:
        if required:
            raise ValueError(f"required archive metadata missing: {name}")
        return None
    return read_json_bytes(archive.read(info), name)


def parse_class_references(data: bytes, label: str) -> dict[str, Any]:
    """Read a class file's constant pool and source class name, without execution."""
    if len(data) < 10 or len(data) > MAX_CLASS_BYTES or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError(f"invalid or out-of-range Java class file: {label}")
    cursor = 8

    def take(size: int) -> bytes:
        nonlocal cursor
        if size < 0 or cursor + size > len(data):
            raise ValueError(f"truncated class file: {label}")
        result = data[cursor:cursor + size]
        cursor += size
        return result

    def u1() -> int:
        return take(1)[0]

    def u2() -> int:
        return int.from_bytes(take(2), "big")

    def u4() -> int:
        return int.from_bytes(take(4), "big")

    cp_count = u2()
    if cp_count < 2 or cp_count > 65535:
        raise ValueError(f"invalid constant-pool count: {label}")
    cp: list[Any] = [None] * cp_count
    index = 1
    while index < cp_count:
        tag = u1()
        if tag == 1:
            raw = take(u2())
            cp[index] = ("utf8", raw.decode("utf-8", errors="replace"))
        elif tag in (3, 4):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag == 7:
            cp[index] = ("class", u2())
        elif tag == 8:
            cp[index] = ("string", u2())
        elif tag in (9, 10, 11):
            cp[index] = ("member_ref", tag, u2(), u2())
        elif tag == 12:
            cp[index] = ("name_type", u2(), u2())
        elif tag == 15:
            take(3)
        elif tag == 16:
            cp[index] = ("method_type", u2())
        elif tag in (17, 18):
            take(4)
        elif tag in (19, 20):
            take(2)
        else:
            raise ValueError(f"unsupported constant-pool tag {tag}: {label}")
        index += 1

    def utf8(cp_index: int) -> str:
        if not 0 < cp_index < len(cp):
            raise ValueError(f"constant-pool UTF-8 index out of range: {label}")
        entry = cp[cp_index]
        if not isinstance(entry, tuple) or entry[0] != "utf8":
            raise ValueError(f"constant-pool entry is not UTF-8: {label}")
        return entry[1]

    def class_internal(cp_index: int) -> str:
        if not 0 < cp_index < len(cp):
            raise ValueError(f"constant-pool class index out of range: {label}")
        entry = cp[cp_index]
        if not isinstance(entry, tuple) or entry[0] != "class":
            raise ValueError(f"constant-pool entry is not Class: {label}")
        return utf8(entry[1])

    # The class header follows the constant pool. Only its identity is needed here.
    _access_flags = u2()
    this_class_index = u2()
    _super_class_index = u2()
    source_class = class_internal(this_class_index)

    class_refs: set[str] = set()
    strings = [entry[1] for entry in cp if isinstance(entry, tuple) and entry[0] == "utf8"]
    for entry in cp:
        if isinstance(entry, tuple) and entry[0] == "class":
            raw = utf8(entry[1])
            class_refs.update(MC_CLASS_PATTERN.findall(raw))
    # Descriptors, generic signatures, and string-based Mixin targets all live in UTF-8 constants.
    for value in strings:
        class_refs.update(MC_CLASS_PATTERN.findall(value))

    member_refs: list[dict[str, str]] = []
    tag_kinds = {9: "field", 10: "method", 11: "interface_method"}
    for entry in cp:
        if not isinstance(entry, tuple) or entry[0] != "member_ref":
            continue
        _, tag, owner_index, name_type_index = entry
        owner = class_internal(owner_index)
        name_type = cp[name_type_index] if 0 < name_type_index < len(cp) else None
        if not isinstance(name_type, tuple) or name_type[0] != "name_type":
            raise ValueError(f"invalid member name-and-type constant: {label}")
        member_refs.append({
            "owner_internal": owner,
            "name": utf8(name_type[1]),
            "descriptor": utf8(name_type[2]),
            "kind": tag_kinds[tag],
        })
        class_refs.update(MC_CLASS_PATTERN.findall(utf8(name_type[2])))
        if owner.startswith(MC_PREFIX):
            class_refs.add(owner)

    return {
        "source_class_internal": source_class,
        "class_refs": sorted(value for value in class_refs if value.startswith(MC_PREFIX)),
        "member_refs": [row for row in member_refs if row["owner_internal"].startswith(MC_PREFIX)],
        "utf8_constants": strings,
    }


def _module_id(metadata: dict[str, Any], fallback: str) -> str:
    value = metadata.get("id")
    if isinstance(value, str) and re.fullmatch(r"[a-z][a-z0-9_.-]{0,63}", value):
        return value
    return fallback


def _declared_mixin_paths(metadata: dict[str, Any], names: set[str]) -> set[str]:
    result: set[str] = set()
    declared = metadata.get("mixins", [])
    if isinstance(declared, str):
        declared = [declared]
    if isinstance(declared, list):
        for item in declared:
            if isinstance(item, str):
                result.add(item)
            elif isinstance(item, dict) and isinstance(item.get("config"), str):
                result.add(item["config"])
    result.update(name for name in names if name.endswith((".mixins.json", ".mixin.json")))
    return result


def _mixin_class_paths(config: dict[str, Any]) -> set[str]:
    package = config.get("package", "")
    prefix = package.replace(".", "/").strip("/")
    result: set[str] = set()
    for group in ("mixins", "client", "server"):
        values = config.get(group, [])
        if not isinstance(values, list):
            continue
        for value in values:
            if not isinstance(value, str) or not value or value.startswith(("/", "\\")):
                continue
            leaf = value.replace(".", "/")
            path = f"{prefix}/{leaf}.class" if prefix else f"{leaf}.class"
            if safe_archive_path(path):
                result.add(path)
    return result


def _scan_access_widener(data: bytes, module_id: str, path: str) -> list[dict[str, Any]]:
    if len(data) > MAX_JSON_BYTES:
        raise ValueError(f"access widener exceeds size limit: {path}")
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise ValueError(f"access widener is not UTF-8: {path}") from error
    result = []
    header_seen = False
    for line_no, raw in enumerate(lines, 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if parts[0] == "accessWidener":
            if line_no != 1 or len(parts) < 3:
                raise ValueError(f"invalid access widener header: {path}:{line_no}")
            header_seen = True
            continue
        if not header_seen or len(parts) < 3:
            raise ValueError(f"invalid access widener entry: {path}:{line_no}")
        access_kind, target_kind, target_class = parts[:3]
        if target_kind not in ("class", "method", "field") or not target_class.startswith(MC_PREFIX):
            continue
        row: dict[str, Any] = {
            "module": module_id, "path": path, "line": line_no,
            "access_kind": access_kind, "target_kind": target_kind,
            "target_class_internal": target_class,
        }
        if target_kind in ("method", "field") and len(parts) >= 5:
            row["name"] = parts[3]
            row["descriptor"] = parts[4]
        result.append(row)
    if not header_seen:
        raise ValueError(f"access widener header missing: {path}")
    return result


def scan_fabric_api_jar(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.stat().st_size <= 0 or path.stat().st_size > MAX_ROOT_BYTES:
        raise ValueError("Fabric API aggregate JAR is missing, empty, or outside the size limit")
    artifact_bytes = path.read_bytes()
    artifact_sha = sha256(artifact_bytes)
    class_counter = Counter()
    member_counter = Counter()
    class_mixin_flags: set[tuple[str, str]] = set()
    modules: dict[str, dict[str, Any]] = {}
    mixin_configs: list[dict[str, Any]] = []
    access_wideners: list[dict[str, Any]] = []
    total_nested_bytes = 0
    class_count = 0
    try:
        with zipfile.ZipFile(io.BytesIO(artifact_bytes), "r") as root:
            root_names = {item.filename for item in root.infolist()}
            root_metadata = read_archive_json(root, "fabric.mod.json", required=True)
            assert root_metadata is not None
            declarations = root_metadata.get("jars", [])
            if not isinstance(declarations, list) or not declarations or len(declarations) > MAX_NESTED_JARS:
                raise ValueError("Fabric API aggregate has an empty or excessive nested module list")
            seen_paths: set[str] = set()
            for declaration in declarations:
                nested_path = declaration.get("file") if isinstance(declaration, dict) else None
                if not isinstance(nested_path, str) or not safe_archive_path(nested_path):
                    raise ValueError("Fabric API contains an unsafe or malformed nested JAR declaration")
                if nested_path in seen_paths:
                    raise ValueError(f"duplicate nested JAR declaration: {nested_path}")
                seen_paths.add(nested_path)
                if nested_path not in root_names:
                    raise ValueError(f"declared Fabric API module is missing: {nested_path}")
                info = root.getinfo(nested_path)
                if info.file_size <= 0 or info.file_size > MAX_NESTED_BYTES:
                    raise ValueError(f"nested Fabric API module is outside size limits: {nested_path}")
                total_nested_bytes += info.file_size
                if total_nested_bytes > MAX_TOTAL_NESTED_BYTES:
                    raise ValueError("nested Fabric API module total exceeds the safety limit")
                raw = root.read(info)
                try:
                    module_archive = zipfile.ZipFile(io.BytesIO(raw), "r")
                except zipfile.BadZipFile as error:
                    raise ValueError(f"declared Fabric API module is not a JAR: {nested_path}") from error
                with module_archive:
                    infos = module_archive.infolist()
                    if len(infos) > MAX_ENTRIES:
                        raise ValueError(f"too many entries in Fabric API module: {nested_path}")
                    names = {item.filename for item in infos}
                    metadata = read_archive_json(module_archive, "fabric.mod.json") or {}
                    module_id = _module_id(metadata, Path(nested_path).name.removesuffix(".jar"))
                    module_summary = modules.setdefault(module_id, {
                        "id": module_id, "path": nested_path, "class_count": 0,
                        "minecraft_class_reference_count": 0, "minecraft_member_reference_count": 0,
                        "mixin_config_count": 0, "access_widener_count": 0,
                        "sha256": sha256(raw),
                    })
                    aw_name = metadata.get("accessWidener")
                    for name in names:
                        if not (name.endswith(".accesswidener") or name == aw_name):
                            continue
                        if not safe_archive_path(name):
                            raise ValueError(f"unsafe access widener path: {name}")
                        rows = _scan_access_widener(module_archive.read(name), module_id, name)
                        access_wideners.extend(rows)
                        module_summary["access_widener_count"] += len(rows)

                    config_names = _declared_mixin_paths(metadata, names)
                    module_mixin_paths: set[str] = set()
                    for config_name in sorted(config_names):
                        if config_name not in names:
                            continue
                        if not safe_archive_path(config_name):
                            raise ValueError(f"unsafe Mixin config path: {config_name}")
                        config = read_json_bytes(module_archive.read(config_name), config_name)
                        declared_classes = _mixin_class_paths(config)
                        module_mixin_paths.update(declared_classes)
                        mixin_configs.append({
                            "module": module_id, "path": config_name,
                            "package": config.get("package", ""),
                            "mixin_count": len(declared_classes),
                        })
                        module_summary["mixin_config_count"] += 1

                    for item in infos:
                        name = item.filename
                        if item.is_dir() or not name.endswith(".class") or not name.startswith(FABRIC_PREFIX):
                            continue
                        if item.file_size <= 0 or item.file_size > MAX_CLASS_BYTES:
                            raise ValueError(f"Fabric API implementation class outside size limit: {module_id}:{name}")
                        class_count += 1
                        if class_count > MAX_CLASSES:
                            raise ValueError("Fabric API implementation scan exceeded the class-file budget")
                        parsed = parse_class_references(module_archive.read(item), f"{module_id}!/{name}")
                        source_class = parsed["source_class_internal"]
                        if source_class != name[:-6]:
                            # Keep scanning but do not silently claim a path/class identity match.
                            source_class = name[:-6]
                        is_mixin = name in module_mixin_paths or "/mixin/" in name or "/mixins/" in name
                        if is_mixin:
                            class_mixin_flags.add((module_id, source_class))
                        module_summary["class_count"] += 1
                        for target in parsed["class_refs"]:
                            class_counter[(module_id, source_class, target, bool(is_mixin))] += 1
                            module_summary["minecraft_class_reference_count"] += 1
                        for row in parsed["member_refs"]:
                            key = (module_id, source_class, row["owner_internal"], row["name"],
                                   row["descriptor"], row["kind"], bool(is_mixin))
                            member_counter[key] += 1
                            module_summary["minecraft_member_reference_count"] += 1
    except zipfile.BadZipFile as error:
        raise ValueError(f"invalid Fabric API ZIP/JAR: {error}") from error

    return {
        "artifact_sha256": artifact_sha,
        "root_version": str(root_metadata.get("version", "unknown")),
        "implementation_class_count": class_count,
        "modules": sorted(modules.values(), key=lambda row: row["id"]),
        "class_refs": class_counter,
        "member_refs": member_counter,
        "mixin_configs": mixin_configs,
        "access_wideners": access_wideners,
        "class_mixin_flags": class_mixin_flags,
        "bytecode_executed": False,
    }


def target_status(class_internal: str, class_index: dict[str, dict[str, Any]]) -> str:
    return "present_in_public_abi_catalog" if class_internal in class_index else "not_in_public_abi_catalog"


def native_candidates(module_id: str, targets: list[dict[str, Any]], source_is_mixin: bool = False,
                      is_access_widener: bool = False) -> list[dict[str, Any]]:
    module_folded = module_id.casefold()
    found = []
    for target in targets:
        features = set(target.get("match_source_features", []))
        tokens = target.get("fabric_module_tokens", [])
        matches = isinstance(tokens, list) and any(
            isinstance(token, str) and token.casefold() in module_folded for token in tokens
        )
        feature_match = (source_is_mixin and "mixin" in features) or (is_access_widener and "access_widener" in features)
        if matches or feature_match:
            found.append(target)
    return sorted(found, key=lambda row: row["id"])


def _mapping_id(key: tuple[Any, ...]) -> str:
    payload = json.dumps(key, ensure_ascii=True, separators=(",", ":"), sort_keys=False).encode("utf-8")
    return "HZN-JAVA-" + hashlib.sha1(payload).hexdigest()[:12].upper()


def build_target_map(
    fabric_api_jar: Path,
    minecraft_abi: dict[str, Any],
    fabric_api_abi: dict[str, Any],
    module_manifest: dict[str, Any],
    shim_surface: dict[str, Any],
    native_targets_document: dict[str, Any],
) -> dict[str, Any]:
    expected_mc = minecraft_abi.get("target_minecraft")
    if expected_mc != "26.3" or fabric_api_abi.get("target_minecraft") != expected_mc:
        raise ValueError("Minecraft and Fabric API ABI catalogs must both target Minecraft 26.3")
    if module_manifest.get("target", {}).get("minecraft") != expected_mc:
        raise ValueError("module manifest does not target the same Minecraft version")
    artifact = scan_fabric_api_jar(fabric_api_jar)
    expected_hash = fabric_api_abi.get("aggregate_sha256")
    if expected_hash and artifact["artifact_sha256"] != expected_hash:
        raise ValueError("Fabric API JAR SHA-256 does not match the generated ABI catalog")
    expected_version = str(fabric_api_abi.get("fabric_api_version", "unknown"))
    if expected_version != "unknown" and artifact["root_version"] != expected_version:
        raise ValueError("Fabric API aggregate version does not match the generated ABI catalog")

    mc_classes = minecraft_abi.get("classes", [])
    mc_class_index = {
        row.get("internal_name") or str(row.get("name", "")).replace(".", "/"): row
        for row in mc_classes if isinstance(row, dict)
    }
    target_doc_version = native_targets_document.get("target_minecraft")
    if target_doc_version != expected_mc:
        raise ValueError("native target marker catalog does not target Minecraft 26.3")
    native_targets = native_targets_document.get("targets", [])
    if not isinstance(native_targets, list):
        raise ValueError("native target marker catalog has no targets array")
    target_ids = [row.get("id") for row in native_targets if isinstance(row, dict)]
    if len(target_ids) != len(set(target_ids)) or any(not isinstance(value, str) for value in target_ids):
        raise ValueError("native target marker IDs must be unique strings")
    infrastructure = native_targets_document.get("infrastructure_marker_ids", [])
    if not isinstance(infrastructure, list) or any(value not in target_ids for value in infrastructure):
        raise ValueError("infrastructure marker IDs must refer to declared native targets")
    manifest_modules = {
        row.get("id"): row for row in module_manifest.get("modules", []) if isinstance(row, dict)
    }

    references: list[dict[str, Any]] = []
    class_ref_counts: Counter[str] = Counter()
    member_ref_counts: Counter[str] = Counter()
    candidate_count = 0
    for (module_id, source_class, target_class, source_is_mixin), count in sorted(artifact["class_refs"].items()):
        candidates = native_candidates(module_id, native_targets, source_is_mixin=source_is_mixin)
        marker_ids = sorted({row["id"] for row in candidates} | set(infrastructure))
        catalog_status = target_status(target_class, mc_class_index)
        references.append({
            "mapping_id": _mapping_id(("class", module_id, source_class, target_class, source_is_mixin)),
            "source_module": module_id,
            "source_class": source_class.replace("/", "."),
            "source_is_mixin": source_is_mixin,
            "target_kind": "class",
            "target_class": target_class.replace("/", "."),
            "target_class_internal": target_class,
            "target_member": None,
            "descriptor": None,
            "reference_count": count,
            "minecraft_class_abi_status": catalog_status,
            "minecraft_member_abi_status": "not_applicable",
            "native_candidates": [
                {"id": row["id"], "module_id": row["module_id"], "status": manifest_modules.get(row["module_id"], {}).get("status", "unknown"),
                 "source_path": row["repository_source_path"], "horizon_path": row["horizon_path"],
                 "exact_native_binding_verified": False}
                for row in candidates
            ],
            "infrastructure_marker_ids": infrastructure,
            "marker_ids": marker_ids,
            "mapping_status": "native_module_candidate" if candidates else "no_native_module_candidate",
        })
        class_ref_counts[catalog_status] += 1
        candidate_count += bool(candidates)

    public_members: dict[str, set[tuple[str, str, str]]] = {}
    for internal, row in mc_class_index.items():
        refs = set()
        for kind in ("fields", "methods"):
            for member in row.get(kind, []):
                if isinstance(member, dict) and isinstance(member.get("name"), str) and isinstance(member.get("descriptor"), str):
                    normalized_kind = "field" if kind == "fields" else "method"
                    refs.add((normalized_kind, member["name"], member["descriptor"]))
        public_members[internal] = refs

    for (module_id, source_class, owner, name, descriptor, kind, source_is_mixin), count in sorted(artifact["member_refs"].items()):
        candidates = native_candidates(module_id, native_targets, source_is_mixin=source_is_mixin)
        marker_ids = sorted({row["id"] for row in candidates} | set(infrastructure))
        class_status = target_status(owner, mc_class_index)
        normalized_kind = "field" if kind == "field" else "method"
        member_status = (
            "present_in_public_abi_catalog"
            if (normalized_kind, name, descriptor) in public_members.get(owner, set())
            else "not_in_public_abi_catalog"
        )
        references.append({
            "mapping_id": _mapping_id(("member", module_id, source_class, owner, name, descriptor, kind, source_is_mixin)),
            "source_module": module_id,
            "source_class": source_class.replace("/", "."),
            "source_is_mixin": source_is_mixin,
            "target_kind": kind,
            "target_class": owner.replace("/", "."),
            "target_class_internal": owner,
            "target_member": name,
            "descriptor": descriptor,
            "reference_count": count,
            "minecraft_class_abi_status": class_status,
            "minecraft_member_abi_status": member_status if class_status == "present_in_public_abi_catalog" else "class_not_in_public_abi_catalog",
            "native_candidates": [
                {"id": row["id"], "module_id": row["module_id"], "status": manifest_modules.get(row["module_id"], {}).get("status", "unknown"),
                 "source_path": row["repository_source_path"], "horizon_path": row["horizon_path"],
                 "exact_native_binding_verified": False}
                for row in candidates
            ],
            "infrastructure_marker_ids": infrastructure,
            "marker_ids": marker_ids,
            "mapping_status": "native_module_candidate" if candidates else "no_native_module_candidate",
        })
        member_ref_counts[member_status] += 1
        candidate_count += bool(candidates)

    for row in artifact["access_wideners"]:
        candidates = native_candidates(row["module"], native_targets, is_access_widener=True)
        target_class = row["target_class_internal"]
        class_status = target_status(target_class, mc_class_index)
        member_status = "not_applicable"
        if row["target_kind"] in ("field", "method"):
            kind = row["target_kind"]
            member_status = (
                "present_in_public_abi_catalog"
                if (kind, row.get("name"), row.get("descriptor")) in public_members.get(target_class, set())
                else "not_in_public_abi_catalog"
            )
        marker_ids = sorted({target["id"] for target in candidates} | set(infrastructure))
        references.append({
            "mapping_id": _mapping_id(("access_widener", row["module"], row["path"], row["line"], target_class,
                                       row.get("name"), row.get("descriptor"))),
            "source_module": row["module"],
            "source_class": None,
            "source_is_mixin": True,
            "target_kind": "access_widener_" + row["target_kind"],
            "target_class": target_class.replace("/", "."),
            "target_class_internal": target_class,
            "target_member": row.get("name"),
            "descriptor": row.get("descriptor"),
            "source_metadata": {"path": row["path"], "line": row["line"], "access_kind": row["access_kind"]},
            "reference_count": 1,
            "minecraft_class_abi_status": class_status,
            "minecraft_member_abi_status": member_status if class_status == "present_in_public_abi_catalog" else "class_not_in_public_abi_catalog",
            "native_candidates": [
                {"id": target["id"], "module_id": target["module_id"], "status": manifest_modules.get(target["module_id"], {}).get("status", "unknown"),
                 "source_path": target["repository_source_path"], "horizon_path": target["horizon_path"],
                 "exact_native_binding_verified": False}
                for target in candidates
            ],
            "infrastructure_marker_ids": infrastructure,
            "marker_ids": marker_ids,
            "mapping_status": "native_module_candidate" if candidates else "no_native_module_candidate",
        })
        candidate_count += bool(candidates)

    references.sort(key=lambda row: row["mapping_id"])
    public_api_classes = fabric_api_abi.get("classes", [])
    public_class_modules = Counter(
        str(row.get("module", "unknown")) for row in public_api_classes if isinstance(row, dict)
    )
    public_members_count = sum(
        len(row.get("fields", [])) + len(row.get("methods", []))
        for row in public_api_classes if isinstance(row, dict)
    )
    no_candidate = sum(row["mapping_status"] == "no_native_module_candidate" for row in references)
    class_abi_hit = sum(row["minecraft_class_abi_status"] == "present_in_public_abi_catalog" for row in references)
    member_abi_hit = sum(row["minecraft_member_abi_status"] == "present_in_public_abi_catalog" for row in references)

    target_map = {
        "schema_version": 1,
        "target_minecraft": expected_mc,
        "fabric_api_version": expected_version,
        "sources": {
            "fabric_api_jar_sha256": artifact["artifact_sha256"],
            "minecraft_client_sha1": minecraft_abi.get("client_sha1"),
            "minecraft_client_sha256": minecraft_abi.get("client_sha256"),
            "fabric_api_abi_sha256": fabric_api_abi.get("aggregate_sha256"),
            "module_manifest_profile": module_manifest.get("profile"),
            "shim_surface_schema_version": shim_surface.get("schema_version"),
        },
        "method": {
            "constant_pool_scanned": True,
            "mixins_and_access_wideners_inventoried": True,
            "bytecode_executed": False,
            "semantics_note": "A native-module candidate is a routing hint only. It does not prove that the native implementation matches Java behavior.",
            "abi_note": "Not found in the public ABI catalog does not prove a target is absent; private/package members and mapping differences may be involved.",
        },
        "summary": {
            "fabric_api_public_class_count": len(public_api_classes),
            "fabric_api_public_member_count": public_members_count,
            "fabric_api_public_module_count": len(fabric_api_abi.get("modules", [])),
            "fabric_api_implementation_class_count_scanned": artifact["implementation_class_count"],
            "fabric_api_implementation_module_count_scanned": len(artifact["modules"]),
            "minecraft_class_reference_records": sum(row["target_kind"] == "class" for row in references),
            "minecraft_member_reference_records": sum(row["target_kind"] in ("field", "method", "interface_method") for row in references),
            "access_widener_reference_records": sum(str(row["target_kind"]).startswith("access_widener_") for row in references),
            "mixin_config_count": len(artifact["mixin_configs"]),
            "access_widener_file_reference_count": len(artifact["access_wideners"]),
            "minecraft_class_abi_hits": class_abi_hit,
            "minecraft_member_abi_hits": member_abi_hit,
            "references_with_native_module_candidates": len(references) - no_candidate,
            "references_without_native_module_candidates": no_candidate,
            "unique_native_candidate_reference_links": candidate_count,
        },
        "fabric_api_modules": artifact["modules"],
        "public_api_classes_by_module": dict(sorted(public_class_modules.items())),
        "mixin_configs": artifact["mixin_configs"],
        "access_wideners": artifact["access_wideners"],
        "native_targets": [
            {"id": row["id"], "module_id": row["module_id"], "status": manifest_modules.get(row["module_id"], {}).get("status", "unknown"),
             "repository_source_path": row["repository_source_path"], "horizon_path": row["horizon_path"],
             "marker_kind": row.get("marker_kind", "module_candidate"),
             "description": row.get("description", "")}
            for row in native_targets
        ],
        "references": references,
        "notes": [
            "Stable HZN-JAVA-* IDs identify individual static target-map rows; they are not live execution traces.",
            "HZN-FABRIC-* IDs identify native module integration points exposed by the native capability report.",
            "Exact Java-to-Rust semantic bindings remain unverified until implemented adapters and runtime tests prove them.",
        ],
        "bytecode_executed": False,
    }
    return target_map


def write_markdown_summary(report: dict[str, Any], path: Path) -> None:
    s = report["summary"]
    lines = [
        "## Fabric API → Minecraft Java → Horizon target map",
        "",
        f"- Target: Minecraft {report['target_minecraft']} / Fabric API {report['fabric_api_version']}",
        f"- Fabric API public ABI: {s['fabric_api_public_class_count']} classes, {s['fabric_api_public_member_count']} members, {s['fabric_api_public_module_count']} modules",
        f"- Fabric implementation classes statically scanned: {s['fabric_api_implementation_class_count_scanned']}",
        f"- Minecraft target records: {s['minecraft_class_reference_records']} classes, {s['minecraft_member_reference_records']} members",
        f"- Mixin configs: {s['mixin_config_count']}; access widener entries: {s['access_widener_file_reference_count']}",
        f"- References with native-module candidates: {s['references_with_native_module_candidates']}",
        f"- References without native-module candidates: {s['references_without_native_module_candidates']}",
        "",
        "Candidate links are hints, not proof of compatibility. Bytecode was inspected statically and was not executed. The full JSON artifact contains stable mapping IDs, target descriptors, module statuses, source paths, and marker IDs.",
        "",
    ]
    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    path.write_text(existing + "\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fabric-api-jar", required=True, type=Path)
    parser.add_argument("--minecraft-abi", required=True, type=Path)
    parser.add_argument("--fabric-api-abi", required=True, type=Path)
    parser.add_argument("--module-manifest", required=True, type=Path)
    parser.add_argument("--shim-surface", required=True, type=Path)
    parser.add_argument("--native-targets", required=True, type=Path)
    parser.add_argument("--capability-source", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    try:
        documents = {
            "minecraft": json.loads(args.minecraft_abi.read_text(encoding="utf-8")),
            "fabric": json.loads(args.fabric_api_abi.read_text(encoding="utf-8")),
            "manifest": json.loads(args.module_manifest.read_text(encoding="utf-8")),
            "shim": json.loads(args.shim_surface.read_text(encoding="utf-8")),
            "targets": json.loads(args.native_targets.read_text(encoding="utf-8")),
        }
        if args.capability_source:
            source = args.capability_source.read_text(encoding="utf-8")
            missing = [row.get("id") for row in documents["targets"].get("targets", [])
                       if isinstance(row, dict) and row.get("id") not in source]
            if missing:
                raise ValueError("native capability report is missing marker IDs: " + ", ".join(missing))
        report = build_target_map(
            args.fabric_api_jar, documents["minecraft"], documents["fabric"],
            documents["manifest"], documents["shim"], documents["targets"],
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        if args.summary:
            write_markdown_summary(report, args.summary)
        s = report["summary"]
        print(
            "Fabric target map generated: "
            f"{s['fabric_api_implementation_class_count_scanned']} implementation classes; "
            f"{s['minecraft_class_reference_records']} class refs; "
            f"{s['minecraft_member_reference_records']} member refs; "
            f"{s['references_with_native_module_candidates']} refs have native candidates; "
            f"{s['references_without_native_module_candidates']} need mapping."
        )
        return 0
    except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"Fabric target map generation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
