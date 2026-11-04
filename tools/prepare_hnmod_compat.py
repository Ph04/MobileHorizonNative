#!/usr/bin/env python3
"""Validate native .hnmod compatibility and repin source manifests for Android builds.

A bundle declaring "hooks": N follows the append-only native hook API. A legacy bundle
without that declaration may target an older Horizon commit only when Git proves that
commit is an ancestor of the installed base; its Rust injections are then recompiled
against the exact target checkout. The compiler and final ARM64 build must still pass.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

HOOKS_LINE = re.compile(
    r"^pub\(crate\) const NATIVE_HOOKS_API: u32 = ([0-9]+);$",
    re.MULTILINE,
)
REVISION = re.compile(r"[0-9a-f]{40}")
MIXIN_PINS = re.compile(r"[0-9a-f]{40}")


def native_hooks_api(source_root: Path) -> int:
    path = source_root / "crates/horizon-client/src/game/native_hooks.rs"
    if not path.is_file():
        raise ValueError(f"Target Horizon source is missing {path.relative_to(source_root)}.")
    text = path.read_text(encoding="utf-8")
    matches = HOOKS_LINE.findall(text)
    if len(matches) != 1:
        raise ValueError(
            "Cannot determine NATIVE_HOOKS_API from the exact target Horizon source."
        )
    return int(matches[0])



def git_run(source_root: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(source_root), *args],
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def horizon_revision_is_ancestor(source_root: Path, old_revision: str, target_revision: str) -> bool:
    if old_revision == target_revision:
        return True
    if not REVISION.fullmatch(old_revision):
        return False
    try:
        git_run(source_root, "cat-file", "-e", f"{old_revision}^{{commit}}")
    except subprocess.CalledProcessError:
        # The base checkout is intentionally shallow. Deepen a bounded amount so old
        # package pins can be verified without cloning the entire Horizon history.
        git_run(source_root, "fetch", "--quiet", "--filter=blob:none", "--deepen=12000", "origin", target_revision)
    try:
        git_run(source_root, "cat-file", "-e", f"{old_revision}^{{commit}}")
    except subprocess.CalledProcessError:
        return False
    result = git_run(
        source_root, "merge-base", "--is-ancestor", old_revision, target_revision, check=False
    )
    return result.returncode == 0


def repin_hook_bundle(toml_path: Path, expected_old_revision: str, target_revision: str) -> None:
    text = toml_path.read_text(encoding="utf-8")
    sections = list(re.finditer(
        r"(?ms)^\[compatibility\][ \t]*\r?\n(.*?)(?=^\[|\Z)", text
    ))
    if len(sections) != 1:
        raise ValueError(f"{toml_path}: expected one [compatibility] section.")
    section = sections[0]
    body = section.group(1)
    pattern = re.compile(
        r"(?m)^([ \t]*horizon_revision[ \t]*=[ \t]*)(['\"])([0-9a-f]{40})(\2)([ \t]*(?:\#.*)?)(\r?)$"
    )
    matches = list(pattern.finditer(body))
    if len(matches) != 1:
        raise ValueError(f"{toml_path}: expected one exact compatibility.horizon_revision.")
    if matches[0].group(3) != expected_old_revision:
        raise ValueError(
            f"{toml_path}: TOML Horizon revision does not match its native bundle manifest."
        )
    new_body = pattern.sub(
        lambda m: m.group(1) + m.group(2) + target_revision + m.group(4) + m.group(5) + m.group(6),
        body,
        count=1,
    )
    updated = text[:section.start(1)] + new_body + text[section.end(1):]
    toml_path.write_text(updated, encoding="utf-8")


def prepare(records_path: Path, base_path: Path, source_root: Path, mods_root: Path) -> int:
    records = json.loads(records_path.read_text(encoding="utf-8"))
    base = json.loads(base_path.read_text(encoding="utf-8"))
    if not isinstance(records, list) or not records:
        raise ValueError("No native .hnmod package was validated.")
    target_revision = str(base.get("upstream_commit", "")).lower()
    if not REVISION.fullmatch(target_revision):
        raise ValueError("The installed base runtime has no exact Horizon source commit.")

    mixins = {str(item.get("mixins", "")).lower() for item in records}
    if not mixins or not all(MIXIN_PINS.fullmatch(pin) for pin in mixins):
        raise ValueError(
            "Every selected module must pin one exact 40-character horizon-mixins commit."
        )
    if any(item.get("game") != "26.3" for item in records):
        raise ValueError("Every selected .hnmod must target Minecraft 26.3.")

    api = native_hooks_api(source_root)
    pinned = [item for item in records if item.get("hooks") is None]
    repinned_legacy = []
    for item in pinned:
        old_revision = str(item.get("horizon", "")).lower()
        if not REVISION.fullmatch(old_revision):
            raise ValueError(
                f"{item.get('id', '<unknown>')}: legacy Horizon revision must be an exact 40-character SHA."
            )
        if old_revision == target_revision:
            continue
        if not horizon_revision_is_ancestor(source_root, old_revision, target_revision):
            raise ValueError(
                f"{item.get('id', '<unknown>')} targets Horizon {old_revision[:12]}, which is "
                f"not a verified ancestor of installed revision {target_revision[:12]}. "
                "The package cannot be safely repinned."
            )
        mod_id = str(item.get("id", ""))
        toml_path = mods_root / mod_id / "mixins" / mod_id / "horizon-mixin.toml"
        if not toml_path.is_file():
            raise ValueError(f"{mod_id}: extracted horizon-mixin.toml is missing.")
        repin_hook_bundle(toml_path, old_revision, target_revision)
        repinned_legacy.append(f"{mod_id} ({old_revision[:8]} → {target_revision[:8]})")

    repinned = []
    for item in records:
        required = item.get("hooks")
        if required is None:
            continue
        if not isinstance(required, int) or isinstance(required, bool) or required <= 0:
            raise ValueError(f"{item.get('id', '<unknown>')}: invalid native hooks API requirement.")
        if required > api:
            raise ValueError(
                f"{item.get('id', '<unknown>')} requires native hook points API v{required}, "
                f"but the installed Horizon revision provides v{api}. Update Horizon or use "
                "a module built for an older hook API."
            )
        mod_id = str(item.get("id", ""))
        toml_path = mods_root / mod_id / "mixins" / mod_id / "horizon-mixin.toml"
        if not toml_path.is_file():
            raise ValueError(f"{mod_id}: extracted horizon-mixin.toml is missing.")
        repin_hook_bundle(toml_path, str(item.get("horizon", "")).lower(), target_revision)
        repinned.append(f"{mod_id} (hooks v{required})")

    records.sort(key=lambda item: str(item.get("id", "")))
    records_path.write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
    base["native_hooks_api"] = api
    base["legacy_horizon_revisions_verified"] = True
    base_path.write_text(json.dumps(base, indent=2) + "\n", encoding="utf-8")
    print("Target Horizon:", target_revision)
    print("Target native hooks API:", api)
    print("Pinned legacy modules:", ", ".join(item["id"] for item in pinned) or "none")
    print("Repinned legacy modules after Git ancestry verification:", ", ".join(repinned_legacy) or "none")
    print("Repinned hook modules:", ", ".join(repinned) or "none")
    print("Native mixins package pins:", ", ".join(sorted(mixins)))
    return api


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print(
            "Usage: prepare_hnmod_compat.py <hnmods.json> <base-runtime.json> "
            "<horizon-source> <hnmod-mods>",
            file=sys.stderr,
        )
        return 2
    try:
        prepare(*(Path(value) for value in argv[1:]))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
