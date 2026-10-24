"""Expose Horizon's immutable local player and clock snapshot to P1 guests."""
from pathlib import Path

runtime = root / "crates/horizon-mods/src/runtime.rs"
manifest = root / "crates/horizon-mods/src/manifest.rs"
for path in (runtime, manifest):
    if not path.is_file():
        raise SystemExit(f"Missing source required for P1 game-read API: {path}")

def rr(old, new, label):
    replace_once(runtime, old, new)

def rm(old, new, label):
    replace_once(manifest, old, new)

rr(
'''        hud::{Anchor as P1Anchor, Element as P1Element, Widget as P1Widget},
        keys::{KeyState as P1KeyState, Trigger as P1Trigger},
        types::ChatMessage as P1ChatMessage,''',
'''        game::{Clock as P1Clock, Player as P1Player},
        hud::{Anchor as P1Anchor, Element as P1Element, Widget as P1Widget},
        keys::{KeyState as P1KeyState, Trigger as P1Trigger},
        types::ChatMessage as P1ChatMessage,''',
"P1 game snapshot imports",
)

rr(
'''struct P1State {
    id: String,
    subscriptions: BTreeMap<String, P1Phase>,''',
'''struct P1State {
    id: String,
    snapshot: Arc<Snapshot>,
    subscriptions: BTreeMap<String, P1Phase>,''',
"P1 state snapshot field",
)

rr(
'''        let state = P1State {
            id: manifest.id.clone(),
            subscriptions: BTreeMap::new(),''',
'''        let state = P1State {
            id: manifest.id.clone(),
            snapshot: Arc::clone(&self.snapshot),
            subscriptions: BTreeMap::new(),''',
"P1 state snapshot initialization",
)

rr(
'''    fn call_p1<R>(
        &mut self,
        index: usize,
        fuel: u64,
        call: impl FnOnce(&ModP1, &mut Store<P1State>) -> wasmtime::Result<R>,
    ) -> Option<R> {
        let entry = &mut self.entries[index];
        let running = entry.p1_run.as_mut()?;''',
'''    fn call_p1<R>(
        &mut self,
        index: usize,
        fuel: u64,
        call: impl FnOnce(&ModP1, &mut Store<P1State>) -> wasmtime::Result<R>,
    ) -> Option<R> {
        let snapshot = Arc::clone(&self.snapshot);
        let entry = &mut self.entries[index];
        let running = entry.p1_run.as_mut()?;
        running.store.data_mut().snapshot = snapshot;''',
"P1 snapshot refresh before every guest call",
)

rr(
'''impl fabric_bridge_p1_bindings::horizon::fabric_bridge::types::Host for P1State {}

impl fabric_bridge_p1_bindings::horizon::fabric_bridge::events::Host for P1State {''',
'''impl fabric_bridge_p1_bindings::horizon::fabric_bridge::types::Host for P1State {}

impl fabric_bridge_p1_bindings::horizon::fabric_bridge::game::Host for P1State {
    fn get_player(&mut self) -> Option<P1Player> {
        let player = self.snapshot.player.as_ref()?;
        Some(P1Player {
            x: player.x,
            y: player.y,
            z: player.z,
            yaw: player.yaw,
            pitch: player.pitch,
            health: player.health,
            food: player.food,
            dimension: player.dimension.clone(),
        })
    }

    fn get_clock(&mut self) -> Option<P1Clock> {
        let clock = self.snapshot.clock.as_ref()?;
        Some(P1Clock {
            game_time: clock.game_time,
            day_time: clock.day_time,
        })
    }
}

impl fabric_bridge_p1_bindings::horizon::fabric_bridge::events::Host for P1State {''',
"P1 game-read host implementation",
)

rm(
'''            ("horizon:fabric-bridge", "events") => Capability::Events,
            ("horizon:fabric-bridge", "keys") => Capability::Keys,''',
'''            ("horizon:fabric-bridge", "game") => Capability::GameRead,
            ("horizon:fabric-bridge", "events") => Capability::Events,
            ("horizon:fabric-bridge", "keys") => Capability::Keys,''',
"P1 game-read capability mapping",
)

print("Connected P1 game-read imports to Horizon's current player/clock snapshot.")
