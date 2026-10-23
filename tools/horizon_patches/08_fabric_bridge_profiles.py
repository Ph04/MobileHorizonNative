"""Give the Android client an opt-in, isolated mod profile for Fabric-ported packages.

The profile selects a separate mod directory and enable-list. It does not change
Minecraft's handshake, client brand, packet flow, worlds, options.txt, or native
Horizon resources. Without HORIZON_MOD_PROFILE=fabric-bridge, the old paths remain.
"""
from pathlib import Path

mod_list = root / "crates/horizon-client/src/mod_list.rs"
game_mods = root / "crates/horizon-client/src/game/mods.rs"
game_options = root / "crates/horizon-client/src/game/options.rs"
startup_menu = root / "crates/horizon-client/src/game/startup_menu.rs"

for path in (mod_list, game_mods, game_options, startup_menu):
    if not path.is_file():
        raise SystemExit(f"Missing source required for Fabric Bridge profile patch: {path}")

replace_once(
    mod_list,
    '''pub const FILE: &str = "horizon-mods.txt";
/// The folder holding .hmod files, beside options.txt.
pub const DIR: &str = "horizon-mods";''',
    '''pub const FILE: &str = "horizon-mods.txt";
/// The folder holding .hmod files, beside options.txt.
pub const DIR: &str = "horizon-mods";
/// Enable-list and mod folder reserved for the optional Fabric Bridge profile.
pub const FABRIC_BRIDGE_FILE: &str = "horizon-fabric-bridge-mods.txt";
pub const FABRIC_BRIDGE_DIR: &str = "horizon-fabric-bridge-mods";

/// True only when Android's launcher explicitly selected the optional profile.
/// Missing or unknown values retain Horizon's original Vanilla-compatible path.
pub fn fabric_bridge_enabled() -> bool {
    std::env::var("HORIZON_MOD_PROFILE")
        .is_ok_and(|profile| profile == "fabric-bridge")
}

/// Active mod directory. This is read during startup/mod-list operations, not per frame.
pub fn active_dir() -> &'static str {
    if fabric_bridge_enabled() {
        FABRIC_BRIDGE_DIR
    } else {
        DIR
    }
}

/// Active enable-list beside options.txt. Normal options and save paths stay shared.
pub fn active_file() -> &'static str {
    if fabric_bridge_enabled() {
        FABRIC_BRIDGE_FILE
    } else {
        FILE
    }
}'''
)

replace_once(
    mod_list,
    "fs::read_to_string(dir.join(FILE))",
    "fs::read_to_string(dir.join(active_file()))",
)
replace_once(
    mod_list,
    "let path = dir.join(FILE);",
    "let path = dir.join(active_file());",
)

replace_once(
    game_mods,
    "let mut paths = horizon_mods::find(&here.join(mod_list::DIR));",
    "let mut paths = horizon_mods::find(&here.join(mod_list::active_dir()));",
)
replace_once(
    game_mods,
    "let dir = Self::options_path().with_file_name(mod_list::DIR);",
    '''let dir = Self::options_path().with_file_name(mod_list::active_dir());
        log::info!(
            "Horizon: mod profile = {}",
            if mod_list::fabric_bridge_enabled() { "fabric-bridge" } else { "vanilla" }
        );'''
)
replace_once(
    game_options,
    "Self::options_path().with_file_name(horizon_client::mod_list::DIR)",
    "Self::options_path().with_file_name(horizon_client::mod_list::active_dir())",
)
replace_once(
    startup_menu,
    "Game::options_path().with_file_name(horizon_client::mod_list::DIR)",
    "Game::options_path().with_file_name(horizon_client::mod_list::active_dir())",
)

print("Applied optional Fabric Bridge mod-profile isolation.")
