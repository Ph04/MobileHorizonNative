#!/usr/bin/env python3
"""Verify the Android NativeActivity wrapper uses the selected Horizon public entrypoint."""
from __future__ import annotations

import argparse
import re
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path,
                        help="Exact Horizon source checkout used for this build")
    parser.add_argument("--wrapper", type=Path, default=None,
                        help="Optional explicit generated NativeActivity wrapper path")
    args = parser.parse_args()

    source = args.source.resolve()
    # patch_horizon_android_touch.py copies the repository template into the generated
    # cdylib crate and rewrites that copy for the selected Horizon revision. The template
    # intentionally remains run() so it can serve both Stable and Nightly.
    wrapper = (args.wrapper.resolve() if args.wrapper else
               source / "crates/mobilehorizon-android/src/lib.rs")
    game = source / "crates/horizon-client/src/game.rs"
    lib = source / "crates/horizon-client/src/lib.rs"
    for path in (wrapper, game, lib):
        if not path.is_file():
            raise SystemExit(f"Android NativeActivity entrypoint check: missing {path}")

    wrapper_text = wrapper.read_text(encoding="utf-8")
    game_text = game.read_text(encoding="utf-8")
    lib_text = lib.read_text(encoding="utf-8")

    entrypoints = {
        name for name in ("run", "run_with")
        if re.search(
            rf"(?m)^pub fn {name}\(\)\s*->\s*Result<\(\)>\s*\{{",
            game_text,
        )
    }
    if len(entrypoints) != 1:
        raise SystemExit(
            "Selected Horizon checkout must export exactly one supported game entrypoint; "
            f"found {sorted(entrypoints)}"
        )
    selected = next(iter(entrypoints))
    expected_call = f"horizon_client::game::{selected}()"
    calls = re.findall(r"horizon_client::game::run(?:_with)?\(\)", wrapper_text)
    if calls != [expected_call]:
        raise SystemExit(
            f"Generated NativeActivity wrapper must call {expected_call} exactly once; "
            f"found {calls}"
        )
    if "pub mod game;" not in lib_text:
        raise SystemExit("Selected Horizon checkout does not publicly export the game module")

    print(
        f"Generated Android NativeActivity wrapper matches selected "
        f"Horizon game::{selected}() entrypoint."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
