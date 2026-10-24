//! APIs nativas adicionais do Fabric Bridge v0.6, mantidas sem hooks de jogo implícitos.
//! São modelos/algoritmos determinísticos para o host ligar aos caminhos autoritativos do Horizon.
use crate::Id;

pub mod transfer {
    use super::Id;

    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Stack { pub id: Id, pub count: u32, pub max_count: u32 }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Slot { pub stack: Option<Stack>, pub capacity: u32 }
    #[derive(Clone, Debug, Default, PartialEq, Eq)]
    pub struct Inventory { pub slots: Vec<Slot> }

    fn move_items(from: &mut Inventory, to: &mut Inventory, max: u32) -> u32 {
        let mut moved = 0;
        for source in &mut from.slots {
            if moved >= max { break; }
            let Some(mut stack) = source.stack.take() else { continue; };
            if stack.count == 0 { continue; }
            for target in &mut to.slots {
                if moved >= max { break; }
                let room = match target.stack.as_ref() {
                    Some(existing) if existing.id == stack.id =>
                        existing.max_count.min(target.capacity).saturating_sub(existing.count),
                    None => target.capacity.min(stack.max_count),
                    _ => 0,
                };
                let amount = room.min(stack.count).min(max - moved);
                if amount == 0 { continue; }
                match target.stack.as_mut() {
                    Some(existing) => existing.count += amount,
                    None => target.stack = Some(Stack {
                        id: stack.id.clone(), count: amount,
                        max_count: stack.max_count,
                    }),
                }
                stack.count -= amount;
                moved += amount;
            }
            if stack.count > 0 { source.stack = Some(stack); }
        }
        moved
    }

    /// Move respects a single global maximum across all source and destination slots.
    pub fn move_items_limited(from: &mut Inventory, to: &mut Inventory, max: u32) -> u32 {
        move_items(from, to, max)
    }

    /// Snapshot-backed transaction: drop rolls back unless commit is explicit.
    pub struct Transaction<'a> {
        from: &'a mut Inventory,
        to: &'a mut Inventory,
        original_from: Inventory,
        original_to: Inventory,
        savepoints: Vec<(Inventory, Inventory)>,
        committed: bool,
    }
    impl<'a> Transaction<'a> {
        pub fn begin(from: &'a mut Inventory, to: &'a mut Inventory) -> Self {
            Self {
                original_from: from.clone(), original_to: to.clone(),
                from, to, savepoints: Vec::new(), committed: false,
            }
        }
        pub fn transfer(&mut self, max: u32) -> u32 {
            move_items(self.from, self.to, max)
        }
        pub fn begin_nested(&mut self) {
            self.savepoints.push((self.from.clone(), self.to.clone()));
        }
        pub fn commit_nested(&mut self) -> Result<(), &'static str> {
            self.savepoints.pop().map(|_| ()).ok_or("no nested savepoint")
        }
        pub fn rollback_nested(&mut self) -> Result<(), &'static str> {
            let (from, to) = self.savepoints.pop().ok_or("no nested savepoint")?;
            *self.from = from;
            *self.to = to;
            Ok(())
        }
        pub fn commit(mut self) -> Result<(), &'static str> {
            if !self.savepoints.is_empty() { return Err("unfinished nested savepoint"); }
            self.committed = true;
            Ok(())
        }
    }
    impl Drop for Transaction<'_> {
        fn drop(&mut self) {
            if !self.committed {
                *self.from = self.original_from.clone();
                *self.to = self.original_to.clone();
            }
        }
    }
}

pub mod permissions {
    use super::Id;
    use std::collections::{BTreeMap, BTreeSet};

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Decision { Allow, Deny, Inherit }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Snapshot {
        pub revision: u64,
        pub defaults: BTreeMap<Id, u8>,
        pub decisions: BTreeMap<u64, BTreeMap<Id, Decision>>,
    }
    #[derive(Default)]
    pub struct Registry {
        defaults: BTreeMap<Id, u8>,
        grants: BTreeMap<u64, BTreeSet<Id>>,
        denies: BTreeMap<u64, BTreeSet<Id>>,
        revision: u64,
    }
    impl Registry {
        pub fn register(&mut self, id: Id, default_level: u8) {
            self.defaults.insert(id, default_level.min(4));
            self.revision = self.revision.wrapping_add(1);
        }
        pub fn check(&self, player: u64, id: &Id, operator_level: u8) -> bool {
            if self.denies.get(&player).is_some_and(|set| set.contains(id)) { return false; }
            if self.grants.get(&player).is_some_and(|set| set.contains(id)) { return true; }
            self.defaults.get(id).is_some_and(|level| operator_level.min(4) >= *level)
        }
        pub fn set(&mut self, player: u64, id: Id, decision: Decision) {
            self.grants.entry(player).or_default().remove(&id);
            self.denies.entry(player).or_default().remove(&id);
            match decision {
                Decision::Allow => { self.grants.entry(player).or_default().insert(id); }
                Decision::Deny => { self.denies.entry(player).or_default().insert(id); }
                Decision::Inherit => {}
            }
            self.revision = self.revision.wrapping_add(1);
        }
        pub fn snapshot(&self) -> Snapshot {
            let mut decisions = BTreeMap::new();
            for (player, ids) in &self.grants {
                for id in ids { decisions.entry(*player).or_insert_with(BTreeMap::new).insert(id.clone(), Decision::Allow); }
            }
            for (player, ids) in &self.denies {
                for id in ids { decisions.entry(*player).or_insert_with(BTreeMap::new).insert(id.clone(), Decision::Deny); }
            }
            Snapshot { revision: self.revision, defaults: self.defaults.clone(), decisions }
        }
        pub fn apply_snapshot(&mut self, snapshot: Snapshot) -> Result<(), String> {
            if snapshot.revision < self.revision { return Err("stale permission snapshot".into()); }
            if snapshot.defaults.values().any(|v| *v > 4) { return Err("permission level must be 0..=4".into()); }
            if snapshot.decisions.values().flat_map(|v| v.keys()).any(|id| !snapshot.defaults.contains_key(id)) {
                return Err("decision references unregistered permission node".into());
            }
            self.defaults = snapshot.defaults;
            self.grants.clear(); self.denies.clear();
            for (player, entries) in snapshot.decisions {
                for (id, decision) in entries {
                    match decision {
                        Decision::Allow => { self.grants.entry(player).or_default().insert(id); }
                        Decision::Deny => { self.denies.entry(player).or_default().insert(id); }
                        Decision::Inherit => {}
                    }
                }
            }
            self.revision = snapshot.revision;
            Ok(())
        }
    }
}

pub mod registry_sync {
    use super::Id;
    use std::collections::BTreeMap;

    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Entry { pub version: u32, pub required: bool }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Manifest { pub revision: u64, pub entries: BTreeMap<Id, Entry> }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Negotiated { pub revision: u64, pub accepted: Vec<Id>, pub rejected_optional: Vec<Id> }

    /// Deterministic negotiation: required mismatches fail; optional mismatches are omitted.
    pub fn negotiate(local: &Manifest, remote: &Manifest, max_entries: usize) -> Result<Negotiated, String> {
        if local.entries.len() > max_entries || remote.entries.len() > max_entries {
            return Err("registry manifest exceeds negotiated entry limit".into());
        }
        let mut accepted = Vec::new();
        let mut rejected_optional = Vec::new();
        for (id, local_entry) in &local.entries {
            match remote.entries.get(id) {
                Some(remote_entry) if remote_entry.version == local_entry.version => accepted.push(id.clone()),
                _ if local_entry.required => return Err(format!("required registry entry incompatible: {}", id.as_str())),
                _ => rejected_optional.push(id.clone()),
            }
        }
        Ok(Negotiated { revision: local.revision.min(remote.revision), accepted, rejected_optional })
    }
}

pub mod attachments {
    use super::Id;
    use std::collections::BTreeMap;

    #[derive(Clone, Debug, PartialEq)]
    pub enum Value { Bool(bool), Integer(i64), Number(f64), Text(String), Bytes(Vec<u8>) }
    impl Value {
        pub fn type_name(&self) -> &'static str {
            match self { Self::Bool(_) => "bool", Self::Integer(_) => "integer", Self::Number(_) => "number", Self::Text(_) => "text", Self::Bytes(_) => "bytes" }
        }
    }
    #[derive(Clone, Debug, PartialEq)]
    pub struct Record { pub owner: Id, pub key: Id, pub value: Value, pub revision: u64 }
    #[derive(Clone, Debug, Default, PartialEq)]
    pub struct Snapshot { pub revision: u64, pub records: Vec<Record> }
    #[derive(Default)]
    pub struct Store { revision: u64, values: BTreeMap<(Id, Id), Record> }
    impl Store {
        pub fn set(&mut self, owner: Id, key: Id, value: Value) -> u64 {
            self.revision = self.revision.wrapping_add(1);
            self.values.insert((owner.clone(), key.clone()), Record { owner, key, value, revision: self.revision });
            self.revision
        }
        pub fn get(&self, owner: &Id, key: &Id) -> Option<&Value> { self.values.get(&(owner.clone(), key.clone())).map(|record| &record.value) }
        pub fn remove(&mut self, owner: &Id, key: &Id) -> bool {
            let removed = self.values.remove(&(owner.clone(), key.clone())).is_some();
            if removed { self.revision = self.revision.wrapping_add(1); }
            removed
        }
        pub fn snapshot(&self) -> Snapshot { Snapshot { revision: self.revision, records: self.values.values().cloned().collect() } }
        pub fn apply_snapshot(&mut self, snapshot: Snapshot) -> Result<(), String> {
            if snapshot.revision < self.revision { return Err("stale attachment snapshot".into()); }
            let mut values = BTreeMap::new();
            for record in snapshot.records {
                if record.revision > snapshot.revision { return Err("attachment record revision exceeds snapshot".into()); }
                let key = (record.owner.clone(), record.key.clone());
                if values.insert(key, record).is_some() { return Err("duplicate attachment owner/key".into()); }
            }
            self.values = values; self.revision = snapshot.revision; Ok(())
        }
    }
}

pub mod resources {
    use super::Id;
    use std::collections::BTreeMap;

    #[derive(Clone, Debug)]
    pub struct Pack { pub owner: Id, pub priority: i32, pub entries: BTreeMap<String, Vec<u8>> }
    #[derive(Clone, Debug, Default, PartialEq, Eq)]
    pub struct ReloadReport { pub resources: BTreeMap<String, Vec<u8>>, pub applied_packs: Vec<Id>, pub shadowed_entries: usize }
    #[derive(Default)]
    pub struct Manager { packs: BTreeMap<Id, Pack>, revision: u64 }
    impl Manager {
        pub fn insert(&mut self, pack: Pack) -> Result<(), String> {
            if pack.entries.keys().any(|path| path.is_empty() || path.starts_with('/') || path.split('/').any(|p| p == ".." || p.is_empty())) {
                return Err("resource path is absolute or traverses its pack root".into());
            }
            self.packs.insert(pack.owner.clone(), pack); self.revision = self.revision.wrapping_add(1); Ok(())
        }
        pub fn remove(&mut self, owner: &Id) -> bool {
            let removed = self.packs.remove(owner).is_some();
            if removed { self.revision = self.revision.wrapping_add(1); }
            removed
        }
        pub fn revision(&self) -> u64 { self.revision }
        /// Higher priority wins; ties are resolved by namespaced owner ID for determinism.
        pub fn reload(&self) -> ReloadReport {
            let mut packs: Vec<_> = self.packs.values().collect();
            packs.sort_by(|a,b| a.priority.cmp(&b.priority).then_with(|| a.owner.cmp(&b.owner)));
            let mut report = ReloadReport::default();
            for pack in packs {
                report.applied_packs.push(pack.owner.clone());
                for (path, bytes) in &pack.entries {
                    if report.resources.insert(path.clone(), bytes.clone()).is_some() { report.shadowed_entries += 1; }
                }
            }
            report
        }
    }
}


/// Declarative vanilla recipes and a deterministic RecipeBook.
pub mod recipes {
    use super::Id;
    use std::collections::{BTreeMap, BTreeSet};

    #[derive(Clone, Debug, PartialEq, Eq)]
    pub enum Ingredient { Item(Id), Tag(Id) }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct ItemStack { pub id: Id, pub count: u32 }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub enum Recipe {
        Shaped { id: Id, width: u8, height: u8, pattern: Vec<Option<Ingredient>>, result: ItemStack },
        Shapeless { id: Id, ingredients: Vec<Ingredient>, result: ItemStack },
    }
    impl Recipe {
        pub fn id(&self) -> &Id { match self { Self::Shaped { id, .. } | Self::Shapeless { id, .. } => id } }
        pub fn result(&self) -> &ItemStack { match self { Self::Shaped { result, .. } | Self::Shapeless { result, .. } => result } }
        pub fn validate(&self) -> Result<(), String> {
            if self.result().count == 0 || self.result().count > 99 { return Err("recipe result count must be 1..=99".into()); }
            match self {
                Self::Shaped { width, height, pattern, .. } => {
                    if *width == 0 || *height == 0 || *width > 3 || *height > 3 || pattern.len() != (*width as usize) * (*height as usize) || pattern.iter().all(Option::is_none) {
                        return Err("shaped recipe must have a non-empty pattern no larger than 3x3".into());
                    }
                }
                Self::Shapeless { ingredients, .. } => {
                    if ingredients.is_empty() || ingredients.len() > 9 { return Err("shapeless recipe must have 1..=9 ingredients".into()); }
                }
            }
            Ok(())
        }
        pub fn matches(&self, grid: &[Option<Id>], grid_width: u8, grid_height: u8, tags: &BTreeMap<Id, BTreeSet<Id>>) -> bool {
            if grid_width == 0 || grid_height == 0 || grid.len() != grid_width as usize * grid_height as usize { return false; }
            match self {
                Self::Shaped { width, height, pattern, .. } => {
                    if *width > grid_width || *height > grid_height { return false; }
                    for oy in 0..=(grid_height - *height) {
                        for ox in 0..=(grid_width - *width) {
                            for mirrored in [false, true] {
                                let mut valid = true;
                                for y in 0..grid_height {
                                    for x in 0..grid_width {
                                        let in_pattern = x >= ox && x < ox + *width && y >= oy && y < oy + *height;
                                        let item = grid[y as usize * grid_width as usize + x as usize].as_ref();
                                        if !in_pattern {
                                            if item.is_some() { valid = false; }
                                            continue;
                                        }
                                        let px = x - ox;
                                        let px = if mirrored { *width - 1 - px } else { px };
                                        let ingredient = pattern[(y - oy) as usize * *width as usize + px as usize].as_ref();
                                        if !ingredient_matches(ingredient, item, tags) { valid = false; }
                                    }
                                }
                                if valid { return true; }
                            }
                        }
                    }
                    false
                }
                Self::Shapeless { ingredients, .. } => {
                    let items: Vec<&Id> = grid.iter().filter_map(Option::as_ref).collect();
                    if items.len() != ingredients.len() { return false; }
                    let mut used = vec![false; items.len()];
                    match_shapeless(ingredients, &items, tags, 0, &mut used)
                }
            }
        }
    }
    fn ingredient_matches(ingredient: Option<&Ingredient>, item: Option<&Id>, tags: &BTreeMap<Id, BTreeSet<Id>>) -> bool {
        match (ingredient, item) {
            (None, None) => true,
            (Some(Ingredient::Item(expected)), Some(actual)) => expected == actual,
            (Some(Ingredient::Tag(tag)), Some(actual)) => tags.get(tag).is_some_and(|members| members.contains(actual)),
            _ => false,
        }
    }
    fn match_shapeless(ingredients: &[Ingredient], items: &[&Id], tags: &BTreeMap<Id, BTreeSet<Id>>, index: usize, used: &mut [bool]) -> bool {
        if index == ingredients.len() { return true; }
        for i in 0..items.len() {
            if used[i] { continue; }
            if ingredient_matches(Some(&ingredients[index]), Some(items[i]), tags) {
                used[i] = true;
                if match_shapeless(ingredients, items, tags, index + 1, used) { return true; }
                used[i] = false;
            }
        }
        false
    }
    #[derive(Default)]
    pub struct RecipeBook { recipes: BTreeMap<Id, Recipe> }
    impl RecipeBook {
        pub fn register(&mut self, recipe: Recipe) -> Result<(), String> {
            recipe.validate()?;
            if self.recipes.contains_key(recipe.id()) { return Err(format!("duplicate recipe {}", recipe.id().as_str())); }
            self.recipes.insert(recipe.id().clone(), recipe);
            Ok(())
        }
        pub fn get(&self, id: &Id) -> Option<&Recipe> { self.recipes.get(id) }
        pub fn matching(&self, grid: &[Option<Id>], width: u8, height: u8, tags: &BTreeMap<Id, BTreeSet<Id>>) -> Vec<&Recipe> {
            self.recipes.values().filter(|recipe| recipe.matches(grid, width, height, tags)).collect()
        }
        pub fn ids(&self) -> impl Iterator<Item = &Id> { self.recipes.keys() }
    }
}

/// Safe declarative subset of vanilla loot conditions/functions. Arbitrary Java callbacks must be ported.
pub mod loot {
    use super::Id;
    use std::collections::BTreeSet;

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Weather { Clear, Rain, Thunder }
    #[derive(Clone, Debug, Default)]
    pub struct Context { pub weather: Option<Weather>, pub entity_tags: BTreeSet<Id> }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub enum Condition { Always, ChanceBasisPoints(u16), Weather(Weather), EntityTag(Id) }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub enum Function { SetCount { min: u32, max: u32 }, AddCount(u32), MultiplyCount { numerator: u32, denominator: u32 } }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Entry { pub item: Id, pub weight: u32, pub conditions: Vec<Condition>, pub functions: Vec<Function>, pub max_stack: u32 }
    #[derive(Clone, Debug, Default, PartialEq, Eq)]
    pub struct Pool { pub rolls: u8, pub entries: Vec<Entry> }
    #[derive(Clone, Debug, Default, PartialEq, Eq)]
    pub struct Table { pub pools: Vec<Pool> }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Drop { pub item: Id, pub count: u32 }
    #[derive(Clone, Copy)]
    struct Rng(u64);
    impl Rng {
        fn next(&mut self) -> u64 { self.0 ^= self.0 << 13; self.0 ^= self.0 >> 7; self.0 ^= self.0 << 17; self.0 }
        fn below(&mut self, upper: u64) -> u64 { if upper == 0 { 0 } else { self.next() % upper } }
    }
    impl Table {
        pub fn validate(&self) -> Result<(), String> {
            if self.pools.len() > 64 { return Err("loot table has more than 64 pools".into()); }
            for pool in &self.pools {
                if pool.entries.len() > 256 { return Err("loot pool has more than 256 entries".into()); }
                for entry in &pool.entries {
                    if entry.weight == 0 || entry.max_stack == 0 || entry.max_stack > 99 { return Err("loot entry requires positive weight and max_stack 1..=99".into()); }
                    for f in &entry.functions {
                        match f {
                            Function::SetCount { min, max } if min == &0 || min > max || *max > entry.max_stack => return Err("invalid loot set-count range".into()),
                            Function::MultiplyCount { denominator: 0, .. } => return Err("loot multiplier denominator cannot be zero".into()),
                            _ => {}
                        }
                    }
                }
            }
            Ok(())
        }
        pub fn roll(&self, seed: u64, context: &Context) -> Result<Vec<Drop>, String> {
            self.validate()?;
            let mut rng = Rng(if seed == 0 { 0x9e37_79b9_7f4a_7c15 } else { seed });
            let mut output = Vec::new();
            for pool in &self.pools {
                for _ in 0..pool.rolls {
                    let mut eligible: Vec<&Entry> = Vec::new();
                    let mut total = 0u64;
                    for entry in &pool.entries {
                        if entry.conditions.iter().all(|c| condition_matches(c, context, &mut rng)) {
                            total = total.checked_add(entry.weight as u64).ok_or("loot weights overflow")?;
                            eligible.push(entry);
                        }
                    }
                    if total == 0 { continue; }
                    let chosen = rng.below(total);
                    let mut cursor = 0u64;
                    let entry = eligible.into_iter().find(|e| { cursor += e.weight as u64; chosen < cursor }).expect("weight selection is bounded");
                    let mut count = 1u32;
                    for function in &entry.functions {
                        count = match function {
                            Function::SetCount { min, max } => min + rng.below((*max - *min + 1) as u64) as u32,
                            Function::AddCount(add) => count.saturating_add(*add),
                            Function::MultiplyCount { numerator, denominator } => count.saturating_mul(*numerator) / *denominator,
                        }.clamp(1, entry.max_stack);
                    }
                    output.push(Drop { item: entry.item.clone(), count });
                    if output.len() > 1024 { return Err("loot output exceeded safety cap".into()); }
                }
            }
            Ok(output)
        }
    }
    fn condition_matches(condition: &Condition, context: &Context, rng: &mut Rng) -> bool {
        match condition {
            Condition::Always => true,
            Condition::ChanceBasisPoints(bp) => rng.below(10_000) < (*bp as u64).min(10_000),
            Condition::Weather(expected) => context.weather == Some(*expected),
            Condition::EntityTag(tag) => context.entity_tags.contains(tag),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{attachments, permissions, registry_sync, resources, transfer};
    use crate::Id;
    use std::collections::BTreeMap;
    fn id(s: &str) -> Id { Id::parse(s).unwrap() }

    #[test]
    fn transfer_limit_is_global_across_slots_and_transaction_rolls_back() {
        let mut from = transfer::Inventory { slots: vec![transfer::Slot {
            stack: Some(transfer::Stack { id: id("minecraft:stone"), count: 20, max_count: 64 }), capacity: 64,
        }]};
        let mut to = transfer::Inventory { slots: vec![
            transfer::Slot { stack: None, capacity: 4 },
            transfer::Slot { stack: None, capacity: 4 },
        ]};
        {
            let mut tx = transfer::Transaction::begin(&mut from, &mut to);
            assert_eq!(tx.transfer(5), 5);
        }
        assert_eq!(from.slots[0].stack.as_ref().unwrap().count, 20);
        assert!(to.slots.iter().all(|slot| slot.stack.is_none()));
        assert_eq!(transfer::move_items_limited(&mut from, &mut to, 5), 5);
        assert_eq!(to.slots.iter().map(|slot| slot.stack.as_ref().map_or(0, |s| s.count)).sum::<u32>(), 5);
    }

    #[test]
    fn permission_defaults_and_explicit_denies_are_respected() {
        let mut registry = permissions::Registry::default();
        registry.register(id("m:basic"), 0);
        registry.register(id("m:admin"), 2);
        assert!(registry.check(1, &id("m:basic"), 0));
        assert!(!registry.check(1, &id("m:admin"), 0));
        registry.set(1, id("m:basic"), permissions::Decision::Deny);
        assert!(!registry.check(1, &id("m:basic"), 4));
        let snap = registry.snapshot();
        let mut replica = permissions::Registry::default();
        replica.apply_snapshot(snap).unwrap();
        assert!(!replica.check(1, &id("m:basic"), 4));
    }

    #[test]
    fn required_registry_mismatch_fails_but_optional_is_reported() {
        let mut local = registry_sync::Manifest { revision: 4, entries: BTreeMap::new() };
        let mut remote = registry_sync::Manifest { revision: 3, entries: BTreeMap::new() };
        local.entries.insert(id("m:required"), registry_sync::Entry { version: 1, required: true });
        remote.entries.insert(id("m:required"), registry_sync::Entry { version: 1, required: true });
        local.entries.insert(id("m:optional"), registry_sync::Entry { version: 1, required: false });
        assert!(registry_sync::negotiate(&local, &remote, 32).unwrap().accepted.contains(&id("m:required")));
        assert!(registry_sync::negotiate(&local, &remote, 32).unwrap().rejected_optional.contains(&id("m:optional")));
        remote.entries.remove(&id("m:required"));
        assert!(registry_sync::negotiate(&local, &remote, 32).is_err());
    }

    #[test]
    fn attachment_snapshots_reject_stale_revisions() {
        let mut store = attachments::Store::default();
        store.set(id("m:player"), id("m:coins"), attachments::Value::Integer(12));
        let snapshot = store.snapshot();
        assert_eq!(store.get(&id("m:player"), &id("m:coins")), Some(&attachments::Value::Integer(12)));
        let mut replica = attachments::Store::default();
        replica.apply_snapshot(snapshot.clone()).unwrap();
        assert_eq!(replica.get(&id("m:player"), &id("m:coins")), Some(&attachments::Value::Integer(12)));
        store.set(id("m:player"), id("m:coins"), attachments::Value::Integer(13));
        assert!(store.apply_snapshot(snapshot).is_err());
    }

    #[test]
    fn resource_priority_is_deterministic_and_paths_are_confined() {
        let mut manager = resources::Manager::default();
        manager.insert(resources::Pack { owner:id("m:low"), priority:0, entries:BTreeMap::from([("assets/m/x.png".into(),vec![1])]) }).unwrap();
        manager.insert(resources::Pack { owner:id("m:high"), priority:10, entries:BTreeMap::from([("assets/m/x.png".into(),vec![2])]) }).unwrap();
        assert_eq!(manager.reload().resources["assets/m/x.png"], vec![2]);
        assert_eq!(manager.reload().shadowed_entries, 1);
        assert!(manager.insert(resources::Pack { owner:id("m:bad"), priority:0, entries:BTreeMap::from([("../escape".into(),vec![])]) }).is_err());
    }
}
