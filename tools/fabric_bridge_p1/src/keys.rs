//! Keybindings with keyboard, mouse and virtual touch triggers.
use crate::Id;
use std::collections::BTreeMap;

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Trigger {
    Key(u32),
    MouseButton(u8),
    TouchButton(String),
    Unbound,
}
#[derive(Clone, Debug)]
pub struct KeyBinding {
    pub id: Id,
    pub category: String,
    pub default: Trigger,
    pub current: Trigger,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyState { Pressed, Released }

#[derive(Default)]
pub struct KeyRegistry {
    // BTreeMap makes dispatch and conflict reports stable across runs.
    binds: BTreeMap<Id, KeyBinding>,
    down: BTreeMap<Id, bool>,
}
impl KeyRegistry {
    pub fn register(&mut self, id: Id, category: &str, default: Trigger) -> Result<(), String> {
        if category.trim().is_empty() || category.len() > 96 {
            return Err("categoria de tecla vazia ou muito longa".into());
        }
        if let Trigger::TouchButton(name) = &default {
            if name.trim().is_empty() || name.len() > 96 {
                return Err("id de botão touch vazio ou muito longo".into());
            }
        }
        if self.binds.contains_key(&id) {
            return Err(format!("keybinding duplicado: {}", id.as_str()));
        }
        self.binds.insert(id.clone(), KeyBinding {
            id, category: category.into(), current: default.clone(), default,
        });
        Ok(())
    }
    pub fn rebind(&mut self, id: &Id, trigger: Trigger) -> bool {
        let Some(binding) = self.binds.get_mut(id) else { return false; };
        binding.current = trigger;
        // Rebinding while held must not leave a stuck-down state.
        self.down.insert(id.clone(), false);
        true
    }
    /// Caller must suppress dispatch while a text field or menu owns input focus.
    /// Only state transitions are emitted, so key-repeat does not spam callbacks.
    pub fn dispatch(&mut self, trigger: &Trigger, state: KeyState) -> Vec<(Id, KeyState)> {
        let mut out = Vec::new();
        for binding in self.binds.values().filter(|b| b.current == *trigger && b.current != Trigger::Unbound) {
            let down = self.down.entry(binding.id.clone()).or_insert(false);
            let now = state == KeyState::Pressed;
            if *down != now {
                *down = now;
                out.push((binding.id.clone(), state));
            }
        }
        out
    }
    pub fn conflicts(&self) -> Vec<(Id, Id)> {
        let values: Vec<_> = self.binds.values().filter(|b| b.current != Trigger::Unbound).collect();
        let mut conflicts = Vec::new();
        for i in 0..values.len() {
            for j in i + 1..values.len() {
                if values[i].current == values[j].current {
                    conflicts.push((values[i].id.clone(), values[j].id.clone()));
                }
            }
        }
        conflicts
    }
    pub fn bindings(&self) -> impl Iterator<Item = &KeyBinding> { self.binds.values() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn key_repeat_is_ignored_and_conflicts_are_reported() {
        let mut k = KeyRegistry::default();
        let a = Id::parse("demo:a").unwrap();
        let b = Id::parse("demo:b").unwrap();
        let trigger = Trigger::TouchButton("jump".into());
        k.register(a.clone(), "movement", trigger.clone()).unwrap();
        k.register(b.clone(), "movement", trigger.clone()).unwrap();
        assert_eq!(k.dispatch(&trigger, KeyState::Pressed).len(), 2);
        assert!(k.dispatch(&trigger, KeyState::Pressed).is_empty());
        assert_eq!(k.conflicts(), vec![(a, b)]);
    }
}
