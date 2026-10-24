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
                _ if local_entry.required => return Err(format!("required local registry entry incompatible: {}", id.as_str())),
                _ => rejected_optional.push(id.clone()),
            }
        }
        for (id, remote_entry) in &remote.entries {
            if remote_entry.required && !local.entries.contains_key(id) {
                return Err(format!("server requires unknown registry entry: {}", id.as_str()));
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
            if pack.entries.keys().any(|path| path.is_empty() || path.starts_with('/') || path.contains('\\') || path.split('/').any(|p| p == ".." || p == "." || p.is_empty())) {
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
                    if entry.conditions.iter().any(|condition| matches!(condition, Condition::ChanceBasisPoints(bp) if *bp > 10_000)) {
                        return Err("loot chance must be in basis points 0..=10000".into());
                    }
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
                            Function::SetCount { min, max } => *min + rng.below((*max - *min + 1) as u64) as u32,
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


/// Declarative biome modifier pipeline. Actual chunk/worldgen injection belongs to the host.
pub mod biomes {
    use super::Id;
    use std::collections::BTreeMap;

    #[derive(Clone, Debug, PartialEq)]
    pub struct SpawnRule { pub entity: Id, pub weight: u32, pub min_group: u8, pub max_group: u8 }
    #[derive(Clone, Debug, PartialEq)]
    pub enum Modifier {
        AddFeature { feature: Id, step: String },
        AddSpawn(SpawnRule),
        SetTemperature(f32),
        SetDownfall(f32),
    }
    #[derive(Clone, Debug, Default, PartialEq)]
    pub struct Snapshot { pub features: Vec<(String, Id)>, pub spawns: Vec<SpawnRule>, pub temperature: Option<f32>, pub downfall: Option<f32> }
    #[derive(Default)]
    pub struct Registry { by_biome: BTreeMap<Id, Vec<Modifier>> }
    impl Registry {
        pub fn add(&mut self, biome: Id, modifier: Modifier) -> Result<(), String> {
            match &modifier {
                Modifier::AddFeature { step, .. } if step.trim().is_empty() || step.len() > 64 => return Err("invalid feature generation step".into()),
                Modifier::AddSpawn(rule) if rule.weight == 0 || rule.min_group == 0 || rule.max_group < rule.min_group => return Err("invalid biome spawn rule".into()),
                Modifier::SetTemperature(v) if !v.is_finite() || !(-2.0..=2.0).contains(v) => return Err("temperature must be finite and within -2..=2".into()),
                Modifier::SetDownfall(v) if !v.is_finite() || !(0.0..=1.0).contains(v) => return Err("downfall must be finite and within 0..=1".into()),
                _ => {}
            }
            let entries = self.by_biome.entry(biome).or_default();
            if entries.len() >= 1024 { return Err("biome modifier limit reached".into()); }
            entries.push(modifier); Ok(())
        }
        /// Produces a deterministic patch; host applies it during authoritative world generation.
        pub fn apply_to_snapshot(&self, biome: &Id, mut base: Snapshot) -> Snapshot {
            if let Some(modifiers) = self.by_biome.get(biome) {
                for modifier in modifiers {
                    match modifier {
                        Modifier::AddFeature { feature, step } => base.features.push((step.clone(), feature.clone())),
                        Modifier::AddSpawn(rule) => base.spawns.push(rule.clone()),
                        Modifier::SetTemperature(value) => base.temperature = Some(*value),
                        Modifier::SetDownfall(value) => base.downfall = Some(*value),
                    }
                }
            }
            base.features.sort_by(|a,b| a.0.cmp(&b.0).then_with(|| a.1.cmp(&b.1)));
            base
        }
        pub fn biome_count(&self) -> usize { self.by_biome.len() }
    }
}

/// Typed registries and vanilla-style data maps; custom Java behavior is intentionally not representable.
pub mod content_registries {
    use super::Id;
    use std::collections::BTreeMap;
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Definition<T> { pub id: Id, pub value: T }
    #[derive(Default)]
    pub struct Registry<T> { entries: BTreeMap<Id, T>, frozen: bool, revision: u64 }
    impl<T> Registry<T> {
        pub fn register(&mut self, id: Id, value: T) -> Result<u64, String> {
            if self.frozen { return Err("registry is frozen".into()); }
            if self.entries.contains_key(&id) { return Err(format!("duplicate registry id {}", id.as_str())); }
            self.entries.insert(id, value);
            self.revision = self.revision.wrapping_add(1);
            Ok(self.revision)
        }
        pub fn get(&self, id: &Id) -> Option<&T> { self.entries.get(id) }
        pub fn freeze(&mut self) { self.frozen = true; }
        pub fn is_frozen(&self) -> bool { self.frozen }
        pub fn revision(&self) -> u64 { self.revision }
        pub fn iter(&self) -> impl Iterator<Item=(&Id, &T)> { self.entries.iter() }
    }
    #[derive(Default)]
    pub struct DataMap<T> { entries: BTreeMap<Id, T>, revision: u64 }
    impl<T> DataMap<T> {
        pub fn set(&mut self, key: Id, value: T) -> u64 {
            self.entries.insert(key, value); self.revision = self.revision.wrapping_add(1); self.revision
        }
        pub fn get(&self, key: &Id) -> Option<&T> { self.entries.get(key) }
        pub fn remove(&mut self, key: &Id) -> bool {
            let removed = self.entries.remove(key).is_some();
            if removed { self.revision = self.revision.wrapping_add(1); }
            removed
        }
        pub fn revision(&self) -> u64 { self.revision }
        pub fn iter(&self) -> impl Iterator<Item=(&Id, &T)> { self.entries.iter() }
    }
}

/// Bounded declarative object-builder values and schemas. Native serializers are supplied by explicit ports.
pub mod object_builder {
    use std::collections::BTreeMap;
    #[derive(Clone, Debug, PartialEq)]
    pub enum Value { Null, Bool(bool), Integer(i64), Number(f64), Text(String), Array(Vec<Value>), Object(BTreeMap<String, Value>) }
    #[derive(Clone, Debug, PartialEq)]
    pub enum Kind { Any, Bool, Integer { min: i64, max: i64 }, Number { min: f64, max: f64 }, Text { max_bytes: usize }, Array { item: Box<Kind>, max_items: usize }, Object { fields: BTreeMap<String, Kind>, allow_unknown: bool } }
    pub fn validate(value: &Value, kind: &Kind, max_depth: usize, max_nodes: usize) -> Result<(), String> {
        let mut nodes = 0usize;
        validate_inner(value, kind, 0, max_depth, max_nodes, &mut nodes)
    }
    fn validate_inner(value: &Value, kind: &Kind, depth: usize, max_depth: usize, max_nodes: usize, nodes: &mut usize) -> Result<(), String> {
        *nodes = nodes.saturating_add(1);
        if *nodes > max_nodes { return Err("object-builder node budget exceeded".into()); }
        if depth > max_depth { return Err("object-builder nesting limit exceeded".into()); }
        match (value, kind) {
            (_, Kind::Any) | (Value::Null, _) => Ok(()),
            (Value::Bool(_), Kind::Bool) => Ok(()),
            (Value::Integer(v), Kind::Integer { min, max }) if min <= v && v <= max => Ok(()),
            (Value::Number(v), Kind::Number { min, max }) if v.is_finite() && min.is_finite() && max.is_finite() && min <= v && v <= max => Ok(()),
            (Value::Text(v), Kind::Text { max_bytes }) if v.len() <= *max_bytes => Ok(()),
            (Value::Array(items), Kind::Array { item, max_items }) if items.len() <= *max_items => {
                for child in items { validate_inner(child, item, depth + 1, max_depth, max_nodes, nodes)?; } Ok(())
            }
            (Value::Object(fields), Kind::Object { fields: schema, allow_unknown }) => {
                if !allow_unknown && fields.keys().any(|key| !schema.contains_key(key)) { return Err("object contains unknown field".into()); }
                for (key, child) in fields {
                    if let Some(child_schema) = schema.get(key) { validate_inner(child, child_schema, depth + 1, max_depth, max_nodes, nodes)?; }
                    else if !allow_unknown { return Err(format!("unknown object field {key}")); }
                    else { validate_inner(child, &Kind::Any, depth + 1, max_depth, max_nodes, nodes)?; }
                }
                Ok(())
            }
            _ => Err("object-builder value does not match schema".into()),
        }
    }
}

/// Dimension metadata registry. A registered generator ID is only a host hook request.
pub mod dimensions {
    use super::Id;
    use std::collections::BTreeMap;
    #[derive(Clone, Debug, PartialEq)]
    pub struct Definition { pub id: Id, pub generator: Id, pub effects: Id, pub has_ceiling: bool, pub ambient_light: f32, pub logical_height: u16, pub coordinate_scale: f64 }
    #[derive(Default)]
    pub struct Registry { entries: BTreeMap<Id, Definition>, frozen: bool }
    impl Registry {
        pub fn register(&mut self, definition: Definition) -> Result<(), String> {
            if self.frozen { return Err("dimension registry is frozen".into()); }
            if !definition.ambient_light.is_finite() || !(0.0..=1.0).contains(&definition.ambient_light)
                || definition.logical_height == 0 || definition.logical_height > 4096
                || !definition.coordinate_scale.is_finite() || definition.coordinate_scale <= 0.0 || definition.coordinate_scale > 1.0e6 {
                return Err("invalid dimension numeric properties".into());
            }
            if self.entries.contains_key(&definition.id) { return Err(format!("duplicate dimension {}", definition.id.as_str())); }
            self.entries.insert(definition.id.clone(), definition); Ok(())
        }
        pub fn get(&self, id: &Id) -> Option<&Definition> { self.entries.get(id) }
        pub fn freeze(&mut self) { self.frozen = true; }
    }
}

/// Chat pipeline that explicitly records whether modifications invalidate an upstream signature.
pub mod messages {
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Signature { pub bytes: Vec<u8>, pub algorithm: String }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Message { pub sender: Option<String>, pub text: String, pub signature: Option<Signature>, pub modified: bool, pub blocked: bool }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub enum Action { Pass, Modify(String), Block }
    pub fn apply_pipeline(mut message: Message, actions: impl IntoIterator<Item=Action>, max_bytes: usize) -> Result<Message, String> {
        if message.text.len() > max_bytes { return Err("incoming message exceeds text budget".into()); }
        for action in actions {
            if message.blocked { break; }
            match action {
                Action::Pass => {}
                Action::Modify(text) => {
                    if text.len() > max_bytes { return Err("modified message exceeds text budget".into()); }
                    if text != message.text { message.text = text; message.modified = true; }
                }
                Action::Block => message.blocked = true,
            }
        }
        // Do not pretend a changed signed message remains cryptographically valid.
        if message.modified { message.signature = None; }
        Ok(message)
    }
}

/// Bounded, allowlisted custom payload channels; actual packet transport belongs to the host.
pub mod networking {
    use super::Id;
    use std::collections::BTreeMap;
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Direction { ClientToServer, ServerToClient, Bidirectional }
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Channel { pub direction: Direction, pub max_payload: usize, pub max_messages_per_window: u32 }
    #[derive(Default)]
    pub struct Registry { channels: BTreeMap<Id, Channel> }
    impl Registry {
        pub fn register(&mut self, id: Id, channel: Channel) -> Result<(), String> {
            if channel.max_payload == 0 || channel.max_payload > 1_048_576 || channel.max_messages_per_window == 0 || channel.max_messages_per_window > 10_000 {
                return Err("invalid network channel limits".into());
            }
            if self.channels.len() >= 1024 { return Err("network channel registration limit reached".into()); }
            if self.channels.insert(id.clone(), channel).is_some() { return Err(format!("duplicate network channel {}", id.as_str())); }
            Ok(())
        }
        pub fn validate_payload(&self, id: &Id, direction: Direction, size: usize) -> Result<(), String> {
            let channel = self.channels.get(id).ok_or_else(|| format!("unregistered network channel {}", id.as_str()))?;
            let direction_ok = channel.direction == Direction::Bidirectional || channel.direction == direction;
            if !direction_ok { return Err("network channel direction is not permitted".into()); }
            if size > channel.max_payload { return Err("network payload exceeds channel limit".into()); }
            Ok(())
        }
    }
}

/// Backend-neutral mesh validation and per-frame draw budgeting.
pub mod render {
    #[derive(Clone, Copy, Debug, PartialEq)]
    pub struct Vertex { pub position: [f32; 3], pub normal: [f32; 3], pub uv: [f32; 2] }
    #[derive(Clone, Debug, Default, PartialEq)]
    pub struct Mesh { pub vertices: Vec<Vertex>, pub indices: Vec<u32> }
    impl Mesh {
        pub fn validate(&self, max_vertices: usize, max_indices: usize) -> Result<(), String> {
            if self.vertices.len() > max_vertices || self.indices.len() > max_indices { return Err("mesh exceeds geometry budget".into()); }
            if self.indices.len() % 3 != 0 { return Err("triangle mesh index count must be divisible by three".into()); }
            if self.indices.iter().any(|i| *i as usize >= self.vertices.len()) { return Err("mesh index references missing vertex".into()); }
            for v in &self.vertices {
                if v.position.iter().chain(v.normal.iter()).chain(v.uv.iter()).any(|x| !x.is_finite()) { return Err("mesh contains non-finite vertex data".into()); }
            }
            Ok(())
        }
    }
    #[derive(Clone, Debug)]
    pub struct DrawBudget { remaining: usize }
    impl DrawBudget {
        pub fn new(max_commands: usize) -> Self { Self { remaining: max_commands } }
        pub fn consume(&mut self, count: usize) -> Result<(), String> {
            self.remaining = self.remaining.checked_sub(count).ok_or("render command budget exceeded")?;
            Ok(())
        }
        pub fn remaining(&self) -> usize { self.remaining }
    }
}

/// Declarative effects. The host must enqueue emissions into its real particle/audio engine.
pub mod effects {
    use super::Id;
    #[derive(Clone, Debug, PartialEq)]
    pub struct Particle { pub id: Id, pub count: u16, pub spread: [f32; 3], pub speed: f32 }
    #[derive(Clone, Debug, PartialEq)]
    pub struct Sound { pub id: Id, pub volume: f32, pub pitch: f32, pub category: String }
    impl Particle {
        pub fn validate(&self) -> Result<(), String> {
            if self.count == 0 || self.count > 4096 || self.spread.iter().any(|v| !v.is_finite() || *v < 0.0 || *v > 128.0) || !self.speed.is_finite() || !(0.0..=16.0).contains(&self.speed) {
                return Err("invalid particle emission parameters".into());
            }
            Ok(())
        }
    }
    impl Sound {
        pub fn validate(&self) -> Result<(), String> {
            if !self.volume.is_finite() || !(0.0..=16.0).contains(&self.volume)
                || !self.pitch.is_finite() || !(0.01..=4.0).contains(&self.pitch)
                || self.category.is_empty() || self.category.len() > 64 {
                return Err("invalid sound parameters".into());
            }
            Ok(())
        }
    }
}

/// Bounded debug primitives, rendered only when the host exposes a debug overlay.
pub mod debug {
    #[derive(Clone, Copy, Debug, PartialEq)]
    pub struct Line { pub from: [f32; 3], pub to: [f32; 3], pub rgba: u32 }
    #[derive(Default)]
    pub struct Overlay { lines: Vec<Line> }
    impl Overlay {
        pub fn push(&mut self, line: Line, max_lines: usize) -> Result<(), String> {
            if line.from.iter().chain(line.to.iter()).any(|v| !v.is_finite()) { return Err("debug line has non-finite coordinates".into()); }
            if self.lines.len() >= max_lines { return Err("debug overlay budget exceeded".into()); }
            self.lines.push(line); Ok(())
        }
        pub fn lines(&self) -> &[Line] { &self.lines }
        pub fn clear(&mut self) { self.lines.clear(); }
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
        local.entries.remove(&id("m:required"));
        remote.entries.insert(id("server:mandatory"), registry_sync::Entry { version: 1, required: true });
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
        assert!(manager.insert(resources::Pack { owner:id("m:dot"), priority:0, entries:BTreeMap::from([("assets/./escape".into(),vec![])]) }).is_err());
        assert!(manager.insert(resources::Pack { owner:id("m:windows"), priority:0, entries:BTreeMap::from([("assets\\..\\escape".into(),vec![])]) }).is_err());
    }
}


#[cfg(test)]
mod recipes_loot_tests {
    use super::{loot, recipes};
    use crate::Id;
    use std::collections::{BTreeMap, BTreeSet};
    fn id(s: &str) -> Id { Id::parse(s).unwrap() }

    #[test]
    fn shaped_recipe_matches_offset_and_mirror_and_rejects_extra_items() {
        let recipe = recipes::Recipe::Shaped {
            id: id("m:stone_pair"), width: 2, height: 1,
            pattern: vec![
                Some(recipes::Ingredient::Item(id("minecraft:stone"))),
                Some(recipes::Ingredient::Item(id("minecraft:stone"))),
            ],
            result: recipes::ItemStack { id: id("m:stone_pair"), count: 1 },
        };
        let grid = vec![None, None, None, Some(id("minecraft:stone")), Some(id("minecraft:stone")), None, None, None, None];
        assert!(recipe.matches(&grid, 3, 3, &BTreeMap::new()));
        let mut extra = grid.clone();
        extra[0] = Some(id("minecraft:dirt"));
        assert!(!recipe.matches(&extra, 3, 3, &BTreeMap::new()));
    }

    #[test]
    fn shapeless_tag_matching_handles_overlapping_candidates() {
        let stone = id("minecraft:stone");
        let granite = id("minecraft:granite");
        let rock_tag = id("m:rock");
        let stone_tag = id("m:stone_only");
        let tags = BTreeMap::from([
            (rock_tag.clone(), BTreeSet::from([stone.clone(), granite.clone()])),
            (stone_tag.clone(), BTreeSet::from([stone.clone()])),
        ]);
        let recipe = recipes::Recipe::Shapeless {
            id: id("m:mix"), ingredients: vec![
                recipes::Ingredient::Tag(rock_tag),
                recipes::Ingredient::Tag(stone_tag),
            ],
            result: recipes::ItemStack { id: id("m:mix"), count: 1 },
        };
        assert!(recipe.matches(&[Some(stone), Some(granite), None, None], 2, 2, &tags));
    }

    #[test]
    fn loot_is_seed_deterministic_and_applies_bounded_functions() {
        let table = loot::Table { pools: vec![loot::Pool { rolls: 4, entries: vec![
            loot::Entry { item: id("m:gem"), weight: 3, conditions: vec![loot::Condition::Always],
                functions: vec![loot::Function::SetCount { min: 2, max: 3 }, loot::Function::AddCount(1)], max_stack: 4 },
            loot::Entry { item: id("m:rare"), weight: 1, conditions: vec![loot::Condition::ChanceBasisPoints(0)],
                functions: vec![], max_stack: 1 },
        ]}]};
        let context = loot::Context::default();
        let a = table.roll(1234, &context).unwrap();
        let b = table.roll(1234, &context).unwrap();
        assert_eq!(a, b);
        assert_eq!(a.len(), 4);
        assert!(a.iter().all(|drop| drop.count >= 3 && drop.count <= 4));
        let invalid = loot::Table { pools: vec![loot::Pool { rolls: 1, entries: vec![
            loot::Entry { item: id("m:gem"), weight: 1, conditions: vec![loot::Condition::ChanceBasisPoints(10_001)], functions: vec![], max_stack: 1 }
        ]}]};
        assert!(invalid.roll(1, &context).is_err());
    }
}
