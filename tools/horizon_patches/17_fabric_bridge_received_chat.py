"""Dispatch received server chat through cancellable P1 listeners before rendering it."""
from pathlib import Path

runtime = root / "crates/horizon-mods/src/runtime.rs"
mods_game = root / "crates/horizon-client/src/game/mods.rs"
remote_game = root / "crates/horizon-client/src/game/remote.rs"
for path in (runtime, mods_game, remote_game):
    if not path.is_file():
        raise SystemExit(f"Missing source required for P1 received-chat hook: {path}")

def rr(path, old, new, label):
    replace_once(path, old, new)

rr(
    runtime,
'''    pub fn chat(&mut self, text: &str) {
        self.each(
            FUEL_EVENT,
            |h| h.chat,
            |m, s| m.horizon_mods_events().call_chat(s, text),
        );
        let message = P1ChatMessage { text: text.to_owned(), sender: None, system: false };
        self.each_p1("chat_received", FUEL_EVENT, |m, s| {
            m.horizon_fabric_bridge_guest().call_on_chat_received(s, &message).map(|_| ())
        });
    }''',
'''    /// Dispatches the legacy observation callback and the cancellable P1 event.
    /// Returns true only when a P1 listener explicitly cancels presentation.
    pub fn chat(&mut self, text: &str) -> bool {
        self.each(
            FUEL_EVENT,
            |h| h.chat,
            |m, s| m.horizon_mods_events().call_chat(s, text),
        );
        self.chat_received(P1ChatMessage {
            text: text.to_owned(),
            sender: None,
            system: false,
        })
    }

    pub fn chat_received(&mut self, message: P1ChatMessage) -> bool {
        let mut order: Vec<(u8, usize)> = self.entries.iter().enumerate()
            .filter_map(|(index, entry)| {
                let phase = entry.p1_run.as_ref()?.store.data().subscriptions.get("chat_received")?;
                let rank = match phase {
                    P1Phase::Early => 0,
                    P1Phase::Normal => 1,
                    P1Phase::Late => 2,
                };
                Some((rank, index))
            })
            .collect();
        order.sort_by_key(|(rank, index)| (*rank, *index));
        for (_, index) in order {
            let result = self.call_p1(index, FUEL_EVENT, |mod_exports, store| {
                mod_exports.horizon_fabric_bridge_guest()
                    .call_on_chat_received(store, &message)
            });
            if matches!(result, Some(P1EventResult::Cancel)) {
                return true;
            }
        }
        false
    }''',
    "cancellable P1 received-chat dispatch",
)

rr(
    mods_game,
'''    pub(super) fn mods_chat(&mut self, line: &str) {
        self.mods.mods.chat(line);
        self.show_mod_output();
    }''',
'''    pub(super) fn mods_chat(&mut self, line: &str) -> bool {
        let cancelled = self.mods.mods.chat(line);
        self.show_mod_output();
        cancelled
    }''',
    "propagate chat cancellation through Game",
)

rr(
    remote_game,
'''        self.ui.chat.add(
            &self.gui.atlas,
            horizon_client::text::Text::literal(text.clone()),
            None,
            self.ticks,
            self.screen == Screen::Chat,
        );''',
'''        // Fabric's received-chat allow/cancel hook runs before this message is
        // presented. The packet is still counted and retained in remote history.
        if !self.mods_chat(&text) {
            self.ui.chat.add(
                &self.gui.atlas,
                horizon_client::text::Text::literal(text.clone()),
                None,
                self.ticks,
                self.screen == Screen::Chat,
            );
        }''',
    "server chat presentation hook",
)

print("Connected cancellable P1 received-chat events to multiplayer chat presentation.")
