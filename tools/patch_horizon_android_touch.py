#!/usr/bin/env python3
"""Apply the MobileHorizonNative overlay to a pinned Horizon checkout.

The patch groups under tools/horizon_patches are intentionally executed in the
listed order. All exact-anchor edits remain fail-fast: if upstream changes a
target, the build stops and names the missing/duplicated anchor instead of
silently shipping a partial runtime.
"""
from pathlib import Path
import runpy
import shutil
import sys

root = Path(sys.argv[1])
repo_root = Path(__file__).resolve().parents[1]

# Shared upstream paths used by the first two patch groups.
# Recent Nightlies moved rendering modules into horizon-client-render.
# Prefer legacy locations when present, then use the active render crate.
game = root / "crates/horizon-client/src/game.rs"
lib = root / "crates/horizon-client/src/lib.rs"
render_src = root / "crates/horizon-client-render/src"
hud = root / "crates/horizon-client/src/hud.rs"
if not hud.is_file():
    hud = render_src / "hud.rs"
audio = root / "crates/horizon-client/src/audio.rs"
if not audio.is_file():
    audio = render_src / "audio.rs"
skins = root / "crates/horizon-client/src/skins.rs"
if not skins.is_file():
    skins = render_src / "skins.rs"
remote = root / "crates/horizon-client/src/game/remote.rs"
chat = root / "crates/horizon-client/src/game/chat.rs"
names = root / "crates/horizon-client/src/game/names.rs"
item_draw = root / "crates/horizon-client/src/game/item_draw.rs"
net = root / "crates/horizon-client/src/net.rs"
client_cargo = root / "crates/horizon-client/Cargo.toml"
client_input_cargo = root / "crates/horizon-client-input/Cargo.toml"
legacy_clipboard = root / "crates/horizon-client/src/clipboard.rs"
input_clipboard = root / "crates/horizon-client-input/src/clipboard.rs"
# Horizon v0.2.92 moved the clipboard implementation into horizon-client-input.
# Prefer the active module path and retain support for older releases.
if legacy_clipboard.is_file():
    clipboard = legacy_clipboard
else:
    clipboard = input_clipboard
pad_menu = root / "crates/horizon-client/src/game/pad_menu.rs"
startup_menu = root / "crates/horizon-client/src/game/startup_menu.rs"
workspace_cargo = root / "Cargo.toml"

required_sources = (game, lib, hud, audio, skins, remote, chat, names, item_draw,
                    net, client_cargo, clipboard, pad_menu, startup_menu, workspace_cargo)
for path in required_sources:
    if not path.is_file():
        raise SystemExit(f"Missing pinned Horizon source file: {path}")
if clipboard == input_clipboard and not client_input_cargo.is_file():
    raise SystemExit(f"Missing Horizon input crate manifest: {client_input_cargo}")

def replace_once(path: Path, old: str, new: str) -> None:
    content = path.read_text(encoding="utf-8")
    count = content.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: expected one replacement for {old[:90]!r}, found {count}"
        )
    path.write_text(content.replace(old, new, 1), encoding="utf-8")

# Keep patch ordering explicit. The runpy namespace is shared so helper
# functions introduced by one group (notably pack_patch) remain available
# to later groups without duplicating their definitions.
patch_dir = Path(__file__).resolve().parent / "horizon_patches"
steps = [
    "01_offline_server_login.py",
    "02_android_native.py",
]
context = dict(globals())
for step in steps:
    step_path = patch_dir / step
    if not step_path.is_file():
        raise SystemExit(f"Missing Horizon patch group: {step_path}")
    result = runpy.run_path(str(step_path), init_globals=context)
    context.update(result)


# Horizon v0.2.73 and v0.2.74 removed Game::extras_key from the extras subsystem.
# The Android-injected key path still feeds vanilla key mappings below; remove only
# the now-obsolete extras hook call so the pinned upstream API compiles cleanly.
legacy_extras_hook = "                    self.extras_key(key, pressed, false);\n"
patched_game = game.read_text(encoding="utf-8")
legacy_count = patched_game.count(legacy_extras_hook)
if legacy_count == 1:
    patched_game = patched_game.replace(
        legacy_extras_hook,
        "                    // Upstream v0.2.73 removed the extras key hook; vanilla key mappings follow below.\n",
        1,
    )
    game.write_text(patched_game, encoding="utf-8")
elif legacy_count == 0 and "self.extras_key(" not in patched_game:
    # Idempotent compatibility for a future native patch that already omits the call.
    pass
else:
    raise SystemExit(
        f"{game}: expected exactly one obsolete extras_key call from the Android input patch, found {legacy_count}"
    )

print("Applied all ordered MobileHorizonNative patch groups.")
