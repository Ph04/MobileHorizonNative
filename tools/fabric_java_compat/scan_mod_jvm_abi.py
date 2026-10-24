#!/usr/bin/env python3
"""Inventory referenced JVM classes, fields and methods in an original Fabric JAR.

The scanner reads ZIP and class-file metadata only. It never loads or executes mod
bytecode. Symbols missing from the public ABI catalog may exist as private or
package-private members; they must not be labelled absent from the actual game
without inspecting the corresponding official class bytecode.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import struct
import zipfile
from collections import Counter
from pathlib import Path, PurePosixPath
from typing import Any

MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
MAX_CLASS_BYTES = 16 * 1024 * 1024
MAX_METADATA_BYTES = 1024 * 1024
MAX_ENTRIES = 100_000
MAX_NESTED_COUNT = 64
MAX_NESTED_BYTES = 32 * 1024 * 1024
MAX_TOTAL_NESTED_BYTES = 128 * 1024 * 1024
DESCRIPTOR_CLASS = re.compile(r"L([^;:<]+)(?:;|<|:)")
IMPORTANT_PREFIXES = (
    "net/minecraft/", "net/fabricmc/", "org/spongepowered/",
    "com/google/common/", "org/apache/logging/",
)

def safe_path(value: str) -> bool:
    if not value or value.startswith(("/", "\\")) or "\\" in value or "\x00" in value:
        return False
    return all(part not in ("", ".", "..") for part in value.split("/"))

def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def parse_class_file(data: bytes, label: str) -> dict[str, Any]:
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError(f"invalid Java class file: {label}")
    minor, major = struct.unpack_from(">HH", data, 4)
    if major < 45 or major > 100:
        raise ValueError(f"unsupported class-file major version {major}: {label}")
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

    cp_count = u2()
    if cp_count < 2:
        raise ValueError(f"invalid constant pool: {label}")
    cp: list[Any] = [None] * cp_count
    index = 1
    while index < cp_count:
        tag = u1()
        if tag == 1:
            length = u2()
            cp[index] = ("utf8", take(length).decode("utf-8", errors="replace"))
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
            cp[index] = ("method_handle", u1(), u2())
        elif tag == 16:
            cp[index] = ("method_type", u2())
        elif tag in (17, 18):
            cp[index] = ("dynamic", tag, u2(), u2())
        elif tag in (19, 20):
            cp[index] = ("one_index", tag, u2())
        else:
            raise ValueError(f"unknown constant-pool tag {tag} in {label}")
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

    access_flags = u2()
    this_class_index = u2()
    super_class_index = u2()
    interface_count = u2()
    interfaces = [class_internal(u2()) for _ in range(interface_count)]
    this_class = class_internal(this_class_index)
    super_class = class_internal(super_class_index) if super_class_index else None

    classes: set[str] = set()
    for entry in cp:
        if isinstance(entry, tuple) and entry[0] == "class":
            raw = utf8(entry[1])
            if raw.startswith("["):
                classes.update(DESCRIPTOR_CLASS.findall(raw))
            else:
                classes.add(raw)
        elif isinstance(entry, tuple) and entry[0] in ("method_type",):
            classes.update(DESCRIPTOR_CLASS.findall(utf8(entry[1])))
        elif isinstance(entry, tuple) and entry[0] in ("name_type",):
            classes.update(DESCRIPTOR_CLASS.findall(utf8(entry[2])))

    members: Counter[tuple[str, str, str, str]] = Counter()
    kinds = {9: "field", 10: "method", 11: "interface_method"}
    for entry in cp:
        if not isinstance(entry, tuple) or entry[0] != "member_ref":
            continue
        _, tag, owner_index, name_type_index = entry
        owner = class_internal(owner_index)
        nat = cp[name_type_index] if 0 < name_type_index < len(cp) else None
        if not isinstance(nat, tuple) or nat[0] != "name_type":
            raise ValueError(f"invalid NameAndType constant: {label}")
        name = utf8(nat[1])
        descriptor = utf8(nat[2])
        members[(owner, name, descriptor, kinds[tag])] += 1
        classes.update(DESCRIPTOR_CLASS.findall(descriptor))

    # Also include the direct hierarchy even if its type was only present in header indices.
    classes.add(this_class)
    if super_class:
        classes.add(super_class)
    classes.update(interfaces)
    public_members = []
    for (owner, name, descriptor, kind), count in sorted(members.items()):
        public_members.append({
            "owner": owner.replace("/", "."),
            "owner_internal": owner,
            "name": name,
            "descriptor": descriptor,
            "kind": kind,
            "constant_pool_reference_count": count,
        })
    return {
        "class_name": this_class.replace("/", "."),
        "class_internal_name": this_class,
        "class_major": major,
        "class_minor": minor,
        "access_flags": access_flags,
        "class_references_internal": sorted(classes),
        "member_references": public_members,
    }

def load_abi(path: Path | None) -> dict[str, set[tuple[str, str, str]]]:
    if path is None:
        return {}
    document = json.loads(path.read_text(encoding="utf-8"))
    rows = document.get("classes", [])
    result: dict[str, set[tuple[str, str, str]]] = {}
    for row in rows if isinstance(rows, list) else []:
        if not isinstance(row, dict):
            continue
        internal = row.get("internal_name")
        if not isinstance(internal, str):
            name = row.get("name")
            if not isinstance(name, str):
                continue
            internal = name.replace(".", "/")
        items: set[tuple[str, str, str]] = set()
        for key, default_kind in (("fields", "field"), ("methods", "method")):
            for member in row.get(key, []):
                if isinstance(member, dict):
                    kind = str(member.get("kind", default_kind))
                    name = member.get("name")
                    descriptor = member.get("descriptor")
                    if isinstance(name, str) and isinstance(descriptor, str):
                        items.add((kind, name, descriptor))
                        # Interface method references resolve against method members too.
                        if default_kind == "method":
                            items.add(("interface_method", name, descriptor))
        result[internal] = items
    return result

def scan_mod(mod_jar: Path, minecraft_abi: Path | None = None, fabric_abi: Path | None = None) -> dict[str, Any]:
    if not mod_jar.is_file() or mod_jar.stat().st_size <= 0 or mod_jar.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError("mod JAR is missing, empty, or larger than 128 MiB")
    mc_catalog = load_abi(minecraft_abi)
    fabric_catalog = load_abi(fabric_abi)
    class_refs: set[str] = set()
    class_versions: list[int] = []
    member_refs: Counter[tuple[str, str, str, str]] = Counter()
    class_entries = 0
    nested_count = 0
    nested_total = 0
    nested_labels: list[str] = []
    warnings: list[str] = []
    mod_metadata: dict[str, Any] = {}

    def scan(archive: zipfile.ZipFile, label: str, depth: int = 0) -> None:
        nonlocal class_entries, nested_count, nested_total, mod_metadata
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ValueError(f"{label} has too many ZIP entries")
        names = {item.filename for item in infos}
        metadata: dict[str, Any] = {}
        if "fabric.mod.json" in names:
            info = archive.getinfo("fabric.mod.json")
            if not 0 < info.file_size <= MAX_METADATA_BYTES:
                raise ValueError(f"invalid fabric.mod.json size in {label}")
            value = json.loads(archive.read(info).decode("utf-8"))
            if not isinstance(value, dict):
                raise ValueError(f"fabric.mod.json must be an object in {label}")
            metadata = value
            if depth == 0:
                mod_metadata = metadata

        for info in infos:
            if info.is_dir():
                continue
            name = info.filename
            if not safe_path(name):
                raise ValueError(f"unsafe ZIP path in {label}: {name!r}")
            if not name.endswith(".class"):
                continue
            if not 0 < info.file_size <= MAX_CLASS_BYTES:
                raise ValueError(f"class outside size limits: {label}!/{name}")
            class_entries += 1
            row = parse_class_file(archive.read(info), f"{label}!/{name}")
            class_versions.append(row["class_major"])
            class_refs.update(row["class_references_internal"])
            for member in row["member_references"]:
                member_refs[(member["owner_internal"], member["name"], member["descriptor"], member["kind"])] += member["constant_pool_reference_count"]

        nested = metadata.get("jars", []) if isinstance(metadata, dict) else []
        if nested:
            if not isinstance(nested, list) or depth >= 8:
                raise ValueError(f"invalid or too-deep nested JAR declarations in {label}")
            for declaration in nested:
                if not isinstance(declaration, dict) or not isinstance(declaration.get("file"), str):
                    raise ValueError(f"invalid nested JAR declaration in {label}")
                nested_name = declaration["file"]
                if not safe_path(nested_name) or not nested_name.lower().endswith(".jar"):
                    raise ValueError(f"unsafe nested JAR path: {nested_name!r}")
                if nested_name not in names:
                    raise ValueError(f"declared nested JAR missing: {label}!/{nested_name}")
                nested_info = archive.getinfo(nested_name)
                if nested_info.file_size <= 0 or nested_info.file_size > MAX_NESTED_BYTES:
                    raise ValueError(f"nested JAR outside size limits: {label}!/{nested_name}")
                nested_count += 1
                nested_total += nested_info.file_size
                if nested_count > MAX_NESTED_COUNT or nested_total > MAX_TOTAL_NESTED_BYTES:
                    raise ValueError("nested JAR safety budget exceeded")
                nested_labels.append(f"{label}!/{nested_name}")
                try:
                    with zipfile.ZipFile(io.BytesIO(archive.read(nested_info)), "r") as nested_archive:
                        scan(nested_archive, f"{label}!/{nested_name}", depth + 1)
                except zipfile.BadZipFile as error:
                    raise ValueError(f"declared nested entry is not a valid JAR: {nested_name}") from error

    with zipfile.ZipFile(mod_jar, "r") as archive:
        scan(archive, mod_jar.name)

    if not isinstance(mod_metadata.get("id"), str):
        raise ValueError("fabric.mod.json has no mod id")
    all_member_rows = [
        {"owner": owner.replace("/", "."), "owner_internal": owner, "name": name,
         "descriptor": descriptor, "kind": kind, "references": count}
        for (owner, name, descriptor, kind), count in sorted(member_refs.items())
    ]
    mc_member_refs = [row for row in all_member_rows if row["owner_internal"].startswith("net/minecraft/")]
    fabric_member_refs = [row for row in all_member_rows if row["owner_internal"].startswith("net/fabricmc/fabric/api/")]
    mc_unknown_classes = sorted(c for c in class_refs if c.startswith("net/minecraft/") and c not in mc_catalog)
    fabric_unknown_classes = sorted(c for c in class_refs if c.startswith("net/fabricmc/fabric/api/") and c not in fabric_catalog)

    def missing_public_members(rows: list[dict[str, Any]], catalog: dict[str, set[tuple[str, str, str]]]) -> list[dict[str, Any]]:
        result = []
        for row in rows:
            known = catalog.get(row["owner_internal"])
            if known is not None and (row["kind"], row["name"], row["descriptor"]) not in known:
                result.append({k: row[k] for k in ("owner", "name", "descriptor", "kind", "references")})
        return result

    mc_members_not_public = missing_public_members(mc_member_refs, mc_catalog)
    fabric_members_not_public = missing_public_members(fabric_member_refs, fabric_catalog)
    important_classes = sorted(c.replace("/", ".") for c in class_refs if c.startswith(IMPORTANT_PREFIXES))
    return {
        "schema_version": 1,
        "tool": "MobileHorizonNative mod JVM ABI reference scanner",
        "executed_mod_code": False,
        "mod": {
            "id": mod_metadata["id"],
            "version": str(mod_metadata.get("version", "unknown")),
            "sha256": sha256_file(mod_jar),
            "size_bytes": mod_jar.stat().st_size,
            "class_count_scanned": class_entries,
            "max_class_major": max(class_versions, default=0),
            "nested_jars": nested_labels,
        },
        "official_abi": {
            "minecraft_catalog_supplied": minecraft_abi is not None,
            "fabric_catalog_supplied": fabric_abi is not None,
            "minecraft_class_refs_in_mod": sorted(c.replace("/", ".") for c in class_refs if c.startswith("net/minecraft/")),
            "fabric_api_class_refs_in_mod": sorted(c.replace("/", ".") for c in class_refs if c.startswith("net/fabricmc/fabric/api/")),
            "mc_class_refs_not_in_public_abi": [c.replace("/", ".") for c in mc_unknown_classes],
            "fabric_class_refs_not_in_public_abi": [c.replace("/", ".") for c in fabric_unknown_classes],
            "mc_member_refs_not_in_public_abi": mc_members_not_public,
            "fabric_member_refs_not_in_public_abi": fabric_members_not_public,
            "note": "Not present in the public/protected ABI catalog does not prove a symbol is absent from the official JAR; it may be non-public or accessed through an access widener.",
        },
        "referenced_classes": important_classes,
        "minecraft_member_references": mc_member_refs,
        "fabric_api_member_references": fabric_member_refs,
        "other_external_member_references": [row for row in all_member_rows if not row["owner_internal"].startswith(("net/minecraft/", "net/fabricmc/fabric/api/"))],
        "bytecode_executed": False,
        "warnings": warnings,
    }

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mod", type=Path, required=True)
    parser.add_argument("--minecraft-abi", type=Path)
    parser.add_argument("--fabric-api-abi", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = scan_mod(args.mod, args.minecraft_abi, args.fabric_api_abi)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"JVM ABI inventory: {report['mod']['id']} {report['mod']['version']}; "
        f"{report['mod']['class_count_scanned']} classes; "
        f"{len(report['minecraft_member_references'])} Minecraft member refs; "
        f"{len(report['fabric_api_member_references'])} Fabric API member refs; "
        f"{len(report['official_abi']['mc_member_refs_not_in_public_abi'])} MC refs not in public ABI; "
        f"{len(report['official_abi']['fabric_member_refs_not_in_public_abi'])} Fabric refs not in public ABI."
    )
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
