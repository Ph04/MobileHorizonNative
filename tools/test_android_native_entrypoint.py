#!/usr/bin/env python3
"""Verify the Android NativeActivity wrapper uses the selected Horizon public entrypoint."""
from __future__ import annotations

import argparse
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path,
                        help="Exact Horizon source checkout used for this build")
    parser.add_argument("--wrapper", type=Path, default=Path("android-native/src/lib.rs"))
    args = parser.parse_args()

    source = args.source.resolve()
    wrapper = args.wrapper.resolve()
    game = source / "crates/horizon-client/src/game.rs"
    lib = source / "crates/horizon-client/src/lib.rs"
    for path in (wrapper, game, lib):
        if not path.is_file():
            raise SystemExit(f"Android NativeActivity entrypoint check: missing {path}")

    wrapper_text = wrapper.read_text(encoding="utf-8")
    game_text = game.read_text(encoding="utf-8")
    lib_text = lib.read_text(encoding="utf-8")

    expected_call = "horizon_client::game::run_with()"
    actual_old_call = "horizon_client::game::run()"
    if wrapper_text.count(expected_call) != 1:
        raise SystemExit(
            "Android NativeActivity wrapper must call horizon_client::game::run_with() "
            f"exactly once; found {wrapper_text.count(expected_call)}"
        )
    if actual_old_call in wrapper_text:
        raise SystemExit("Obsolete horizon_client::game::run() call remains in the Android wrapper")
    if "pub fn run_with()" not in game_text:
        raise SystemExit(
            "Selected Horizon checkout does not export game::run_with(); "
            "update the Android wrapper before compiling"
        )
    if "pub mod game;" not in lib_text:
        raise SystemExit("Selected Horizon checkout does not publicly export the game module")

    print("Android NativeActivity wrapper matches the selected Horizon game::run_with() entrypoint.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
