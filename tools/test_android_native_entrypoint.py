#!/usr/bin/env python3
"""Verify the generated Android wrapper and revision-specific keyboard/event-loop adapters."""
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
    # The patcher copies the generic template and rewrites the generated cdylib crate.
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

    # Stable exposes the three-argument method; current Nightly adds the physical key.
    mapped_key_start = game_text.find("fn mapped_key(")
    mapped_key_end = game_text.find(") -> Result<()> ", mapped_key_start)
    if mapped_key_end < 0:
        mapped_key_end = game_text.find(") -> Result<()>", mapped_key_start)
    if (mapped_key_start < 0 or mapped_key_end < 0
            or game_text.find("fn mapped_key(", mapped_key_start + 1) >= 0):
        raise SystemExit("Selected Horizon checkout has no unique supported mapped_key declaration")

    mapped_key_signature = game_text[mapped_key_start:mapped_key_end]
    required_parameters = ("&mut self", "key: KeyCode", "pressed: bool", "repeat: bool")
    if not all(parameter in mapped_key_signature for parameter in required_parameters):
        raise SystemExit("Selected Horizon checkout has an unsupported mapped_key signature")
    mapped_key_call = (
        "self.mapped_key(mapped_key, pressed, false, key)"
        if "raw: KeyCode" in mapped_key_signature
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
        f"Horizon game::{selected}(), with the matching keyboard and Winit APIs."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
