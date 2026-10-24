#!/usr/bin/env python3
"""Generate a public JVM ABI catalog from the official Fabric API 26.3 JAR.

Reads class-file metadata only. It never loads or executes any Java bytecode.
"""
from __future__ import annotations
import argparse, hashlib, io, json, re, sys, zipfile
from pathlib import Path
from typing import Any

MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_NESTED_JAR_BYTES = 32 * 1024 * 1024
MAX_CLASS_BYTES = 16 * 1024 * 1024
MAX_CLASSES = 30_000
API_PREFIX = "net/fabricmc/fabric/api/"

def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def _safe_archive_path(value: str) -> bool:
    return bool(value) and not value.startswith("/") and "\\" not in value and "\x00" not in value and all(
        part not in ("", ".", "..") for part in value.split("/")
    )

def parse_class_api(data: bytes, module_id: str) -> dict[str, Any]:
    """Parse a Java class header, its names, and public/protected member descriptors."""
    if len(data) < 10 or len(data) > MAX_CLASS_BYTES:
        raise ValueError("class file size is outside the accepted range")
    cursor = 0
    def take(size: int) -> bytes:
        nonlocal cursor
        if size < 0 or cursor + size > len(data):
            raise ValueError("truncated class file")
        result = data[cursor:cursor + size]
        cursor += size
        return result
    def u1() -> int: return take(1)[0]
    def u2() -> int: return int.from_bytes(take(2), "big")
    def u4() -> int: return int.from_bytes(take(4), "big")

    if u4() != 0xCAFEBABE: raise ValueError("invalid Java class-file magic")
    minor, major, cp_count = u2(), u2(), u2()
    if cp_count < 1: raise ValueError("invalid constant-pool count")
    cp: list[Any] = [None] * cp_count
    index = 1
    while index < cp_count:
        tag = u1()
        if tag == 1: cp[index] = ("utf8", take(u2()).decode("utf-8", errors="replace"))
        elif tag in (3, 4): take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag == 7: cp[index] = ("class", u2())
        elif tag in (8, 16, 19, 20): cp[index] = ("one-index", u2())
        elif tag in (9, 10, 11, 12, 17, 18): cp[index] = ("two-index", u2(), u2())
        elif tag == 15: take(3)
        else: raise ValueError(f"unknown constant-pool tag {tag}")
        index += 1

    def utf8(cp_index: int) -> str:
        if not 0 < cp_index < len(cp): raise ValueError("constant-pool UTF-8 index is out of range")
        entry = cp[cp_index]
        if not isinstance(entry, tuple) or entry[0] != "utf8": raise ValueError("constant-pool entry is not UTF-8")
        return entry[1]
    def class_name(cp_index: int) -> str | None:
        if cp_index == 0: return None
        if not 0 < cp_index < len(cp): raise ValueError("constant-pool class index is out of range")
        entry = cp[cp_index]
        if not isinstance(entry, tuple) or entry[0] != "class": raise ValueError("constant-pool entry is not a class")
        return utf8(entry[1])

    access_flags, this_class, super_class = u2(), class_name(u2()), class_name(u2())
    if not this_class: raise ValueError("class has no internal name")
    interfaces = [class_name(u2()) for _ in range(u2())]

    def attributes(count: int) -> dict[str, str]:
        result: dict[str, str] = {}
        for _ in range(count):
            name, length = utf8(u2()), u4()
            payload = take(length)
            if name == "Signature" and length == 2:
                result["signature"] = utf8(int.from_bytes(payload, "big"))
        return result
    def members(count: int, kind: str) -> list[dict[str, Any]]:
        result = []
        for _ in range(count):
            flags, name, descriptor = u2(), utf8(u2()), utf8(u2())
            attrs = attributes(u2())
            if flags & (0x0001 | 0x0004):
                member = {"kind": kind, "name": name, "descriptor": descriptor, "access_flags": flags}
                if "signature" in attrs: member["generic_signature"] = attrs["signature"]
                result.append(member)
        return result

    fields, methods = members(u2(), "field"), members(u2(), "method")
    attributes(u2())
    if cursor != len(data): raise ValueError("trailing bytes after class attributes")
    if not access_flags & 0x0001: raise ValueError("non-public class is outside the public ABI catalog")
    kind = "interface" if access_flags & 0x0200 else "class"
    if access_flags & 0x2000: kind = "annotation"
    elif access_flags & 0x4000: kind = "enum"
    return {
        "name": this_class.replace("/", "."),
        "internal_name": this_class,
        "module": module_id,
        "kind": kind,
        "access_flags": access_flags,
        "major_version": major,
        "minor_version": minor,
        "super": super_class.replace("/", ".") if super_class else None,
        "interfaces": [name.replace("/", ".") for name in interfaces if name],
        "fields": fields,
        "methods": methods,
    }

def _read_mod_id(archive: zipfile.ZipFile, fallback: str) -> str:
    try:
        info = archive.getinfo("fabric.mod.json")
        if info.file_size > 1024 * 1024: return fallback
        metadata = json.loads(archive.read(info).decode("utf-8"))
        value = metadata.get("id") if isinstance(metadata, dict) else None
        return value if isinstance(value, str) and re.fullmatch(r"[a-z][a-z0-9_.-]{0,63}", value) else fallback
    except (KeyError, OSError, ValueError, UnicodeDecodeError, json.JSONDecodeError, zipfile.BadZipFile):
        return fallback

def generate_catalog(aggregate_jar: Path) -> dict[str, Any]:
    if not aggregate_jar.is_file(): raise ValueError(f"Fabric API aggregate JAR does not exist: {aggregate_jar}")
    if aggregate_jar.stat().st_size <= 0 or aggregate_jar.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError("Fabric API aggregate JAR exceeds the accepted size limit")
    digest = hashlib.sha256()
    with aggregate_jar.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""): digest.update(chunk)
    aggregate_hash = digest.hexdigest()

    classes: dict[str, dict[str, Any]] = {}
    modules: dict[str, dict[str, Any]] = {}
    warnings: list[str] = []
    total_nested_bytes = 0
    with zipfile.ZipFile(aggregate_jar, "r") as aggregate:
        names = {item.filename for item in aggregate.infolist()}
        try:
            meta = aggregate.getinfo("fabric.mod.json")
            if meta.file_size > 1024 * 1024: raise ValueError("aggregate fabric.mod.json is too large")
            metadata = json.loads(aggregate.read(meta).decode("utf-8"))
        except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError(f"aggregate JAR has invalid fabric.mod.json: {error}") from error
        version = metadata.get("version", "unknown") if isinstance(metadata, dict) else "unknown"
        declarations = metadata.get("jars", []) if isinstance(metadata, dict) else []
        if not isinstance(declarations, list) or not declarations or len(declarations) > 128:
            raise ValueError("aggregate nested module list is empty or outside the limit")

        for declaration in declarations:
            path = declaration.get("file") if isinstance(declaration, dict) else None
            if not isinstance(path, str) or not _safe_archive_path(path):
                warnings.append("unsafe or malformed nested JAR declaration ignored")
                continue
            if path not in names:
                warnings.append(f"declared nested module is missing: {path}")
                continue
            info = aggregate.getinfo(path)
            if info.is_dir() or info.file_size <= 0 or info.file_size > MAX_NESTED_JAR_BYTES:
                warnings.append(f"nested module outside accepted size limit: {path}")
                continue
            total_nested_bytes += info.file_size
            if total_nested_bytes > MAX_ARCHIVE_BYTES: raise ValueError("nested module total exceeds byte limit")
            raw = aggregate.read(info)
            try: child = zipfile.ZipFile(io.BytesIO(raw), "r")
            except zipfile.BadZipFile:
                warnings.append(f"declared nested module is not a valid ZIP/JAR: {path}")
                continue
            with child:
                module_id = _read_mod_id(child, Path(path).name.removesuffix(".jar"))
                modules[module_id] = {"id": module_id, "path": path, "archive_bytes": len(raw), "sha256": _sha256(raw)}
                for item in child.infolist():
                    name = item.filename
                    if item.is_dir() or not name.startswith(API_PREFIX) or not name.endswith(".class"): continue
                    if name.endswith(("/package-info.class", "/module-info.class")): continue
                    if item.file_size <= 0 or item.file_size > MAX_CLASS_BYTES:
                        warnings.append(f"class outside accepted size limit: {module_id}:{name}")
                        continue
                    if len(classes) >= MAX_CLASSES: raise ValueError(f"public API scan exceeded {MAX_CLASSES} classes")
                    try: parsed = parse_class_api(child.read(item), module_id)
                    except (ValueError, zipfile.BadZipFile) as error:
                        if "non-public class" not in str(error):
                            warnings.append(f"could not inspect {module_id}:{name}: {error}")
                        continue
                    old = classes.get(parsed["internal_name"])
                    if old is not None:
                        old_core = {k: old[k] for k in ("kind", "super", "interfaces", "fields", "methods")}
                        new_core = {k: parsed[k] for k in ("kind", "super", "interfaces", "fields", "methods")}
                        if old_core != new_core: raise ValueError(f"conflicting public ABI for {parsed['internal_name']}")
                        if module_id != old["module"]:
                            additionally = old.setdefault("also_in_modules", [])
                            if module_id not in additionally: additionally.append(module_id)
                        continue
                    classes[parsed["internal_name"]] = parsed

    member_count = sum(len(row["fields"]) + len(row["methods"]) for row in classes.values())
    if len(classes) < 100 or member_count < 100:
        raise ValueError(f"official API ABI scan incomplete: {len(classes)} classes, {member_count} members")
    return {
        "schema_version": 1,
        "target_minecraft": "26.3",
        "fabric_api_version": version,
        "aggregate_sha256": aggregate_hash,
        "nested_module_count": len(modules),
        "public_class_count": len(classes),
        "public_member_count": member_count,
        "modules": sorted(modules.values(), key=lambda row: row["id"]),
        "classes": [classes[key] for key in sorted(classes)],
        "warnings": warnings[:200],
        "bytecode_executed": False,
        "purpose": "exact public JVM descriptors for implementing version-specific Fabric/Minecraft adapters",
    }

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aggregate_jar", type=Path)
    parser.add_argument("output_json", type=Path)
    args = parser.parse_args()
    try:
        catalog = generate_catalog(args.aggregate_jar)
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(json.dumps(catalog, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"Fabric API ABI catalog generated: {catalog['public_class_count']} classes, "
              f"{catalog['public_member_count']} members, {catalog['nested_module_count']} modules, "
              f"sha256={catalog['aggregate_sha256']}")
        if catalog["warnings"]: print(f"ABI catalog warnings: {len(catalog['warnings'])}", file=sys.stderr)
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Fabric API ABI catalog generation failed: {error}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
