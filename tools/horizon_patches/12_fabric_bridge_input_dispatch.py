"""Connect P1 keyboard bindings to Horizon's post-rebinding input path.

P1 key values are USB HID Keyboard/Keypad Usage IDs, not Rust enum
discriminants or platform-specific Android keycodes.
"""
from pathlib import Path

runtime = root / "crates/horizon-mods/src/runtime.rs"
game = root / "crates/horizon-client/src/game.rs"
for path in (runtime, game):
    if not path.is_file():
        raise SystemExit(f"Missing source required for P1 keyboard dispatch: {path}")

replace_once(
    runtime,
    "keys::Trigger as P1Trigger,",
    "keys::{KeyState as P1KeyState, Trigger as P1Trigger},",
)

key_method = """    /// Delivers a post-rebinding keyboard event to registered P1 keybindings.
    /// The numeric code is the USB HID Keyboard/Keypad Usage ID.
    pub fn key(&mut self, code: u32, pressed: bool) {
        let owners: Vec<(usize, Vec<String>)> = self.entries.iter().enumerate()
            .filter_map(|(index, entry)| {
                let running = entry.p1_run.as_ref()?;
                let ids: Vec<String> = running.store.data().keybindings.iter()
                    .filter_map(|(id, trigger)| match trigger {
                        P1Trigger::Key(value) if *value == code => Some(id.clone()),
                        _ => None,
                    })
                    .collect();
                (!ids.is_empty()).then_some((index, ids))
            })
            .collect();
        for (index, ids) in owners {
            for id in ids {
                let _ = self.call_p1(index, "key", FUEL_EVENT, |m, store| {
                    let state = if pressed { P1KeyState::Pressed } else { P1KeyState::Released };
                    m.horizon_fabric_bridge_guest().call_on_key(store, &id, state).map(|_| ())
                });
            }
        }
    }

"""
replace_once(
    runtime,
    "    /// A game tick.\n    pub fn tick(&mut self, tick: u64) {",
    key_method + "    /// A game tick.\n    pub fn tick(&mut self, tick: u64) {",
)

key_map = """    /// Stable USB HID keyboard usage IDs for P1 mods; never serialize Rust enum values.
    fn fabric_bridge_key_code(key: KeyCode) -> Option<u32> {
        match key {
            KeyCode::KeyA => Some(0x04), KeyCode::KeyB => Some(0x05),
            KeyCode::KeyC => Some(0x06), KeyCode::KeyD => Some(0x07),
            KeyCode::KeyE => Some(0x08), KeyCode::KeyF => Some(0x09),
            KeyCode::KeyG => Some(0x0A), KeyCode::KeyH => Some(0x0B),
            KeyCode::KeyI => Some(0x0C), KeyCode::KeyJ => Some(0x0D),
            KeyCode::KeyK => Some(0x0E), KeyCode::KeyL => Some(0x0F),
            KeyCode::KeyM => Some(0x10), KeyCode::KeyN => Some(0x11),
            KeyCode::KeyO => Some(0x12), KeyCode::KeyP => Some(0x13),
            KeyCode::KeyQ => Some(0x14), KeyCode::KeyR => Some(0x15),
            KeyCode::KeyS => Some(0x16), KeyCode::KeyT => Some(0x17),
            KeyCode::KeyU => Some(0x18), KeyCode::KeyV => Some(0x19),
            KeyCode::KeyW => Some(0x1A), KeyCode::KeyX => Some(0x1B),
            KeyCode::KeyY => Some(0x1C), KeyCode::KeyZ => Some(0x1D),
            KeyCode::Digit1 => Some(0x1E), KeyCode::Digit2 => Some(0x1F),
            KeyCode::Digit3 => Some(0x20), KeyCode::Digit4 => Some(0x21),
            KeyCode::Digit5 => Some(0x22), KeyCode::Digit6 => Some(0x23),
            KeyCode::Digit7 => Some(0x24), KeyCode::Digit8 => Some(0x25),
            KeyCode::Digit9 => Some(0x26), KeyCode::Digit0 => Some(0x27),
            KeyCode::Enter => Some(0x28), KeyCode::Escape => Some(0x29),
            KeyCode::Backspace => Some(0x2A), KeyCode::Tab => Some(0x2B),
            KeyCode::Space => Some(0x2C), KeyCode::Minus => Some(0x2D),
            KeyCode::Equal => Some(0x2E), KeyCode::BracketLeft => Some(0x2F),
            KeyCode::BracketRight => Some(0x30), KeyCode::Backslash => Some(0x31),
            KeyCode::Semicolon => Some(0x33), KeyCode::Quote => Some(0x34),
            KeyCode::Backquote => Some(0x35), KeyCode::Comma => Some(0x36),
            KeyCode::Period => Some(0x37), KeyCode::Slash => Some(0x38),
            KeyCode::CapsLock => Some(0x39),
            KeyCode::F1 => Some(0x3A), KeyCode::F2 => Some(0x3B),
            KeyCode::F3 => Some(0x3C), KeyCode::F4 => Some(0x3D),
            KeyCode::F5 => Some(0x3E), KeyCode::F6 => Some(0x3F),
            KeyCode::F7 => Some(0x40), KeyCode::F8 => Some(0x41),
            KeyCode::F9 => Some(0x42), KeyCode::F10 => Some(0x43),
            KeyCode::F11 => Some(0x44), KeyCode::F12 => Some(0x45),
            KeyCode::PrintScreen => Some(0x46), KeyCode::ScrollLock => Some(0x47),
            KeyCode::Pause => Some(0x48), KeyCode::Insert => Some(0x49),
            KeyCode::Home => Some(0x4A), KeyCode::PageUp => Some(0x4B),
            KeyCode::Delete => Some(0x4C), KeyCode::End => Some(0x4D),
            KeyCode::PageDown => Some(0x4E), KeyCode::ArrowRight => Some(0x4F),
            KeyCode::ArrowLeft => Some(0x50), KeyCode::ArrowDown => Some(0x51),
            KeyCode::ArrowUp => Some(0x52),
            KeyCode::ControlLeft => Some(0xE0), KeyCode::ShiftLeft => Some(0xE1),
            KeyCode::AltLeft => Some(0xE2), KeyCode::SuperLeft => Some(0xE3),
            KeyCode::ControlRight => Some(0xE4), KeyCode::ShiftRight => Some(0xE5),
            KeyCode::AltRight => Some(0xE6), KeyCode::SuperRight => Some(0xE7),
            _ => None,
        }
    }

"""
keyAnchor = """    /// A key event after rebinding, as the default key of its mapping (the
    /// keyboard and controllers both end here).
"""
replace_once(
    game,
    keyAnchor,
    key_map + keyAnchor,
)
replace_once(
    game,
    "    fn mapped_key(&mut self, key: KeyCode, pressed: bool, repeat: bool) -> Result<()> {\n",
    "    fn mapped_key(&mut self, key: KeyCode, pressed: bool, repeat: bool) -> Result<()> {\n"
    "        // Dispatch only after rebinding, using stable HID codes for the native P1 API.\n"
    "        if !repeat {\n"
    "            if let Some(usage) = Self::fabric_bridge_key_code(key) {\n"
    "                self.mods.mods.key(usage, pressed);\n"
    "            }\n"
    "        }\n",
)
print("Connected registered P1 keyboard bindings to Horizon input dispatch.")
