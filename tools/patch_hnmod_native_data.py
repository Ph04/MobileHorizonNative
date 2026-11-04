#!/usr/bin/env python3
"""Make Horizon's native-data::built() resolve per-instance files on Android."""
from __future__ import annotations

import argparse
from pathlib import Path

NEEDLE = '''pub fn built(mod_id: &str) -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;'''
REPLACEMENT = '''pub fn built(mod_id: &str) -> Option<PathBuf> {
    // Android loads the engine from a shared object inside the app's per-instance
    // runtime directory; current_exe() is app_process, not that directory.
    if let Some(root) = std::env::var_os("HORIZON_NATIVE_DATA_ROOT") {
        return Some(PathBuf::from(root).join(mod_id));
    }
    let exe = std::env::current_exe().ok()?;'''


def patch(source: Path) -> bool:
    path = source / "crates" / "horizon-client" / "src" / "native_data.rs"
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"Expected Horizon native-data source is missing: {path}")
    text = path.read_text(encoding="utf-8")
    if "HORIZON_NATIVE_DATA_ROOT" in text:
        if REPLACEMENT in text:
            return False
        raise ValueError("native_data.rs already mentions HORIZON_NATIVE_DATA_ROOT with an unknown implementation.")
    if NEEDLE not in text:
        raise ValueError("Horizon native_data::built() changed; refusing an unsafe source rewrite.")
    path.write_text(text.replace(NEEDLE, REPLACEMENT, 1), encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    changed = patch(args.source.resolve())
    print("Android native-data lookup patched." if changed else "Android native-data lookup already patched.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
