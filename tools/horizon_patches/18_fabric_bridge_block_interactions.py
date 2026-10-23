"""Add cancellable native block-break and block-use hooks for Fabric-style events."""
from pathlib import Path

runtime = root / "crates/horizon-mods/src/runtime.rs"
game = root / "crates/horizon-client/src/game.rs"
remote_game = root / "crates/horizon-client/src/game/remote.rs"
for path in (runtime, game, remote_game):
    if not path.is_file():
        raise SystemExit(f"Missing source required for block interaction hooks: {path}")

def rr(path, old, new, label):
    replace_once(path, old, new)

rr(
    runtime,
'''        types::{
            ChatMessage as P1ChatMessage,
            EventResult as P1EventResult,
            ItemStack as P1ItemStack,
            WorldInfo as P1WorldInfo,
        },''',
'''        types::{
            BlockPos as P1BlockPos,
            ChatMessage as P1ChatMessage,
            EventResult as P1EventResult,
            ItemStack as P1ItemStack,
            WorldInfo as P1WorldInfo,
        },''',
    "P1 block position import",
)

rr(
    runtime,
'''        if !matches!(event.as_str(), "client_tick" | "world_join" | "world_leave" | "chat_received" | "chat_send") {''',
'''        if !matches!(
            event.as_str(),
            "client_tick" | "world_join" | "world_leave" | "chat_received"
                | "chat_send" | "block_break" | "block_interact"
        ) {''',
    "block event subscription allow-list",
)

rr(
    runtime,
'''    pub fn chat(&mut self, text: &str) -> bool {''',
'''    /// Fabric PlayerBlockBreakEvents.BEFORE equivalent. The host invokes this
    /// before mutating the target block; any listener may cancel it.
    pub fn block_break(&mut self, pos: [i32; 3], state: &str) -> bool {
        let position = P1BlockPos { x: pos[0], y: pos[1], z: pos[2] };
        let mut order: Vec<(u8, usize)> = self.entries.iter().enumerate().filter_map(|(index, entry)| {
            let phase = entry.p1_run.as_ref()?.store.data().subscriptions.get("block_break")?;
            let rank = match phase { P1Phase::Early => 0, P1Phase::Normal => 1, P1Phase::Late => 2 };
            Some((rank, index))
        }).collect();
        order.sort_by_key(|(rank, index)| (*rank, *index));
        for (_, index) in order {
            let result = self.call_p1(index, FUEL_EVENT, |mod_exports, store| {
                mod_exports.horizon_fabric_bridge_guest()
                    .call_on_block_break(store, &position, state)
            });
            if matches!(result, Some(P1EventResult::Cancel)) {
                return true;
            }
        }
        false
    }

    /// Fabric UseBlockCallback equivalent. Cancellation suppresses the native
    /// block-use path before it changes state or sends a server interaction.
    pub fn block_interact(
        &mut self,
        pos: [i32; 3],
        state: &str,
        face: [i32; 3],
        offhand: bool,
    ) -> bool {
        let position = P1BlockPos { x: pos[0], y: pos[1], z: pos[2] };
        let face = P1BlockPos { x: face[0], y: face[1], z: face[2] };
        let mut order: Vec<(u8, usize)> = self.entries.iter().enumerate().filter_map(|(index, entry)| {
            let phase = entry.p1_run.as_ref()?.store.data().subscriptions.get("block_interact")?;
            let rank = match phase { P1Phase::Early => 0, P1Phase::Normal => 1, P1Phase::Late => 2 };
            Some((rank, index))
        }).collect();
        order.sort_by_key(|(rank, index)| (*rank, *index));
        for (_, index) in order {
            let result = self.call_p1(index, FUEL_EVENT, |mod_exports, store| {
                mod_exports.horizon_fabric_bridge_guest()
                    .call_on_block_interact(store, &position, state, &face, offhand)
            });
            if matches!(result, Some(P1EventResult::Cancel)) {
                return true;
            }
        }
        false
    }

    pub fn chat(&mut self, text: &str) -> bool {''',
    "native block event dispatchers",
)

rr(
    game,
'''    fn break_block_as_player(&mut self, pos: [i32; 3]) -> Result<()> {
        self.pad_rumble(horizon_client::gamepad::Rumble::BlockBroken);
        // ServerPlayerGameMode.destroyBlock.
        let state = self.world.block(pos[0], pos[1], pos[2]);
        let facts = self.res.facts().get(state).clone();
        let state = self.crack_mined_pot(pos, state);''',
'''    fn break_block_as_player(&mut self, pos: [i32; 3]) -> Result<()> {
        // Snapshot the pre-break block before any sound, crack effect or mutation.
        let original_state = self.world.block(pos[0], pos[1], pos[2]);
        let facts = self.res.facts().get(original_state).clone();
        let block_name = facts.name.to_string();
        if self.mods.mods.block_break(pos, &block_name) {
            return Ok(());
        }
        self.pad_rumble(horizon_client::gamepad::Rumble::BlockBroken);
        // ServerPlayerGameMode.destroyBlock.
        let state = self.crack_mined_pot(pos, original_state);''',
    "local world block-break hook",
)

rr(
    game,
'''        if !(sneaking && hands_full)
            && let Some(hit) = block_hit
        {
            let held = self.held().cloned();''',
'''        if !(sneaking && hands_full)
            && let Some(hit) = block_hit
        {
            let block_state = self.world.block(hit.pos[0], hit.pos[1], hit.pos[2]);
            let block_name = self.res.facts().get(block_state).name.to_string();
            if self.mods.mods.block_interact(
                hit.pos,
                &block_name,
                hit.face,
                self.hand == Hand::Off,
            ) {
                return Ok(true);
            }
            let held = self.held().cloned();''',
    "local world block-use hook",
)

rr(
    remote_game,
'''    destroying: Option<([i32; 3], u8, f32)>,
    destroy_delay: u32,''',
'''    destroying: Option<([i32; 3], u8, f32)>,
    /// Target whose break was cancelled by a registered P1 listener.
    block_break_cancelled: Option<[i32; 3]>,
    destroy_delay: u32,''',
    "remote cancelled-break latch field",
)

rr(
    remote_game,
'''            destroying: None,
            destroy_delay: 0,''',
'''            destroying: None,
            block_break_cancelled: None,
            destroy_delay: 0,''',
    "remote cancelled-break latch initialization",
)

rr(
    remote_game,
'''        } else if !self.entity_targeted()
            && let Some(hit) = self.pick()
        {
            self.remote_mining(true);
            instant = self
                .res
                .facts()
                .is_air(self.world.block(hit.pos[0], hit.pos[1], hit.pos[2]));
        } else {''',
'''        } else if !self.entity_targeted()
            && let Some(hit) = self.pick()
        {
            let state = self.world.block(hit.pos[0], hit.pos[1], hit.pos[2]);
            let is_air = self.res.facts().is_air(state);
            let block_name = self.res.facts().get(state).name.to_string();
            if !is_air && self.mods.mods.block_break(hit.pos, &block_name) {
                if let Some(remote) = self.remote.as_mut() {
                    remote.block_break_cancelled = Some(hit.pos);
                    remote.destroying = None;
                }
                self.mining = None;
                return false;
            }
            if let Some(remote) = self.remote.as_mut() {
                remote.block_break_cancelled = None;
            }
            self.remote_mining(true);
            instant = self.res.facts().is_air(self.world.block(hit.pos[0], hit.pos[1], hit.pos[2]));
        } else {''',
    "remote block-break start hook",
)

rr(
    remote_game,
'''    fn remote_continue_attack(&mut self, down: bool) {
        if !down {
            self.clicks.miss_time = 0;
        }''',
'''    fn remote_continue_attack(&mut self, down: bool) {
        if !down {
            self.clicks.miss_time = 0;
            if let Some(remote) = self.remote.as_mut() {
                remote.block_break_cancelled = None;
            }
        }''',
    "release resets cancelled block-break latch",
)

rr(
    remote_game,
'''        if down
            && !entity
            && let Some(hit) = self.pick()
        {
            let state = self.world.block(hit.pos[0], hit.pos[1], hit.pos[2]);''',
'''        if down
            && !entity
            && let Some(hit) = self.pick()
        {
            let cancelled = self.remote.as_ref().and_then(|remote| remote.block_break_cancelled);
            if cancelled == Some(hit.pos) {
                return;
            }
            if cancelled.is_some() {
                if let Some(remote) = self.remote.as_mut() {
                    remote.block_break_cancelled = None;
                }
            }
            let state = self.world.block(hit.pos[0], hit.pos[1], hit.pos[2]);''',
    "remote cancelled-break latch prevents continuation",
)

rr(
    remote_game,
'''        if let Some(hit) = self.pick() {
            let (reacts, changes) = if hand == Hand::Main {
                self.remote_block_reacts(&hit, held.as_ref())''',
'''        if let Some(hit) = self.pick() {
            let block_state = self.world.block(hit.pos[0], hit.pos[1], hit.pos[2]);
            let block_name = self.res.facts().get(block_state).name.to_string();
            if self.mods.mods.block_interact(
                hit.pos,
                &block_name,
                hit.face,
                hand == Hand::Off,
            ) {
                return true;
            }
            let (reacts, changes) = if hand == Hand::Main {
                self.remote_block_reacts(&hit, held.as_ref())''',
    "remote block-use hook before packet dispatch",
)

print("Connected cancellable P1 block-break and block-use callbacks to both local and remote interaction paths.")
