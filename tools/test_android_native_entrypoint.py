#!/usr/bin/env python3
"""Verify the generated Android NativeActivity wrapper matches Stable or Nightly Horizon."""
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

    # Android keyboard injection must follow the exact Stable/Nightly API signature.
    mapped_key_signature = re.search(
        r"fn mapped_key\(\s*&mut self\s*,\s*key:\s*KeyCode\s*,"
        r"\s*pressed:\s*bool\s*,\s*repeat:\s*bool"
        r"(?P<raw>\s*,\s*raw:\s*KeyCode)?\s*\)\s*->\s*Result<\(\)>",
        game_text,
    )
    if mapped_key_signature is None:
        raise SystemExit("Selected Horizon checkout has an unsupported mapped_key signature")
    mapped_key_call = (
        "self.mapped_key(mapped_key, pressed, false, key)"
        if mapped_key_signature.group("raw")
        else "self.mapped_key(mapped_key, pressed, false)"
    )
    if game_text.count(mapped_key_call) != 1:
        raise SystemExit(
            f"Generated Android keyboard adapter must call {mapped_key_call!r} exactly once"
        )
    if ("fn new_event_loop<T: 'static + Send>() -> Result<EventLoop<T>>" not in game_text
            or "Ok(EventLoop::<T>::with_user_event().build()?)" not in game_text):
        raise SystemExit(
            "Generated Android event-loop factory must build generic Winit user events on both targets"
        )

    print(
        f"Generated Android NativeActivity wrapper matches selected "
        f"Horizon game::{selected}() entrypoint."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
