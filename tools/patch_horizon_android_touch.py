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
game = root / "crates/horizon-client/src/game.rs"
lib = root / "crates/horizon-client/src/lib.rs"
hud = root / "crates/horizon-client/src/hud.rs"
audio = root / "crates/horizon-client/src/audio.rs"
skins = root / "crates/horizon-client/src/skins.rs"
remote = root / "crates/horizon-client/src/game/remote.rs"
names = root / "crates/horizon-client/src/game/names.rs"
item_draw = root / "crates/horizon-client/src/game/item_draw.rs"
net = root / "crates/horizon-client/src/net.rs"
client_cargo = root / "crates/horizon-client/Cargo.toml"
clipboard = root / "crates/horizon-client/src/clipboard.rs"
pad_menu = root / "crates/horizon-client/src/game/pad_menu.rs"
startup_menu = root / "crates/horizon-client/src/game/startup_menu.rs"
workspace_cargo = root / "Cargo.toml"

for path in (game, lib, hud, audio, skins, remote, names, item_draw, net,
             client_cargo, clipboard, pad_menu, startup_menu, workspace_cargo):
    if not path.is_file():
        raise SystemExit(f"Missing pinned Horizon source file: {path}")

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
    "01_multiplayer_base.py",
    "02_android_native.py",
    "03_server_resource_packs.py",
    "04_remote_entities_and_text.py",
    "05_resource_rendering_and_frames.py",
    "06_resource_diagnostics.py",
    "07_protocol_maps_and_signs.py",
    "08_fabric_bridge_profiles.py",
    "09_fabric_jar_carry.py",
    "10_fabric_bridge_api_p1.py",
    "11_fabric_bridge_p1_runtime.py",
    "12_fabric_bridge_input_dispatch.py",
    "13_fabric_bridge_game_read.py",
    "14_fabric_bridge_lifecycle_chat.py",
    "15_fabric_bridge_inventory_read.py",
    "16_fabric_bridge_runtime_fixes.py",
    "17_fabric_bridge_received_chat.py",
    "18_fabric_bridge_block_interactions.py",
    "19_fabric_java_runtime_bridge.py",
]
context = dict(globals())
for step in steps:
    step_path = patch_dir / step
    if not step_path.is_file():
        raise SystemExit(f"Missing Horizon patch group: {step_path}")
    result = runpy.run_path(str(step_path), init_globals=context)
    context.update(result)

print("Applied all ordered MobileHorizonNative patch groups.")
