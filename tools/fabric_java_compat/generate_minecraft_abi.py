#!/usr/bin/env python3
"""Generate a public class/member descriptor catalog from the official Minecraft 26.3 client JAR.

Minecraft's client JAR is inspected as a ZIP. No classes are loaded or executed.
The version metadata and client SHA-1 must be verified by the caller before use.
"""
from __future__ import annotations
import argparse, hashlib, json, sys, zipfile
from pathlib import Path
from typing import Any

try:
    from tools.fabric_java_compat.generate_fabric_api_abi import parse_class_api
except ModuleNotFoundError:
    from generate_fabric_api_abi import parse_class_api

MAX_CLIENT_JAR_BYTES = 128 * 1024 * 1024
MAX_CLASS_BYTES = 16 * 1024 * 1024
MAX_CLASSES = 60_000
MC_PREFIX = "net/minecraft/"
REQUIRED_PUBLIC_CLASSES = (
    "net/minecraft/client/Minecraft",
    "net/minecraft/client/multiplayer/ClientLevel",
    "net/minecraft/client/player/LocalPlayer",
    "net/minecraft/world/item/ItemStack",
    "net/minecraft/world/level/Level",
    "net/minecraft/core/BlockPos",
    "net/minecraft/world/entity/Entity",
)

def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def sha1_file(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def generate_catalog(client_jar: Path, expected_sha1: str) -> dict[str, Any]:
    if not client_jar.is_file():
        raise ValueError(f"Minecraft client JAR does not exist: {client_jar}")
    if client_jar.stat().st_size <= 0 or client_jar.stat().st_size > MAX_CLIENT_JAR_BYTES:
        raise ValueError("Minecraft client JAR is outside the accepted size range")
    expected_sha1 = expected_sha1.lower()
    if len(expected_sha1) != 40 or any(ch not in "0123456789abcdef" for ch in expected_sha1):
        raise ValueError("expected official client SHA-1 is malformed")
    actual_sha1 = sha1_file(client_jar)
    if actual_sha1 != expected_sha1:
        raise ValueError(f"Minecraft client JAR SHA-1 mismatch: expected {expected_sha1}, got {actual_sha1}")

    classes: dict[str, dict[str, Any]] = {}
    warnings: list[str] = []
    seen_class_paths: set[str] = set()
    with zipfile.ZipFile(client_jar, "r") as archive:
        infos = archive.infolist()
        names = {item.filename for item in infos}
        missing = [f"{path}.class" for path in REQUIRED_PUBLIC_CLASSES if f"{path}.class" not in names]
        if missing:
            raise ValueError("official client JAR is missing expected 26.3 named classes: " + ", ".join(missing))
        class_infos = [
            item for item in infos
            if not item.is_dir() and item.filename.startswith(MC_PREFIX)
            and item.filename.endswith(".class")
            and not item.filename.endswith(("/package-info.class", "/module-info.class"))
        ]
        if not class_infos:
            raise ValueError("official client JAR contains no net.minecraft class files")
        if len(class_infos) > MAX_CLASSES:
            raise ValueError(f"Minecraft class scan exceeds {MAX_CLASSES} class-file entries")

        for item in class_infos:
            if item.file_size <= 0 or item.file_size > MAX_CLASS_BYTES:
                warnings.append(f"class outside accepted size limit: {item.filename}")
                continue
            try:
                row = parse_class_api(archive.read(item), "minecraft-client-26.3")
            except (ValueError, zipfile.BadZipFile) as error:
                if "non-public class" not in str(error):
                    warnings.append(f"could not inspect {item.filename}: {error}")
                continue
            expected_name = item.filename[:-6]
            if row["internal_name"] != expected_name:
                warnings.append(f"class path/name mismatch: {item.filename} vs {row['internal_name']}")
                continue
            if row["internal_name"] in classes:
                raise ValueError(f"duplicate Minecraft class definition: {row['internal_name']}")
            classes[row["internal_name"]] = row
            seen_class_paths.add(item.filename)

    if len(classes) < 500:
        raise ValueError(f"Minecraft ABI scan appears incomplete: only {len(classes)} public net.minecraft classes")
    missing_public = [name for name in REQUIRED_PUBLIC_CLASSES if name not in classes]
    if missing_public:
        raise ValueError("required Minecraft classes are not public/parsable: " + ", ".join(missing_public))
    member_count = sum(len(row["fields"]) + len(row["methods"]) for row in classes.values())
    if member_count < 1000:
        raise ValueError(f"Minecraft ABI scan appears incomplete: only {member_count} public/protected members")

    return {
        "schema_version": 1,
        "target_minecraft": "26.3",
        "source_artifact": "official Mojang client JAR",
        "client_sha1": actual_sha1,
        "client_sha256": sha256_file(client_jar),
        "public_class_count": len(classes),
        "public_member_count": member_count,
        "scanned_class_file_count": len(class_infos),
        "required_classes": [name.replace("/", ".") for name in REQUIRED_PUBLIC_CLASSES],
        "classes": [classes[key] for key in sorted(classes)],
        "warnings": warnings[:300],
        "bytecode_executed": False,
        "purpose": "exact public JVM class/member descriptors for implementing Minecraft 26.3 compatibility shims",
    }

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("client_jar", type=Path)
    parser.add_argument("expected_sha1")
    parser.add_argument("output_json", type=Path)
    args = parser.parse_args()
    try:
        catalog = generate_catalog(args.client_jar, args.expected_sha1)
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(json.dumps(catalog, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"Minecraft 26.3 ABI catalog generated: {catalog['public_class_count']} classes, "
              f"{catalog['public_member_count']} public/protected members, "
              f"client SHA-1={catalog['client_sha1']}")
        if catalog["warnings"]: print(f"Minecraft ABI catalog warnings: {len(catalog['warnings'])}", file=sys.stderr)
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Minecraft ABI catalog generation failed: {error}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
