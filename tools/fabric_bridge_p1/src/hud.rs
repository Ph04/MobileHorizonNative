//! Declarative HUD widgets; arbitrary draw calls remain a separate, future capability.
use crate::Id;
use std::collections::BTreeMap;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum Anchor { TopLeft, TopRight, BottomLeft, BottomRight, TopCenter, BottomCenter }
#[derive(Clone, Debug, PartialEq)]
pub enum Element {
    Text { text: String, rgba: u32, shadow: bool },
    Bar { value: f32, max: f32, rgba: u32, width: u16 },
    ItemIcon { item: Id, count: Option<u32> },
}
#[derive(Clone, Debug, PartialEq)]
pub struct Widget {
    pub owner: Id,
    pub anchor: Anchor,
    pub offset: (i16, i16),
    pub visible: bool,
    pub rows: Vec<Element>,
}
pub const MAX_ROWS: usize = 16;
pub const MAX_TEXT: usize = 256;
pub const MAX_WIDGETS_PER_MOD: usize = 8;
const MAX_WIDGET_KEY: usize = 64;

#[derive(Default)]
pub struct HudState { widgets: BTreeMap<(Id, String), Widget> }
impl HudState {
    pub fn set(&mut self, owner: &Id, key: &str, mut widget: Widget) -> Result<(), String> {
        if key.is_empty() || key.len() > MAX_WIDGET_KEY
            || !key.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"_.-".contains(&b)) {
            return Err("chave de widget inválida".into());
        }
        if widget.rows.len() > MAX_ROWS { return Err(format!("máximo de {MAX_ROWS} linhas")); }
        for row in &mut widget.rows {
            match row {
                Element::Text { text, .. } => truncate_utf8(text, MAX_TEXT),
                Element::Bar { value, max, .. } => {
                    if !max.is_finite() || *max <= 0.0 || !value.is_finite() {
                        return Err("barra HUD requer valor finito e máximo maior que zero".into());
                    }
                    *value = value.clamp(0.0, *max);
                }
                Element::ItemIcon { .. } => {}
            }
        }
        let key = (owner.clone(), key.to_owned());
        let current_count = self.widgets.keys().filter(|(id, _)| id == owner).count();
        if !self.widgets.contains_key(&key) && current_count >= MAX_WIDGETS_PER_MOD {
            return Err(format!("máximo de {MAX_WIDGETS_PER_MOD} widgets por mod"));
        }
        widget.owner = owner.clone();
        self.widgets.insert(key, widget);
        Ok(())
    }

    pub fn remove(&mut self, owner: &Id, key: &str) { self.widgets.remove(&(owner.clone(), key.to_owned())); }
    pub fn clear_mod(&mut self, owner: &Id) { self.widgets.retain(|(id, _), _| id != owner); }
    /// BTreeMap yields a deterministic owner/key order for painting and snapshots.
    pub fn snapshot(&self) -> impl Iterator<Item = &Widget> {
        self.widgets.values().filter(|widget| widget.visible)
    }
}

fn truncate_utf8(text: &mut String, max_bytes: usize) {
    if text.len() <= max_bytes { return; }
    let mut boundary = max_bytes;
    while !text.is_char_boundary(boundary) { boundary -= 1; }
    text.truncate(boundary);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn widget_limits_do_not_panic_on_unicode_or_invalid_bars() {
        let owner = Id::parse("demo:hud").unwrap();
        let mut hud = HudState::default();
        let mut widget = Widget {
            owner: owner.clone(), anchor: Anchor::TopLeft, offset: (0, 0), visible: true,
            rows: vec![Element::Text { text: "é".repeat(200), rgba: 0xffffffff, shadow: true }],
        };
        hud.set(&owner, "status", widget.clone()).unwrap();
        assert!(hud.snapshot().next().unwrap().rows.len() == 1);
        widget.rows = vec![Element::Bar { value: 0.5, max: -1.0, rgba: 0xffffffff, width: 20 }];
        assert!(hud.set(&owner, "broken", widget).is_err());
    }
}
