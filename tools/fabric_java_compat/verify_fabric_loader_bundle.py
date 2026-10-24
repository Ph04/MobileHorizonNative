#!/usr/bin/env python3
"""Verify and lock the official Fabric Loader JAR and optional Horizon provider."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path

VERSION = "0.19.5"
MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
MAX_PROVIDER_BYTES = 4 * 1024 * 1024
MAX_ENTRIES = 100_000
REQUIRED_CLASSES = (
    "net/fabricmc/loader/impl/launch/knot/KnotClient.class",
    "net/fabricmc/loader/impl/game/GameProvider.class",
    "net/fabricmc/loader/api/FabricLoader.class",
)
PROVIDER_CLASSES = (
    "dev/mobilehorizon/fabric/loader/HorizonGameProvider.class",
    "dev/mobilehorizon/fabric/loader/HorizonPreflightMain.class",
)
PROVIDER_SERVICE = "META-INF/services/net.fabricmc.loader.impl.game.GameProvider"
PROVIDER_NAME = "dev.mobilehorizon.fabric.loader.HorizonGameProvider"
DEPENDENCIES = (
    ("org.ow2.asm:asm:9.10.1", "asm-9.10.1.jar", "org/objectweb/asm/ClassReader.class"),
    ("org.ow2.asm:asm-analysis:9.10.1", "asm-analysis-9.10.1.jar", "org/objectweb/asm/tree/analysis/Analyzer.class"),
    ("org.ow2.asm:asm-commons:9.10.1", "asm-commons-9.10.1.jar", "org/objectweb/asm/commons/ClassRemapper.class"),
    ("org.ow2.asm:asm-tree:9.10.1", "asm-tree-9.10.1.jar", "org/objectweb/asm/tree/ClassNode.class"),
    ("org.ow2.asm:asm-util:9.10.1", "asm-util-9.10.1.jar", "org/objectweb/asm/util/CheckClassAdapter.class"),
    ("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7",
     "sponge-mixin-0.17.4+mixin.0.8.7.jar", "org/spongepowered/asm/launch/MixinBootstrap.class"),
)
MAX_DEPENDENCY_BYTES = 16 * 1024 * 1024

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(128 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def inspect_provider(path: Path) -> dict:
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_PROVIDER_BYTES:
        raise ValueError("Horizon Loader provider JAR is missing, empty, or larger than 4 MiB")
    with zipfile.ZipFile(path, "r") as archive:
        names = {item.filename for item in archive.infolist() if not item.is_dir()}
        missing = [name for name in (*PROVIDER_CLASSES, PROVIDER_SERVICE) if name not in names]
        if missing:
            raise ValueError("Horizon Loader provider is missing required entries: " + ", ".join(missing))
        if archive.read(PROVIDER_SERVICE).decode("utf-8").strip() != PROVIDER_NAME:
            raise ValueError("Horizon Loader provider service descriptor names an unexpected provider")
    return {
        "provider_sha256": sha256(path),
        "provider_size_bytes": path.stat().st_size,
        "provider_service": PROVIDER_NAME,
        "provider_execution_status": "preflight_provider_bundled_not_launched_by_game",
    }

def inspect_dependencies(directory: Path) -> list[dict]:
    if not directory.is_dir():
        raise ValueError("Fabric Loader dependency directory does not exist")
    expected_files = {filename for _, filename, _ in DEPENDENCIES}
    actual_files = {item.name for item in directory.iterdir()
                    if item.is_file() and item.suffix == ".jar"}
    if actual_files != expected_files:
        raise ValueError("Fabric Loader dependency JAR set is incomplete or contains unexpected JARs")
    reports = []
    for coordinate, filename, required_class in DEPENDENCIES:
        path = directory / filename
        if not path.is_file() or not 0 < path.stat().st_size <= MAX_DEPENDENCY_BYTES:
            raise ValueError(f"Fabric Loader dependency is missing, empty, or oversized: {filename}")
        try:
            with zipfile.ZipFile(path, "r") as archive:
                if archive.testzip() is not None:
                    raise ValueError(f"Fabric Loader dependency has a corrupt entry: {filename}")
                names = {item.filename for item in archive.infolist() if not item.is_dir()}
                if required_class not in names:
                    raise ValueError(f"Fabric Loader dependency lacks {required_class}: {filename}")
        except zipfile.BadZipFile as error:
            raise ValueError(f"Fabric Loader dependency is not a valid JAR: {filename}") from error
        reports.append({
            "artifact": coordinate,
            "file": filename,
            "sha256": sha256(path),
            "size_bytes": path.stat().st_size,
        })
    return reports

def inspect_archive(path: Path, provider: Path | None = None,
                    dependency_dir: Path | None = None) -> dict:
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_ARCHIVE_BYTES:
        raise ValueError("Fabric Loader archive is missing, empty, or larger than 16 MiB")
    with zipfile.ZipFile(path, "r") as archive:
        entries = archive.infolist()
        if len(entries) > MAX_ENTRIES:
            raise ValueError("Fabric Loader archive has too many entries")
        broken = archive.testzip()
        if broken is not None:
            raise ValueError(f"Fabric Loader archive has a corrupt entry: {broken}")
        names = {item.filename for item in entries if not item.is_dir()}
        missing = [name for name in REQUIRED_CLASSES if name not in names]
        if missing:
            raise ValueError("Fabric Loader JAR is missing required runtime classes: " + ", ".join(missing))
    result = {
        "schema_version": 1,
        "artifact": f"net.fabricmc:fabric-loader:{VERSION}",
        "target_minecraft_version": "26.3",
        "loader_version": VERSION,
        "artifact_url": f"https://maven.fabricmc.net/net/fabricmc/fabric-loader/{VERSION}/fabric-loader-{VERSION}.jar",
        "sha256": sha256(path),
        "size_bytes": path.stat().st_size,
        "required_classes": list(REQUIRED_CLASSES),
        "execution_status": "bundled_verified_not_launched",
    }
    if provider is not None:
        result.update(inspect_provider(provider))
    if dependency_dir is not None:
        result["dependencies"] = inspect_dependencies(dependency_dir)
        result["dependency_execution_status"] = "official_runtime_dependencies_bundled"
    return result

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--upstream-sha256", required=True)
    parser.add_argument("--provider-jar", type=Path)
    parser.add_argument("--dependencies-dir", type=Path)
    args = parser.parse_args()
    expected = args.upstream_sha256.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        parser.error("upstream SHA-256 must be 64 hexadecimal characters")
    report = inspect_archive(args.jar, args.provider_jar, args.dependencies_dir)
    if report["sha256"] != expected:
        raise SystemExit("Fabric Loader JAR hash does not match the official Maven SHA-256 sidecar")
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Verified official Fabric Loader {VERSION}: sha256={report['sha256']}")
    if args.provider_jar is not None:
        print(f"Verified Horizon Loader provider: sha256={report['provider_sha256']}")
    if args.dependencies_dir is not None:
        print(f"Verified {len(report['dependencies'])} official Fabric Loader runtime dependency JARs.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
