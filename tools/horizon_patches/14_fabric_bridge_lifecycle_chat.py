"""Wire P1 world lifecycle and outgoing-chat cancellation into real game hooks."""
from pathlib import Path

runtime = root / "crates/horizon-mods/src/runtime.rs"
snapshot_game = root / "crates/horizon-client/src/game/mods.rs"
chat_game = root / "crates/horizon-client/src/game/chat.rs"
remote_game = root / "crates/horizon-client/src/game/remote.rs"
for path in (runtime, snapshot_game, chat_game, remote_game):
    if not path.is_file():
        raise SystemExit(f"Missing source required for P1 lifecycle/chat hooks: {path}")

def rr(path, old, new, label):
    replace_once(path, old, new)

rr(
 runtime,
'''        game::{Clock as P1Clock, Player as P1Player},
        hud::{Anchor as P1Anchor, Element as P1Element, Widget as P1Widget},
        keys::{KeyState as P1KeyState, Trigger as P1Trigger},
        types::ChatMessage as P1ChatMessage,''',
'''        game::{Clock as P1Clock, Player as P1Player},
        hud::{Anchor as P1Anchor, Element as P1Element, Widget as P1Widget},
        keys::{KeyState as P1KeyState, Trigger as P1Trigger},
        types::{ChatMessage as P1ChatMessage, EventResult as P1EventResult, WorldInfo as P1WorldInfo},''',
"P1 lifecycle WIT types",
)

rr(
 runtime,
'''pub struct Snapshot {
    pub player: Option<Player>,
    pub clock: Option<Clock>,
}''',
'''pub struct Snapshot {
    pub player: Option<Player>,
    pub clock: Option<Clock>,
    /// Whether the current snapshot belongs to a local single-player/LAN world.
    pub singleplayer: bool,
}''',
"snapshot singleplayer context",
)

rr(
 runtime,
'''    pub fn set_snapshot(&mut self, snapshot: Snapshot) {
        self.snapshot = Arc::new(snapshot);
    }''',
'''    pub fn set_snapshot(&mut self, snapshot: Snapshot) {
        let previous = self.snapshot.player.as_ref().map(|player| P1WorldInfo {
            dimension: player.dimension.clone(),
            singleplayer: self.snapshot.singleplayer,
        });
        let current = snapshot.player.as_ref().map(|player| P1WorldInfo {
            dimension: player.dimension.clone(),
            singleplayer: snapshot.singleplayer,
        });
        self.snapshot = Arc::new(snapshot);

        match (previous, current) {
            (None, Some(info)) => self.dispatch_p1_world_event("world_join", &info),
            (Some(info), None) => self.dispatch_p1_world_event("world_leave", &info),
            (Some(previous), Some(current))
                if previous.dimension != current.dimension
                    || previous.singleplayer != current.singleplayer =>
            {
                self.dispatch_p1_world_event("world_leave", &previous);
                self.dispatch_p1_world_event("world_join", &current);
            }
            _ => {}
        }
    }''',
"world lifecycle transition detection",
)

rr(
 runtime,
'''    fn call_p1<R>(
''',
'''    fn dispatch_p1_world_event(&mut self, event: &str, info: &P1WorldInfo) {
        self.each_p1(event, FUEL_EVENT, |mod_exports, store| match event {
            "world_join" => mod_exports.horizon_fabric_bridge_guest()
                .call_on_world_join(store, info).map(|_| ()),
            "world_leave" => mod_exports.horizon_fabric_bridge_guest()
                .call_on_world_leave(store, info).map(|_| ()),
            _ => Ok(()),
        });
    }

    /// Runs cancellable P1 outgoing-chat callbacks in deterministic phase order.
    /// Returns true only when a registered guest explicitly cancels the message.
    pub fn chat_send(&mut self, text: &str) -> bool {
        let mut order: Vec<(u8, usize)> = self.entries.iter().enumerate().filter_map(|(index, entry)| {
            let phase = entry.p1_run.as_ref()?.store.data().subscriptions.get("chat_send")?;
            let rank = match phase { P1Phase::Early => 0, P1Phase::Normal => 1, P1Phase::Late => 2 };
            Some((rank, index))
        }).collect();
        order.sort_by_key(|(rank, index)| (*rank, *index));
        for (_, index) in order {
            let result = self.call_p1(index, FUEL_EVENT, |mod_exports, store| {
                mod_exports.horizon_fabric_bridge_guest().call_on_chat_send(store, text)
            });
            if matches!(result, Some(Ok(P1EventResult::Cancel))) {
                return true;
            }
        }
        false
    }

    fn call_p1<R>(
''',
"world dispatch and outgoing-chat callback methods",
)

rr(
 snapshot_game,
'''        Snapshot { player, clock }''',
'''        Snapshot {
            player,
            clock,
            singleplayer: self.remote.is_none(),
        }''',
"game snapshot mode",
)

rr(
 chat_game,
'''    pub(super) fn handle_chat_input(&mut self, message: &str) {
        self.ui.chat.add_recent(message);
        // On a server (single player's own included) the server runs it.''',
'''    pub(super) fn handle_chat_input(&mut self, message: &str) {
        self.ui.chat.add_recent(message);
        // ClientSendMessageEvents.ALLOW_CHAT equivalent for explicitly registered P1 listeners.
        // Commands keep their separate dispatcher and are not intercepted by this chat event.
        if !message.starts_with('/') && self.mods.mods.chat_send(message) {
            return;
        }
        // On a server (single player's own included) the server runs it.''',
"outgoing chat cancellation hook",
)

# Horizon v0.2.27 refreshes its experience display during remote ticks.
# Preserve that new behavior and add the P1 tick hook before the standard send logic.
remote_tick_layouts = [
    (
        '''    fn tick_remote_level(&mut self) -> Result<()> {
        self.ticks += 1;
        // Options.broadcastOptions:''',
        '''    fn tick_remote_level(&mut self) -> Result<()> {
        self.ticks += 1;
        // Run client-side Horizon/P1 callbacks while connected to remote servers too.
        self.tick_mods();
        // Options.broadcastOptions:''',
    ),
    (
        '''    fn tick_remote_level(&mut self) -> Result<()> {
        self.ticks += 1;
        self.track_experience_display();
        // Options.broadcastOptions:''',
        '''    fn tick_remote_level(&mut self) -> Result<()> {
        self.ticks += 1;
        self.track_experience_display();
        // Run client-side Horizon/P1 callbacks while connected to remote servers too.
        self.tick_mods();
        // Options.broadcastOptions:''',
    ),
]
remote_text = remote_game.read_text(encoding="utf-8")
matching_remote_tick = [
    pair for pair in remote_tick_layouts if remote_text.count(pair[0]) == 1
]
if len(matching_remote_tick) != 1:
    raise SystemExit(
        f"{remote_game}: expected exactly one known remote tick layout, "
        f"found {len(matching_remote_tick)}"
    )
old_remote_tick, new_remote_tick = matching_remote_tick[0]
rr(remote_game, old_remote_tick, new_remote_tick, "remote client mod tick hook")

print("Wired registered world join/leave and cancellable outgoing chat; P1 callbacks now tick on remote servers.")
