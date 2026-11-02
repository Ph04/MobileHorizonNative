#!/usr/bin/env python3
"""Expose a capability-scoped Horizon mod manager through the official WIT host API."""
from pathlib import Path
import sys

root = Path(sys.argv[1])

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count == 0 and new in source:
        print(f"{label}: already applied")
        return
    if count != 1:
        raise SystemExit(f"{path}: expected one {label} anchor, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: applied")

wit = root / "crates/horizon-mod-sdk/wit/horizon.wit"
manifest = root / "crates/horizon-mods/src/manifest.rs"
api_level = root / "crates/horizon-mods/src/api_level.rs"
runtime = root / "crates/horizon-mods/src/runtime.rs"
sdk = root / "crates/horizon-mod-sdk/src/lib.rs"
game_mods = root / "crates/horizon-client/src/game/mods.rs"

required = [wit, manifest, api_level, runtime, sdk, game_mods]
missing = [str(path) for path in required if not path.is_file()]
if missing:
    raise SystemExit("WIT mod manager requires Horizon's complete mod runtime: " + ", ".join(missing))

replace_once(
    wit,
    "/// A client mod: runs in the player's game.\nworld horizon-mod {\n    import log;",
    """/// Capability `mod-manager` (experimental): lists installed Horizon WIT/content
/// modules and changes their enabled state. Changes take effect after the
/// current hook returns, when Horizon reloads the module set.
interface mod-manager {
    record mod-entry {
        id: string,
        name: string,
        version: string,
        description: string,
        /// off, running, elsewhere, or failed.
        status: string,
        enabled: bool,
        /// Loaded with --dev-mod: cannot be toggled through this interface.
        development: bool,
        has-settings: bool,
        problem: option<string>,
    }
    /// A snapshot of installed modules with readable manifests.
    modules: func() -> list<mod-entry>;
    /// Turns an installed module on or off and asks Horizon to reload the
    /// current module set after this hook returns. Development modules are
    /// read-only. No arbitrary code, paths or native libraries are accepted.
    set-enabled: func(id: string, enabled: bool) -> result<_, string>;
}

/// A client mod: runs in the player's game.
world horizon-mod {
    import log;
    import mod-manager;""",
    "add mod-manager WIT interface"
)

replace_once(
    manifest,
    "    /// The recipes and items the game knows, for recipe viewers\n    /// (interface `recipes`).\n    Recipes,\n}",
    """    /// The recipes and items the game knows, for recipe viewers
    /// (interface `recipes`).
    Recipes,
    /// Lists installed WIT/content mods and toggles their enabled state.
    ModManager,
}""",
    "add ModManager capability enum"
)
replace_once(
    manifest,
    "    pub const ALL: [Capability; 12] = [",
    "    pub const ALL: [Capability; 13] = [",
    "extend all capabilities"
)
replace_once(
    manifest,
    "        Capability::Recipes,\n    ];",
    "        Capability::Recipes,\n        Capability::ModManager,\n    ];",
    "register ModManager capability"
)
replace_once(
    manifest,
    "            Capability::Recipes => \"recipes\",\n        }\n    }\n    /// May change in any release: a mod uses it only with",
    "            Capability::Recipes => \"recipes\",\n            Capability::ModManager => \"mod-manager\",\n        }\n    }\n    /// May change in any release: a mod uses it only with",
    "map ModManager WIT interface"
)
replace_once(
    manifest,
    "            Capability::Ui | Capability::Render | Capability::Input | Capability::Recipes\n        )",
    "            Capability::Ui | Capability::Render | Capability::Input | Capability::Recipes | Capability::ModManager\n        )",
    "mark ModManager experimental"
)
replace_once(
    manifest,
    "            Capability::Recipes => {\n                \"read the game's recipes and items and which recipes you know, place recipes in your crafting grid and, in creative mode, give you items\"\n            }\n        }",
    """            Capability::Recipes => {
                "read the game's recipes and items and which recipes you know, place recipes in your crafting grid and, in creative mode, give you items"
            }
            Capability::ModManager => {
                "list installed Horizon modules and enable or disable them; Horizon reloads the module set when a change is applied"
            }
        }""",
    "describe ModManager capability"
)
replace_once(
    manifest,
    "            Capability::Recipes => \"recipes\",\n        };",
    "            Capability::Recipes => \"recipes\",\n            Capability::ModManager => \"mod-manager\",\n        };",
    "display ModManager capability"
)

replace_once(
    api_level,
    "pub const LEVEL: u32 = 7;",
    "pub const LEVEL: u32 = 8;",
    "raise WIT API level for mod manager"
)
replace_once(
    api_level,
    "pub const FUNCTIONS: [(&str, u32); 46] = [",
    "pub const FUNCTIONS: [(&str, u32); 48] = [",
    "extend WIT API function table"
)
replace_once(
    api_level,
    "    (\"render.set-far-terrain\", 7),\n];",
    "    (\"render.set-far-terrain\", 7),\n    (\"mod-manager.modules\", 8),\n    (\"mod-manager.set-enabled\", 8),\n];",
    "register mod-manager API level"
)

replace_once(
    runtime,
    "    pub players: Vec<ServerPlayer>,\n    /// The first block along the player's view",
    """    pub players: Vec<ServerPlayer>,
    /// A safe, path-free snapshot of installed Horizon WIT/content modules.
    pub installed_mods: Vec<InstalledMod>,
    /// The first block along the player's view""",
    "add installed modules to runtime snapshot"
)
replace_once(
    runtime,
    "/// Which mods a game starts: the window's game the client mods, the game\n",
    """/// One installed module exposed to capability `mod-manager`.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct InstalledMod {
    pub id: String,
    pub name: String,
    pub version: String,
    pub description: String,
    pub status: String,
    pub enabled: bool,
    pub development: bool,
    pub has_settings: bool,
    pub problem: Option<String>,
}

/// Which mods a game starts: the window's game the client mods, the game
""",
    "add InstalledMod snapshot type"
)
replace_once(
    runtime,
    "    Give {\n        item: String,\n        count: u32,\n        components: String,\n    },\n}",
    """    Give {
        item: String,
        count: u32,
        components: String,
    },
    /// Capability `mod-manager.set-enabled`: apply after the current hook returns.
    ModEnabled { id: String, enabled: bool },
}""",
    "add queued mod enabled output"
)
replace_once(
    runtime,
    "    horizon::mods::{chat, command, game, hud, log as mod_log, storage},",
    "    horizon::mods::{chat, command, game, hud, log as mod_log, mod_manager, storage},",
    "import mod-manager WIT bindings"
)
replace_once(
    runtime,
    "impl storage::Host for State {",
    """impl mod_manager::Host for State {
    fn modules(&mut self) -> Vec<mod_manager::ModEntry> {
        self.snapshot
            .installed_mods
            .iter()
            .map(|entry| mod_manager::ModEntry {
                id: entry.id.clone(),
                name: entry.name.clone(),
                version: entry.version.clone(),
                description: entry.description.clone(),
                status: entry.status.clone(),
                enabled: entry.enabled,
                development: entry.development,
                has_settings: entry.has_settings,
                problem: entry.problem.clone(),
            })
            .collect()
    }

    fn set_enabled(&mut self, id: String, enabled: bool) -> Result<(), String> {
        let entry = self
            .snapshot
            .installed_mods
            .iter()
            .find(|entry| entry.id == id)
            .ok_or_else(|| format!("Horizon module {id:?} is not installed"))?;
        if entry.development {
            return Err("development modules cannot be toggled by another module".to_owned());
        }
        if entry.enabled != enabled {
            self.out.push(Output::ModEnabled { id, enabled });
        }
        Ok(())
    }
}

impl storage::Host for State {""",
    "implement mod-manager host capability"
)

replace_once(
    sdk,
    "    browser, chat, command, game, hud, input, log, recipes, render, storage, ui,",
    "    browser, chat, command, game, hud, input, log, mod_manager, recipes, render, storage, ui,",
    "export mod-manager from Rust SDK"
)

replace_once(
    game_mods,
    "        Clock, ModKey, Mods, Output, Player, Runs, ServerPlayer, Snapshot, Status, UiScreen,\n        UiWidget,",
    "        Clock, InstalledMod, ModKey, Mods, Output, Player, Runs, ServerPlayer, Snapshot, Status, UiScreen,\n        UiWidget,",
    "import InstalledMod into Horizon game"
)
replace_once(
    game_mods,
    "        let (recipes, known_recipes) = self.mod_recipes();\n        Snapshot {",
    """        let (recipes, known_recipes) = self.mod_recipes();
        let installed_mods = self
            .mods
            .mods
            .entries()
            .iter()
            .filter_map(|entry| {
                let manifest = entry.manifest.as_ref()?;
                let (status, problem) = match &entry.status {
                    Status::Off => ("off".to_owned(), None),
                    Status::Running => ("running".to_owned(), None),
                    Status::Elsewhere => ("elsewhere".to_owned(), None),
                    Status::Failed(reason) => ("failed".to_owned(), Some(reason.clone())),
                };
                Some(InstalledMod {
                    id: manifest.id.clone(),
                    name: manifest.name.clone(),
                    version: manifest.version.clone(),
                    description: manifest.description.clone(),
                    status,
                    enabled: self.settings.mods.enabled.contains(&manifest.id)
                        || self.mods.dev.contains(&entry.path),
                    development: self.mods.dev.contains(&entry.path),
                    has_settings: entry.has_ui_settings(),
                    problem,
                })
            })
            .collect();
        Snapshot {""",
    "build installed module snapshot"
)
replace_once(
    game_mods,
    "            players,\n            view_hit: self.mod_view_hit(),",
    "            players,\n            installed_mods,\n            view_hit: self.mod_view_hit(),",
    "include installed modules in game snapshot"
)
replace_once(
    game_mods,
    "        for output in self.mods.mods.drain() {",
    "        let mut reload_mods = false;\n        for output in self.mods.mods.drain() {",
    "prepare deferred module reload"
)
replace_once(
    game_mods,
    "                Output::Give {\n                    item,\n                    count,\n                    components,\n                } => {\n                    self.mod_give(&item, count, &components);\n                    continue;\n                }",
    """                Output::Give {
                    item,
                    count,
                    components,
                } => {
                    self.mod_give(&item, count, &components);
                    continue;
                }
                Output::ModEnabled { id, enabled } => {
                    let is_development = self
                        .settings
                        .mods
                        .installed
                        .iter()
                        .any(|entry| entry.id == id && entry.dev);
                    if is_development {
                        continue;
                    }
                    let changed = if enabled {
                        self.settings.mods.enabled.insert(id.clone())
                    } else {
                        self.settings.mods.enabled.remove(&id)
                    };
                    if changed {
                        let options = Self::options_path();
                        if let Some(directory) = options.parent() {
                            self.settings.mods.save(directory);
                        }
                        reload_mods = true;
                    }
                    continue;
                }""",
    "apply mod-manager enabled changes"
)
replace_once(
    game_mods,
    "        // A mod may set or clear its settings page in any call, not only\n",
    "        if reload_mods {\n            self.start_mods();\n        }\n        // A mod may set or clear its settings page in any call, not only\n",
    "reload module set after host call completes"
)

print("Installed the capability-scoped WIT mod manager host API.")
