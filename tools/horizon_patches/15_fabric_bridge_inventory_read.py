"""Expose a safe read-only snapshot of the local player inventory."""
from pathlib import Path

runtime = root / "crates/horizon-mods/src/runtime.rs"
manifest = root / "crates/horizon-mods/src/manifest.rs"
game_mods = root / "crates/horizon-client/src/game/mods.rs"
for path in (runtime, manifest, game_mods):
    if not path.is_file():
        raise SystemExit(f"Missing source required for P1 inventory API: {path}")

def rr(path, old, new, label):
    replace_once(path, old, new)

rr(
 runtime,
'''#[derive(Clone, Debug, Default)]
pub struct Snapshot {
    pub player: Option<Player>,
    pub clock: Option<Clock>,
    /// Whether the current snapshot belongs to a local single-player/LAN world.
    pub singleplayer: bool,
}''',
'''#[derive(Clone, Debug, Default)]
pub struct InventoryItem {
    pub id: String,
    pub count: u32,
    pub damage: Option<u32>,
    pub max_damage: Option<u32>,
    /// Simple, stable values only; this is not a lossless item-component encoding.
    pub components: Vec<(String, String)>,
}

#[derive(Clone, Debug, Default)]
pub struct Snapshot {
    pub player: Option<Player>,
    pub clock: Option<Clock>,
    /// Whether the current snapshot belongs to a local single-player/LAN world.
    pub singleplayer: bool,
    pub inventory: Vec<Option<InventoryItem>>,
    pub selected: u32,
    pub offhand: Option<InventoryItem>,
}''',
"inventory snapshot model",
)

rr(
 runtime,
'''        types::{ChatMessage as P1ChatMessage, EventResult as P1EventResult, WorldInfo as P1WorldInfo},''',
'''        types::{
            ChatMessage as P1ChatMessage,
            EventResult as P1EventResult,
            ItemStack as P1ItemStack,
            WorldInfo as P1WorldInfo,
        },''',
"P1 stack type import",
)

rr(
 runtime,
'''impl fabric_bridge_p1_bindings::horizon::fabric_bridge::events::Host for P1State {''',
'''fn p1_item_stack(item: &InventoryItem) -> P1ItemStack {
    P1ItemStack {
        id: item.id.clone(),
        count: item.count,
        damage: item.damage,
        max_damage: item.max_damage,
        components: item.components.clone(),
    }
}

impl fabric_bridge_p1_bindings::horizon::fabric_bridge::inventory::Host for P1State {
    fn get_player(&mut self) -> Vec<(u32, Option<P1ItemStack>)> {
        self.snapshot.inventory.iter().enumerate()
            .map(|(slot, item)| {
                (slot as u32, item.as_ref().map(p1_item_stack))
            })
            .collect()
    }

    fn get_selected(&mut self) -> Option<P1ItemStack> {
        self.snapshot.inventory.get(self.snapshot.selected as usize)?
            .as_ref().map(p1_item_stack)
    }

    fn get_offhand(&mut self) -> Option<P1ItemStack> {
        self.snapshot.offhand.as_ref().map(p1_item_stack)
    }
}

impl fabric_bridge_p1_bindings::horizon::fabric_bridge::events::Host for P1State {''',
"P1 inventory host implementation",
)

rr(
 manifest,
'''pub const ALL: [Capability; 9] = [
        Capability::GameRead,
        Capability::Chat,
        Capability::Hud,
        Capability::Storage,
        Capability::Registries,
        Capability::Events,
        Capability::Keys,
        Capability::Commands,
        Capability::Config,
    ];''',
'''pub const ALL: [Capability; 10] = [
        Capability::GameRead,
        Capability::Chat,
        Capability::Hud,
        Capability::Storage,
        Capability::Registries,
        Capability::Events,
        Capability::Keys,
        Capability::Commands,
        Capability::Config,
        Capability::Inventory,
    ];''',
"manifest inventory capability list",
)

rr(
 manifest,
'''    Commands,
    Config,
}''',
'''    Commands,
    Config,
    Inventory,
}''',
"manifest inventory capability enum",
)

rr(
 manifest,
'''            Capability::Config => "config",
        }
    }
    /// What the player reads before turning the mod on: "May <this>".''',
'''            Capability::Config => "config",
            Capability::Inventory => "inventory",
        }
    }
    /// What the player reads before turning the mod on: "May <this>".''',
"manifest inventory interface mapping",
)

rr(
 manifest,
'''            Capability::Config => "store its own typed settings",
        }''',
'''            Capability::Config => "store its own typed settings",
            Capability::Inventory => "read your current inventory and selected item",
        }''',
"manifest inventory capability description",
)

rr(
 manifest,
'''            ("horizon:fabric-bridge", "config") => Capability::Config,
            _ => return Err(unknown()),''',
'''            ("horizon:fabric-bridge", "config") => Capability::Config,
            ("horizon:fabric-bridge", "inventory") => Capability::Inventory,
            _ => return Err(unknown()),''',
"manifest inventory import validation",
)

rr(
 manifest,
'''            Capability::Config => "config",
        };
        f.write_str(name)''',
'''            Capability::Config => "config",
            Capability::Inventory => "inventory",
        };
        f.write_str(name)''',
"manifest inventory display",
)

rr(
 game_mods,
'''use horizon_mods::{Clock, Mods, Output, Player, Snapshot, Status};''',
'''use horizon_mods::{Clock, InventoryItem, Mods, Output, Player, Snapshot, Status};''',
"import inventory snapshot type",
)

rr(
 game_mods,
'''        let clock = player.is_some().then(|| Clock {
            game_time: self.world.game_time.max(0) as u64,
            day_time: self.world.day_time.max(0) as u64,
        });
        Snapshot {
            player,
            clock,
            singleplayer: self.remote.is_none(),
        }''',
'''        let clock = player.is_some().then(|| Clock {
            game_time: self.world.game_time.max(0) as u64,
            day_time: self.world.day_time.max(0) as u64,
        });
        let snapshot_item = |stack: &crate::items::ItemStack| {
            let max_damage = self.res.items().get(&stack.item)
                .map(|info| info.max_damage).filter(|damage| *damage > 0);
            let mut components = Vec::new();
            if let Some(extra) = &stack.extra {
                if let Some(name) = &extra.custom_name {
                    components.push(("minecraft:custom_name".to_owned(), name.clone()));
                }
                if extra.repair_cost > 0 {
                    components.push(("minecraft:repair_cost".to_owned(), extra.repair_cost.to_string()));
                }
                if let Some(potion) = &extra.potion {
                    components.push(("minecraft:potion_contents".to_owned(), potion.to_string()));
                }
                if let Some((material, pattern)) = &extra.trim {
                    components.push(("minecraft:trim".to_owned(), format!("{material}|{pattern}")));
                }
                if !extra.enchantments.is_empty() {
                    let values = extra.enchantments.iter()
                        .map(|(id, level)| format!("{id}:{level}"))
                        .collect::<Vec<_>>().join(",");
                    components.push(("minecraft:enchantments".to_owned(), values));
                }
                if !extra.stored_enchantments.is_empty() {
                    let values = extra.stored_enchantments.iter()
                        .map(|(id, level)| format!("{id}:{level}"))
                        .collect::<Vec<_>>().join(",");
                    components.push(("minecraft:stored_enchantments".to_owned(), values));
                }
            }
            InventoryItem {
                id: stack.item.to_string(),
                count: stack.count,
                damage: (stack.damage > 0).then_some(stack.damage),
                max_damage,
                components,
            }
        };
        let inventory = self.inventory.iter()
            .map(|stack| stack.as_ref().map(&snapshot_item)).collect();
        let offhand = self.offhand.as_ref().map(&snapshot_item);
        Snapshot {
            player,
            clock,
            singleplayer: self.remote.is_none(),
            inventory,
            selected: self.selected as u32,
            offhand,
        }''',
"populate inventory snapshot from Horizon player state",
)

print("Connected read-only selected/player/offhand inventory access; item component serialization is explicitly partial.")
