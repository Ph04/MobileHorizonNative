#!/usr/bin/env python3
"""Guard the Android NativeActivity wrapper against Horizon entrypoint API drift."""
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

    for required in (wrapper, game, lib):
        if not required.is_file():
            raise SystemExit(f"Android native entrypoint check: missing {required}")

    wrapper_text = wrapper.read_text(encoding="utf-8")
    game_text = game.read_text(encoding="utf-8")
    lib_text = lib.read_text(encoding="utf-8")

    call = "horizon_client::game::run_with()"
    if wrapper_text.count(call) != 1:
        raise SystemExit(
            "Android NativeActivity wrapper must call horizon_client::game::run_with() "
            f"exactly once; found {wrapper_text.count(call)}"
        )
    if "horizon_client::game::run()" in wrapper_text:
        raise SystemExit(
            "Obsolete horizon_client::game::run() call remains in the Android wrapper"
        )
    if "pub fn run_with()" not in game_text:
        raise SystemExit(
            "The selected Horizon checkout does not export game::run_with(); "
            "adapt the Android wrapper to this upstream API before compiling"
        )
    if "pub mod game;" not in lib_text:
        raise SystemExit(
            "The selected Horizon checkout does not publicly export its game module"
        )

    print("Android NativeActivity entrypoint matches the selected Horizon game::run_with() API.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
