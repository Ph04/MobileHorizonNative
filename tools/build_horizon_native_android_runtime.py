#!/usr/bin/env python3
"""Validate native Horizon bundles, apply horizon-mixins, and stage one Android native build.

This script never executes a mod. It validates/extracts source and invokes the
pinned horizon-mixins CLI to patch a separate Horizon source tree. Cargo later
compiles that tree in the isolated GitHub Actions build job.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tomllib
import zipfile
from pathlib import Path

NATIVE_MANIFEST = "horizon-native.json"
CONTENT_MANIFEST = "horizon-mod.toml"
MIXIN_MANIFEST = "horizon-mixin.toml"
MAX_FILES = 512
MAX_SOURCE_BYTES = 64 * 1024 * 1024
MAX_CONTENT_BYTES = 512 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_ENTRIES = 20_000
ID_PATTERN = re.compile(r"[a-z0-9_]{1,64}\Z")
REVISION_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
HOOKS_PATTERN = re.compile(
    r"^pub\(crate\) const NATIVE_HOOKS_API: u32 = (\d+);$", re.MULTILINE
)
HORIZON_PIN_PATTERN = re.compile(
    r'(?m)^(\s*horizon_revision\s*=\s*)"([0-9a-f]{40})"(\s*(?:#.*)?)$'
)


class BundleError(ValueError):
    pass


def read_limited(stream, maximum: int, label: str) -> bytes:
    if maximum < 0:
        raise BundleError(f"{label} exceeds its uncompressed size limit")
    data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise BundleError(f"{label} exceeds its uncompressed size limit")
    return data


def safe_zip_path(name: str, is_dir: bool) -> str:
    if not name or "\\" in name or name.startswith("/") or ":" in name:
        raise BundleError(f"unsafe archive path: {name!r}")
    checked = name[:-1] if is_dir and name.endswith("/") else name
    parts = checked.split("/")
    if not checked or any(part in ("", ".", "..") for part in parts):
        raise BundleError(f"unsafe archive path: {name!r}")
    return checked


def allowed_native_file(relative: str) -> bool:
    if relative.endswith(".rs") or relative.endswith(".toml"):
        return True
    parts = relative.split("/")
    if len(parts) >= 2 and parts[0] == "data":
        return True
    if len(parts) >= 3 and parts[1] == "data":
        return True
    if len(parts) <= 2:
        name = parts[-1]
        return (
            name.startswith(("README", "LICENSE", "LICENCE", "COPYING", "NOTICE"))
            or name == ".gitignore"
        )
    return False


def parse_native_manifest(raw: bytes, archive: Path) -> dict:
    try:
        obj = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BundleError(f"{archive.name}: invalid {NATIVE_MANIFEST}: {exc}") from exc
    if not isinstance(obj, dict):
        raise BundleError(f"{archive.name}: {NATIVE_MANIFEST} must be a JSON object")
    allowed = {"schema", "id", "version", "game", "horizon", "mixins", "hooks"}
    unknown = sorted(set(obj) - allowed)
    if unknown:
        raise BundleError(f"{archive.name}: unknown native manifest keys: {unknown}")
    if obj.get("schema") != 1:
        raise BundleError(f"{archive.name}: native manifest schema 1 is required")
    mod_id = obj.get("id")
    if not isinstance(mod_id, str) or not ID_PATTERN.fullmatch(mod_id):
        raise BundleError(f"{archive.name}: invalid native mod ID")
    for key in ("version", "game"):
        if not isinstance(obj.get(key), str) or not obj[key].strip():
            raise BundleError(f"{archive.name}: missing or empty {key}")
    for key in ("horizon", "mixins"):
        if not isinstance(obj.get(key), str) or not REVISION_PATTERN.fullmatch(obj[key]):
            raise BundleError(f"{archive.name}: {key} must be a full lowercase 40-character commit")
    hooks = obj.get("hooks")
    if hooks is not None and (not isinstance(hooks, int) or isinstance(hooks, bool) or hooks <= 0):
        raise BundleError(f"{archive.name}: hooks must be a positive integer when present")
    return obj


def toml_identity(raw: bytes, archive: Path) -> tuple[dict, dict]:
    try:
        value = tomllib.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
        raise BundleError(f"{archive.name}: invalid {MIXIN_MANIFEST}: {exc}") from exc
    mod = value.get("mod")
    compatibility = value.get("compatibility")
    if not isinstance(mod, dict) or not isinstance(compatibility, dict):
        raise BundleError(f"{archive.name}: {MIXIN_MANIFEST} needs [mod] and [compatibility]")
    return mod, compatibility


def read_bundle(archive: Path) -> dict:
    if archive.is_symlink() or not archive.is_file() or archive.suffix.lower() not in (".hnmod", ".hmod"):
        raise BundleError(f"unsupported or unsafe native package: {archive}")
    try:
        zf = zipfile.ZipFile(archive)
    except (OSError, zipfile.BadZipFile) as exc:
        raise BundleError(f"{archive.name}: not a valid ZIP archive") from exc

    names: set[str] = set()
    native_files: dict[str, bytes] = {}
    native_manifest_raw = None
    content_manifest_raw = None
    source_bytes = 0
    content_bytes = 0
    with zf:
        infos = zf.infolist()
        if len(infos) > MAX_ENTRIES:
            raise BundleError(f"{archive.name}: too many ZIP entries")
        for info in infos:
            name = safe_zip_path(info.filename, info.is_dir())
            if info.is_dir():
                continue
            if name in names:
                raise BundleError(f"{archive.name}: duplicate ZIP path {name}")
            names.add(name)
            mode = (info.external_attr >> 16) & 0o170000
            if mode not in (0, 0o100000):
                raise BundleError(f"{archive.name}: special file type is not allowed: {name}")

            if name == NATIVE_MANIFEST:
                with zf.open(info) as stream:
                    native_manifest_raw = read_limited(stream, MAX_MANIFEST_BYTES, NATIVE_MANIFEST)
                source_bytes += len(native_manifest_raw)
                continue
            if name == CONTENT_MANIFEST:
                with zf.open(info) as stream:
                    content_manifest_raw = read_limited(stream, MAX_MANIFEST_BYTES, CONTENT_MANIFEST)
                continue
            if name.startswith("assets/") or name.startswith("data/"):
                with zf.open(info) as stream:
                    payload = read_limited(stream, MAX_CONTENT_BYTES - content_bytes, name)
                content_bytes += len(payload)
                continue
            if name == "mod.wasm":
                raise BundleError(f"{archive.name}: mod.wasm is no longer supported; use a Rust native mod")
            if len(native_files) >= MAX_FILES:
                raise BundleError(f"{archive.name}: more than {MAX_FILES} native files")
            with zf.open(info) as stream:
                payload = read_limited(stream, MAX_SOURCE_BYTES - source_bytes, name)
            source_bytes += len(payload)
            if source_bytes > MAX_SOURCE_BYTES:
                raise BundleError(f"{archive.name}: native source expands beyond 64 MiB")
            native_files[name] = payload

    if native_manifest_raw is None:
        raise BundleError(f"{archive.name}: {NATIVE_MANIFEST} is missing")
    manifest = parse_native_manifest(native_manifest_raw, archive)

    if content_manifest_raw is not None:
        try:
            content_toml = tomllib.loads(content_manifest_raw.decode("utf-8"))
        except (UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
            raise BundleError(f"{archive.name}: invalid {CONTENT_MANIFEST}: {exc}") from exc
        if content_toml.get("id") != manifest["id"]:
            raise BundleError(
                f"{archive.name}: content and native manifests must declare the same ID"
            )

    prefix = f"mixins/{manifest['id']}/"
    mixin_path = prefix + MIXIN_MANIFEST
    for name in native_files:
        if not name.startswith(prefix):
            raise BundleError(f"{archive.name}: native source outside {prefix}: {name}")
        relative = name[len(prefix):]
        if not allowed_native_file(relative):
            raise BundleError(f"{archive.name}: disallowed native bundle path: {name}")
    if mixin_path not in native_files:
        raise BundleError(f"{archive.name}: {mixin_path} is missing")

    mod_table, compatibility = toml_identity(native_files[mixin_path], archive)
    if (
        mod_table.get("id") != manifest["id"]
        or mod_table.get("version") != manifest["version"]
        or compatibility.get("horizon_revision") != manifest["horizon"]
    ):
        raise BundleError(
            f"{archive.name}: {NATIVE_MANIFEST} and {MIXIN_MANIFEST} identity/revision differ"
        )

    archive_digest = hashlib.sha256()
    with archive.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            archive_digest.update(chunk)
    archive_sha = archive_digest.hexdigest()
    return {
        "archive": archive,
        "manifest": manifest,
        "files": native_files,
        "sha256": archive_sha,
    }


def current_hooks_api(source: Path) -> int:
    path = source / "crates/horizon-client/src/game/native_hooks.rs"
    if not path.is_file():
        raise BundleError(f"Horizon source is missing native hook points: {path}")
    match = HOOKS_PATTERN.search(path.read_text(encoding="utf-8"))
    if not match:
        raise BundleError("Could not read NATIVE_HOOKS_API from the selected Horizon source")
    return int(match.group(1))


def determine_mixins_revision(bundles: list[dict]) -> str:
    revisions = sorted({b["manifest"]["mixins"] for b in bundles})
    if len(revisions) != 1:
        raise BundleError(
            "These packages use different horizon-mixins revisions: "
            + ", ".join(rev[:12] for rev in revisions)
            + ". Install versions built against the same horizon-mixins revision."
        )
    return revisions[0]


def rewrite_horizon_pin(text: str, revision: str, archive: Path) -> str:
    updated, count = HORIZON_PIN_PATTERN.subn(
        lambda match: match.group(1) + '"' + revision + '"' + match.group(3),
        text,
    )
    if count != 1:
        raise BundleError(
            f"{archive.name}: expected one [compatibility].horizon_revision field, found {count}"
        )
    return updated


def stage_bundles(source: Path, bundles: list[dict], mods_dir: Path, upstream_commit: str) -> None:
    api = current_hooks_api(source)
    shutil.rmtree(mods_dir, ignore_errors=True)
    mods_dir.mkdir(parents=True)
    for bundle in bundles:
        manifest = bundle["manifest"]
        if manifest["game"] != "26.3":
            raise BundleError(
                f"{bundle['archive'].name}: targets Minecraft {manifest['game']}; this build is 26.3"
            )
        hooks = manifest.get("hooks")
        if hooks is None:
            if manifest["horizon"] != upstream_commit:
                raise BundleError(
                    f"{manifest['id']} pins Horizon {manifest['horizon'][:12]} without hooks API; "
                    "it must match the exact build commit"
                )
            build_revision = upstream_commit
        else:
            if api < hooks:
                raise BundleError(
                    f"Horizon {upstream_commit[:12]} exposes hook API {api}; "
                    f"{manifest['id']} requires API {hooks}"
                )
            build_revision = upstream_commit

        root = mods_dir / manifest["id"]
        for name, payload in bundle["files"].items():
            relative = name[len(f"mixins/{manifest['id']}/"):]
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if relative == MIXIN_MANIFEST:
                text = payload.decode("utf-8")
                payload = rewrite_horizon_pin(text, build_revision, bundle["archive"]).encode("utf-8")
            destination.write_bytes(payload)


def build_modset_key(bundles: list[dict]) -> str:
    digest = hashlib.sha256()
    for bundle in sorted(bundles, key=lambda item: item["manifest"]["id"]):
        digest.update(bundle["manifest"]["id"].encode("utf-8"))
        digest.update(b"\0")
        digest.update(bytes.fromhex(bundle["sha256"]))
    return digest.hexdigest()


def run(command: list[str], cwd: Path | None = None) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--mod-dir", required=True, type=Path)
    parser.add_argument("--out-dir", required=True, type=Path)
    args = parser.parse_args()

    source = args.source.resolve()
    package_dir = args.mod_dir.resolve()
    output_dir = args.out_dir.resolve()
    if not source.is_dir() or not (source / "Cargo.toml").is_file():
        raise SystemExit(f"invalid Horizon source directory: {source}")
    if not package_dir.is_dir():
        raise SystemExit(
            f"no native-mods/ directory found at {package_dir}; add the exact .hnmod files there"
        )
    archives = sorted(
        path for path in package_dir.iterdir()
        if path.is_file() and path.suffix.lower() in (".hnmod", ".hmod")
    )
    if not archives:
        raise SystemExit("native-mods/ is empty; add at least one .hnmod package")

    bundles = [read_bundle(archive) for archive in archives]
    ids = [bundle["manifest"]["id"] for bundle in bundles]
    if len(ids) != len(set(ids)):
        raise BundleError("Two native packages declare the same mod ID")
    bundles.sort(key=lambda item: item["manifest"]["id"])
    mixins_revision = determine_mixins_revision(bundles)
    upstream_commit = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"], text=True
    ).strip()
    if not REVISION_PATTERN.fullmatch(upstream_commit):
        raise BundleError("Selected Horizon checkout does not have a full commit SHA")

    key = build_modset_key(bundles)
    tag = "horizon-native-android-" + key[:16]
    mods_dir = output_dir / "mods"
    stage_dir = output_dir / "stage"
    metadata_path = output_dir / "metadata.json"
    tool_root = output_dir / "horizon-mixins-tool"
    mixins_binary = tool_root / "bin" / "horizon-mixins"

    output_dir.mkdir(parents=True, exist_ok=True)
    stage_bundles(source, bundles, mods_dir, upstream_commit)

    if not mixins_binary.is_file():
        run([
            "cargo", "install", "--locked", "--git",
            "https://github.com/horizon-rust/horizon-mixins",
            "--rev", mixins_revision, "--root", str(tool_root),
            "horizon-mixins-cli",
        ])

    shutil.rmtree(stage_dir, ignore_errors=True)
    run([
        str(mixins_binary), "apply",
        "--workspace", str(source),
        "--mods", str(mods_dir),
        "--out", str(stage_dir),
    ])

    metadata = {
        "format": 1,
        "tag": tag,
        "mods_sha256": key,
        "upstream_commit": upstream_commit,
        "mixins_revision": mixins_revision,
        "abi": "arm64-v8a",
        "library": "horizon-native-runtime-arm64-v8a.so",
        "mods": [
            {
                "id": item["manifest"]["id"],
                "version": item["manifest"]["version"],
                "sha256": item["sha256"],
            }
            for item in bundles
        ],
    }
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, indent=2))
    print("STAGED_SOURCE=" + str(stage_dir))
    print("METADATA=" + str(metadata_path))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (BundleError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as exc:
        print(f"native Android runtime build preparation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
