#!/usr/bin/env python3
"""Build and run Horizon native-mod post-build programs on the CI host.

The Android game remains cross-compiled for AArch64. Post-build programs are
host tools, just as in Horizon's desktop launcher; their generated data is
packaged beside the per-instance engine runtime for the Android loader.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

MAX_STEPS = 24
MAX_FILES = 4096
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024
SAFE_ID = re.compile(r"[a-z0-9_]{1,64}\Z")
SAFE_PACKAGE = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
SAFE_BIN = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")


def safe_data_path(path: str) -> bool:
    if not isinstance(path, str) or not path or len(path) > 512:
        return False
    if path.startswith("/") or "\\" in path or ":" in path or "\x00" in path:
        return False
    # Split the original string instead of PurePosixPath.parts: pathlib normalizes
    # some "." and empty components, while the Android ZIP installer rejects them.
    parts = path.split("/")
    return (
        len(parts) >= 3
        and parts[0] == "native-data"
        and bool(SAFE_ID.fullmatch(parts[1]))
        and all(part not in ("", ".", "..") for part in parts[2:])
        and all(not any(ord(ch) < 32 or ord(ch) == 127 for ch in part) for part in parts)
    )


def load_steps(source: Path) -> list[dict]:
    path = source / "native-post-build.json"
    if not path.exists():
        return []
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 256 * 1024:
        raise ValueError("native-post-build.json is not a safe regular file.")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, list) or len(value) > MAX_STEPS:
        raise ValueError("native-post-build.json must be an array of at most 24 steps.")
    steps: list[dict] = []
    for index, item in enumerate(value):
        if not isinstance(item, dict):
            raise ValueError(f"Post-build step {index} is not an object.")
        mod_id = item.get("mod")
        package = item.get("package")
        binary = item.get("bin")
        args = item.get("args", [])
        if not isinstance(mod_id, str) or not SAFE_ID.fullmatch(mod_id):
            raise ValueError(f"Post-build step {index} has an invalid mod id.")
        if not isinstance(package, str) or not SAFE_PACKAGE.fullmatch(package):
            raise ValueError(f"Post-build step {index} has an invalid Cargo package.")
        if not isinstance(binary, str) or not SAFE_BIN.fullmatch(binary):
            raise ValueError(f"Post-build step {index} has an invalid binary name.")
        if (not isinstance(args, list) or len(args) > 64
                or any(not isinstance(arg, str) or len(arg) > 4096
                       or any(ord(ch) < 32 and ch not in "\t" for ch in arg)
                       for arg in args)
                or sum(len(arg) for arg in args) > 16384):
            raise ValueError(f"Post-build step {index} has invalid arguments.")
        steps.append({"mod": mod_id, "package": package, "bin": binary, "args": args})
    return steps


def native_data_index(output: Path, allowed_mods: set[str]) -> list[dict]:
    root = output / "native-data"
    if root.is_symlink():
        raise ValueError("The native-data output root may not be a symlink.")
    root.mkdir(parents=True, exist_ok=True)
    records: list[dict] = []
    total = 0
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"Native post-build output may not contain symlinks: {path}")
        if path.is_dir():
            continue
        if not path.is_file():
            raise ValueError(f"Native post-build output is not a regular file: {path}")
        relative = path.relative_to(output).as_posix()
        if not safe_data_path(relative):
            raise ValueError(f"Unsafe native-data output path: {relative}")
        mod_id = relative.split("/", 2)[1]
        if mod_id not in allowed_mods:
            raise ValueError(f"Native-data output belongs to an unselected module: {mod_id}")
        size = path.stat().st_size
        if size > MAX_FILE_BYTES:
            raise ValueError(f"Native-data file exceeds 64 MiB: {relative}")
        total += size
        if total > MAX_TOTAL_BYTES or len(records) >= MAX_FILES:
            raise ValueError("Native post-build data exceeds the 128 MiB / 4096-file limit.")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        records.append({"path": relative, "size": size, "sha256": digest})
    return records


def _print_sanitized_log(path: Path, label: str) -> None:
    if not path.is_file():
        return
    with path.open("rb") as log:
        excerpt = log.read(128 * 1024).decode("utf-8", errors="replace")
    for line in excerpt.splitlines():
        print(f"[hnmod:{label}] " + line.replace("::", ": :"), flush=True)


def run_steps(source: Path, output: Path, cache: Path, timeout: int = 600) -> list[dict]:
    steps = load_steps(source)
    output.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)
    programs = cache.parent / "hnmod-post-build-programs"
    shutil.rmtree(programs, ignore_errors=True)
    target_dir = source / "target"
    native_root = output / "native-data"
    if native_root.is_symlink():
        raise ValueError("The native-data output root may not be a symlink.")
    native_root.mkdir(parents=True, exist_ok=True)

    # No checkout credential, action token, or OIDC token is passed to mod code.
    clean_env = dict(os.environ)
    # Native .hnmod code is executable. Do not expose GitHub/ACTIONS/runner
    # credentials or workflow-command file paths to its build scripts or binaries.
    for key in list(clean_env):
        if key.startswith(("GITHUB_", "ACTIONS_", "RUNNER_", "INPUT_", "STATE_")):
            clean_env.pop(key, None)
    clean_env["GIT_TERMINAL_PROMPT"] = "0"

    for index, step in enumerate(steps):
        print(
            f"Post-build {index + 1}/{len(steps)}: "
            f"build {step['package']}::{step['bin']} for the CI host",
            flush=True,
        )
        build_env = dict(clean_env)
        build_env["CARGO_TARGET_DIR"] = str(target_dir)
        program_dir = programs / str(index)
        program_dir.mkdir(parents=True, exist_ok=True)
        command = [
            "cargo", "build", "--locked", "--release",
            "--manifest-path", str(source / "Cargo.toml"),
            "-p", step["package"], "--bin", step["bin"],
        ]
        build_log = program_dir / "cargo-build.log"
        try:
            with build_log.open("wb") as log:
                subprocess.run(command, cwd=source, env=build_env, check=True,
                               timeout=timeout, stdout=log, stderr=subprocess.STDOUT)
        except subprocess.CalledProcessError:
            _print_sanitized_log(build_log, step["mod"] + ":cargo")
            raise
        _print_sanitized_log(build_log, step["mod"] + ":cargo")

        built = target_dir / "release" / step["bin"]
        if not built.is_file() or built.is_symlink():
            raise RuntimeError(f"Cargo did not produce the expected host program: {built}")
        program = program_dir / step["bin"]
        shutil.copy2(built, program)

        data_dir = output / "native-data" / step["mod"]
        mod_cache = cache / step["mod"]
        bundle_data = source / "native-data" / step["mod"]
        for directory in (data_dir, mod_cache, bundle_data):
            if directory.is_symlink():
                raise ValueError(f"Post-build directory may not be a symlink: {directory}")
            directory.mkdir(parents=True, exist_ok=True)

        env = dict(clean_env)
        env.update({
            "HORIZON_NATIVE_MOD": step["mod"],
            "HORIZON_NATIVE_VERSION_DIR": str(output),
            "HORIZON_NATIVE_DATA_DIR": str(data_dir),
            "HORIZON_NATIVE_CACHE_DIR": str(mod_cache),
            "HORIZON_NATIVE_BUNDLE_DATA_DIR": str(bundle_data),
        })
        command = [str(program), *step["args"]]
        print(f"Running {step['mod']} post-build generator", flush=True)
        log_path = program_dir / "post-build.log"
        with log_path.open("wb") as log:
            try:
                subprocess.run(
                    command, cwd=output, env=env, check=True, timeout=timeout,
                    stdout=log, stderr=subprocess.STDOUT,
                )
            except subprocess.CalledProcessError:
                _print_sanitized_log(log_path, step["mod"])
                raise
        _print_sanitized_log(log_path, step["mod"])

    records = native_data_index(output, {step["mod"] for step in steps})
    index_file = output / "native-data-index.json"
    if index_file.is_symlink() or (index_file.exists() and not index_file.is_file()):
        raise ValueError("Native-data index output is not a regular file.")
    index_file.write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
    print(f"Post-build completed: {len(steps)} step(s), {len(records)} data file(s).", flush=True)
    return records


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("cache", type=Path)
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    if args.timeout < 1 or args.timeout > 1800:
        parser.error("--timeout must be between 1 and 1800 seconds.")
    try:
        run_steps(args.source.resolve(), args.output.resolve(), args.cache.resolve(), args.timeout)
    except (OSError, ValueError, json.JSONDecodeError, subprocess.CalledProcessError,
            subprocess.TimeoutExpired, RuntimeError) as error:
        print(f"::error::Native .hnmod post-build failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
