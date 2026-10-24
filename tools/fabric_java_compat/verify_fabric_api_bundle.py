#!/usr/bin/env python3
"""Verify and lock the intact official Fabric API 26.3 aggregate JAR."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path, PurePosixPath

VERSION = "0.165.0+26.3"
MOD_ID = "fabric-api"
MAX_ARCHIVE_BYTES = 12 * 1024 * 1024
MAX_ENTRIES = 50_000
MAX_NESTED_JARS = 100
MAX_NESTED_JAR_BYTES = 8 * 1024 * 1024


def safe_archive_path(raw: str) -> str:
    path = PurePosixPath(raw)
    if not raw or path.is_absolute() or "\\" in raw or "\x00" in raw:
        raise ValueError(f"unsafe nested JAR path: {raw!r}")
    if any(part in ("", ".", "..") for part in raw.split("/")):
        raise ValueError(f"unsafe nested JAR path: {raw!r}")
    return path.as_posix()


def inspect_archive(path: Path) -> dict:
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_ARCHIVE_BYTES:
        raise ValueError("Fabric API archive is missing, empty, or larger than 12 MiB")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    with zipfile.ZipFile(path, "r") as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ValueError("Fabric API archive has too many entries")
        metadata_info = archive.getinfo("fabric.mod.json")
        if metadata_info.file_size > 1024 * 1024:
            raise ValueError("fabric.mod.json is too large")
        metadata = json.loads(archive.read(metadata_info).decode("utf-8"))
        if not isinstance(metadata, dict):
            raise ValueError("fabric.mod.json is not a JSON object")
        if metadata.get("id") != MOD_ID:
            raise ValueError(f"expected mod id {MOD_ID!r}, found {metadata.get('id')!r}")
        if str(metadata.get("version", "")) != VERSION:
            raise ValueError(f"expected Fabric API version {VERSION!r}, found {metadata.get('version')!r}")

        nested = metadata.get("jars", [])
        if not isinstance(nested, list) or not nested:
            raise ValueError("aggregate Fabric API JAR declares no nested module JARs")
        if len(nested) > MAX_NESTED_JARS:
            raise ValueError("aggregate Fabric API JAR declares too many nested JARs")
        nested_files = []
        for declaration in nested:
            if not isinstance(declaration, dict) or not isinstance(declaration.get("file"), str):
                raise ValueError("invalid nested JAR declaration in fabric.mod.json")
            name = safe_archive_path(declaration["file"])
            try:
                info = archive.getinfo(name)
            except KeyError as error:
                raise ValueError(f"declared Fabric API module JAR is missing: {name}") from error
            if info.is_dir() or not name.lower().endswith(".jar") or info.file_size <= 0 or info.file_size > MAX_NESTED_JAR_BYTES:
                raise ValueError(f"invalid nested module JAR: {name}")
            nested_files.append(name)

        return {
            "schema_version": 1,
            "artifact": f"net.fabricmc.fabric-api:fabric-api:{VERSION}",
            "target_minecraft_version": "26.3",
            "fabric_api_version": VERSION,
            "mod_id": MOD_ID,
            "artifact_url": "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.165.0%2B26.3/fabric-api-0.165.0+26.3.jar",
            "sha256": digest,
            "size_bytes": path.stat().st_size,
            "bundle_mode": "single_original_mod_jar",
            "nested_module_jars_declared": len(nested_files),
            "nested_module_paths": nested_files,
            "execution_status": "bundled_only_not_executed",
        }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--upstream-sha256", required=True)
    args = parser.parse_args()
    expected = args.upstream_sha256.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        parser.error("upstream SHA-256 must be 64 hexadecimal characters")
    report = inspect_archive(args.jar)
    if report["sha256"] != expected:
        raise SystemExit("Fabric API JAR hash does not match the official Maven SHA-256 sidecar")
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Verified intact Fabric API {VERSION}: sha256={report['sha256']}, nested module jars={report['nested_module_jars_declared']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
