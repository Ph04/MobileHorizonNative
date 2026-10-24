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
            match local.entries.get(id) {
                Some(local_entry) if local_entry.version == remote_entry.version => {}
                _ if remote_entry.required => {
                    return Err(format!("server requires an incompatible registry entry: {}", id.as_str()));
                }
                _ => {}
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
        pub fn set(&mut self, owner: Id, key: Id, value: Value) -> Result<u64, String> {
            match &value {
                Value::Number(number) if !number.is_finite() => return Err("attachment number must be finite".into()),
                Value::Text(text) if text.len() > 1_048_576 => return Err("attachment text exceeds 1 MiB".into()),
                Value::Bytes(bytes) if bytes.len() > 1_048_576 => return Err("attachment bytes exceed 1 MiB".into()),
                _ => {}
            }
            self.revision = self.revision.wrapping_add(1);
            self.values.insert((owner.clone(), key.clone()), Record { owner, key, value, revision: self.revision });
            Ok(self.revision)
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
            if self.channels.contains_key(&id) { return Err(format!("duplicate network channel {}", id.as_str())); }
            if self.channels.len() >= 1024 { return Err("network channel registration limit reached".into()); }
            self.channels.insert(id, channel);
            Ok(())
        }
        pub fn validate_payload(&self, id: &Id, direction: Direction, size: usize) -> Result<(), String> {
            let channel = self.channels.get(id).ok_or_else(|| format!("unregistered network channel {}", id.as_str()))?;
            let direction_ok = channel.direction == Direction::Bidirectional || channel.direction == direction;
            if !direction_ok { return Err("network channel direction is not permitted".into()); }
            if size > channel.max_payload { return Err("network payload exceeds channel limit".into()); }
            Ok(())
        }
        fn channel(&self, id: &Id) -> Option<&Channel> { self.channels.get(id) }
    }

    /// The host supplies a monotonic window token (for example, a server tick bucket).
    /// Rate counters are discarded at window boundaries and keyed by player + channel.
    #[derive(Default)]
    pub struct RateLimiter { current_window: Option<u64>, counts: BTreeMap<(u64, Id), u32> }
    impl RateLimiter {
        pub fn allow(&mut self, registry: &Registry, player: u64, id: &Id, window: u64) -> Result<(), String> {
            let limit = registry.channel(id).ok_or_else(|| format!("unregistered network channel {}", id.as_str()))?.max_messages_per_window;
            if self.current_window != Some(window) {
                self.current_window = Some(window);
                self.counts.clear();
            }
            let count = self.counts.entry((player, id.clone())).or_default();
            if *count >= limit { return Err("network channel rate limit exceeded".into()); }
            *count += 1;
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


/// Declarative screen/widget descriptions. Click/render callbacks are dispatched by the client host.
pub mod screens {
    use super::Id;
    use std::collections::BTreeSet;
    #[derive(Clone, Debug, PartialEq)]
    pub enum Widget {
        Label { id: Id, text: String },
        Button { id: Id, label: String, action: Id },
        Toggle { id: Id, label: String, checked: bool },
        Slider { id: Id, label: String, min: f32, max: f32, step: f32, value: f32 },
    }
    #[derive(Clone, Debug, PartialEq)]
    pub struct Screen { pub id: Id, pub width: u16, pub height: u16, pub widgets: Vec<Widget> }
    #[derive(Clone, Debug, PartialEq)]
    pub enum Event { Click(Id), ToggleChanged(Id, bool), SliderChanged(Id, f32), Close }
    impl Widget { fn id(&self) -> &Id { match self { Self::Label{id,..}|Self::Button{id,..}|Self::Toggle{id,..}|Self::Slider{id,..} => id } } }
    impl Screen {
        pub fn validate(&self) -> Result<(), String> {
            if self.width == 0 || self.height == 0 || self.width > 4096 || self.height > 4096 { return Err("screen dimensions must be 1..=4096".into()); }
            if self.widgets.len() > 128 { return Err("screen exceeds 128 widgets".into()); }
            let mut ids = BTreeSet::new();
            for widget in &self.widgets {
                if !ids.insert(widget.id().clone()) { return Err("duplicate screen widget id".into()); }
                match widget {
                    Widget::Label { text, .. } if text.len() > 8192 => return Err("label exceeds text budget".into()),
                    Widget::Button { label, .. } | Widget::Toggle { label, .. } | Widget::Slider { label, .. } if label.len() > 1024 => return Err("widget label exceeds text budget".into()),
                    Widget::Slider { min, max, step, value, .. } if !min.is_finite() || !max.is_finite() || !step.is_finite() || !value.is_finite() || min >= max || *step <= 0.0 || value < min || value > max => return Err("invalid slider range/value".into()),
                    _ => {}
                }
            }
            Ok(())
        }
    }
}

/// Safe model declarations with bounded cuboids and explicit parent resolution.
pub mod model_loading {
    use super::Id;
    use std::collections::{BTreeMap, BTreeSet};
    #[derive(Clone, Debug, PartialEq)]
    pub struct Face { pub texture: String, pub uv: [f32;4], pub tint_index: Option<i32> }
    #[derive(Clone, Debug, PartialEq)]
    pub struct Element { pub from: [f32;3], pub to: [f32;3], pub faces: BTreeMap<String, Face> }
    #[derive(Clone, Debug, PartialEq)]
    pub struct Model { pub id: Id, pub parent: Option<Id>, pub textures: BTreeMap<String, Id>, pub elements: Vec<Element> }
    #[derive(Default)]
    pub struct Registry { models: BTreeMap<Id, Model> }
    impl Model {
        pub fn validate(&self) -> Result<(), String> {
            if self.elements.len() > 256 || self.textures.len() > 256 { return Err("model exceeds element/texture limits".into()); }
            for (key, value) in &self.textures {
                if key.is_empty() || key.len() > 128 || value.as_str().is_empty() { return Err("invalid model texture reference".into()); }
            }
            for element in &self.elements {
                if element.faces.len() > 6 || element.faces.is_empty() { return Err("model element requires 1..=6 faces".into()); }
                for axis in 0..3 {
                    if !element.from[axis].is_finite() || !element.to[axis].is_finite() || element.from[axis] < -16.0 || element.to[axis] > 32.0 || element.from[axis] >= element.to[axis] {
                        return Err("invalid model cuboid bounds".into());
                    }
                }
                for (direction, face) in &element.faces {
                    if !matches!(direction.as_str(), "north"|"south"|"east"|"west"|"up"|"down") { return Err("unknown model face direction".into()); }
                    if face.texture.is_empty() || face.texture.len() > 256 || face.uv.iter().any(|v| !v.is_finite() || *v < -4096.0 || *v > 4096.0) {
                        return Err("invalid model face texture/UV".into());
                    }
                }
            }
            Ok(())
        }
    }
    impl Registry {
        pub fn register(&mut self, model: Model) -> Result<(), String> {
            model.validate()?;
            if self.models.contains_key(&model.id) { return Err(format!("duplicate model {}", model.id.as_str())); }
            self.models.insert(model.id.clone(), model); Ok(())
        }
        pub fn get(&self, id: &Id) -> Option<&Model> { self.models.get(id) }
        pub fn validate_parent_chain(&self, id: &Id, external_parents: &BTreeSet<Id>) -> Result<Vec<Id>, String> {
            let mut result = Vec::new();
            let mut visiting = BTreeSet::new();
            let mut current = Some(id.clone());
            while let Some(next) = current {
                if !visiting.insert(next.clone()) { return Err("model parent cycle detected".into()); }
                if result.len() >= 64 { return Err("model parent chain exceeds 64 entries".into()); }
                result.push(next.clone());
                match self.models.get(&next) {
                    Some(model) => current = model.parent.clone(),
                    None if external_parents.contains(&next) => current = None,
                    None if next == *id => return Err("model not registered".into()),
                    None => return Err(format!("unresolved model parent {}", next.as_str())),
                }
            }
            Ok(result)
        }
    }
}

/// Neutral fluid-surface mesh generation; actual GPU submission remains in the renderer host.
pub mod rendering_fluids {
    use super::{Id, render::{Mesh, Vertex}};
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub struct Appearance { pub id: Id, pub still_texture: Id, pub flowing_texture: Id, pub tint_rgba: u32, pub viscosity: u32 }
    impl Appearance {
        pub fn validate(&self) -> Result<(), String> {
            if self.viscosity > 1_000_000 { return Err("fluid viscosity exceeds supported bound".into()); }
            Ok(())
        }
    }
    pub fn surface_quad(x: f32, y: f32, z: f32, width: f32, depth: f32) -> Result<Mesh, String> {
        if [x,y,z,width,depth].iter().any(|v| !v.is_finite()) || width <= 0.0 || depth <= 0.0 || width > 64.0 || depth > 64.0 {
            return Err("invalid fluid surface bounds".into());
        }
        let x2=x+width; let z2=z+depth;
        let mesh=Mesh { vertices: vec![
            Vertex{position:[x,y,z],normal:[0.0,1.0,0.0],uv:[0.0,0.0]},
            Vertex{position:[x,y,z2],normal:[0.0,1.0,0.0],uv:[0.0,1.0]},
            Vertex{position:[x2,y,z2],normal:[0.0,1.0,0.0],uv:[1.0,1.0]},
            Vertex{position:[x2,y,z],normal:[0.0,1.0,0.0],uv:[1.0,0.0]},
        ], indices:vec![0,1,2,0,2,3]};
        mesh.validate(4,6)?; Ok(mesh)
    }
}

/// Typed equivalent of sounds.json event entries, including weighted variant selection.
pub mod sounds {
    use super::Id;
    #[derive(Clone, Debug, PartialEq)]
    pub struct Variant { pub sound: Id, pub weight: u32, pub volume: f32, pub pitch: f32, pub stream: bool, pub preload: bool }
    #[derive(Clone, Debug, PartialEq)]
    pub struct Event { pub id: Id, pub variants: Vec<Variant> }
    impl Event {
        pub fn validate(&self) -> Result<(), String> {
            if self.variants.is_empty() || self.variants.len() > 256 { return Err("sound event requires 1..=256 variants".into()); }
            for variant in &self.variants {
                if variant.weight == 0 || !variant.volume.is_finite() || !(0.0..=16.0).contains(&variant.volume)
                    || !variant.pitch.is_finite() || !(0.01..=4.0).contains(&variant.pitch) {
                    return Err("invalid sound variant".into());
                }
            }
            Ok(())
        }
        pub fn select(&self, seed: u64) -> Result<&Variant, String> {
            self.validate()?;
            let total: u64 = self.variants.iter().try_fold(0u64, |sum,v| sum.checked_add(v.weight as u64).ok_or("sound weight overflow"))?;
            let mut x = if seed == 0 { 0x9e37_79b9_7f4a_7c15 } else { seed };
            x ^= x << 13; x ^= x >> 7; x ^= x << 17;
            let selected=x % total;
            let mut cumulative=0u64;
            self.variants.iter().find(|variant| { cumulative += variant.weight as u64; selected < cumulative }).ok_or_else(|| "sound variant selection failed".into())
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
        local.entries.remove(&id("m:required"));
        remote.entries.insert(id("server:mandatory"), registry_sync::Entry { version: 1, required: true });
        assert!(registry_sync::negotiate(&local, &remote, 32).is_err());
    }

    #[test]
    fn attachment_snapshots_reject_stale_revisions() {
        let mut store = attachments::Store::default();
        store.set(id("m:player"), id("m:coins"), attachments::Value::Integer(12)).unwrap();
        let snapshot = store.snapshot();
        assert_eq!(store.get(&id("m:player"), &id("m:coins")), Some(&attachments::Value::Integer(12)));
        let mut replica = attachments::Store::default();
        replica.apply_snapshot(snapshot.clone()).unwrap();
        assert_eq!(replica.get(&id("m:player"), &id("m:coins")), Some(&attachments::Value::Integer(12)));
        store.set(id("m:player"), id("m:coins"), attachments::Value::Integer(13)).unwrap();
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

#[cfg(test)]
mod api_v06_coverage_tests {
    use super::{biomes, content_registries, debug, dimensions, effects, messages, networking, object_builder, render};
    use crate::Id;
    use std::collections::BTreeMap;
    fn id(s: &str) -> Id { Id::parse(s).unwrap() }

    #[test]
    fn biome_modifiers_validate_and_produce_deterministic_host_patch() {
        let biome = id("minecraft:plains");
        let mut registry = biomes::Registry::default();
        registry.add(biome.clone(), biomes::Modifier::SetTemperature(0.8)).unwrap();
        registry.add(biome.clone(), biomes::Modifier::AddFeature { feature: id("m:tree"), step:"vegetal".into() }).unwrap();
        let snapshot = registry.apply_to_snapshot(&biome, biomes::Snapshot::default());
        assert_eq!(snapshot.temperature, Some(0.8));
        assert_eq!(snapshot.features, vec![("vegetal".into(), id("m:tree"))]);
        assert!(registry.add(biome, biomes::Modifier::SetTemperature(f32::NAN)).is_err());
    }

    #[test]
    fn typed_registry_freezes_and_data_map_revisions_advance() {
        let mut registry = content_registries::Registry::default();
        registry.register(id("m:item"), 42u32).unwrap();
        assert_eq!(registry.get(&id("m:item")), Some(&42));
        registry.freeze();
        assert!(registry.register(id("m:other"), 13).is_err());
        let mut map = content_registries::DataMap::default();
        assert_eq!(map.set(id("m:tag"), true), 1);
        assert!(map.remove(&id("m:tag")));
        assert_eq!(map.revision(), 2);
    }

    #[test]
    fn object_builder_enforces_types_depth_and_node_budgets() {
        let value = object_builder::Value::Object(BTreeMap::from([
            ("count".into(), object_builder::Value::Integer(3)),
        ]));
        let schema = object_builder::Kind::Object {
            fields: BTreeMap::from([("count".into(), object_builder::Kind::Integer { min:1, max:64 })]),
            allow_unknown:false,
        };
        assert!(object_builder::validate(&value, &schema, 4, 16).is_ok());
        assert!(object_builder::validate(&value, &schema, 0, 16).is_err());
        assert!(object_builder::validate(&value, &schema, 4, 1).is_err());
    }

    #[test]
    fn dimension_metadata_requires_safe_numeric_values_and_unique_ids() {
        let mut registry = dimensions::Registry::default();
        let definition = dimensions::Definition {
            id:id("m:moon"), generator:id("m:moon"), effects:id("minecraft:the_end"),
            has_ceiling:false, ambient_light:0.2, logical_height:256, coordinate_scale:1.0,
        };
        registry.register(definition.clone()).unwrap();
        assert!(registry.register(definition.clone()).is_err());
        let invalid = dimensions::Definition { id:id("m:bad"), ambient_light:f32::NAN, ..definition };
        assert!(registry.register(invalid).is_err());
    }

    #[test]
    fn modified_signed_chat_drops_signature_and_blocking_is_sticky() {
        let message = messages::Message {
            sender:Some("server".into()), text:"hello".into(),
            signature:Some(messages::Signature { bytes:vec![1,2,3], algorithm:"test".into() }),
            modified:false, blocked:false,
        };
        let modified = messages::apply_pipeline(message.clone(), [messages::Action::Modify("hi".into())], 100).unwrap();
        assert_eq!(modified.text, "hi");
        assert!(modified.signature.is_none() && modified.modified);
        let blocked = messages::apply_pipeline(message, [messages::Action::Block, messages::Action::Modify("no".into())], 100).unwrap();
        assert!(blocked.blocked);
        assert_eq!(blocked.text, "hello");
    }

    #[test]
    fn network_channels_enforce_direction_size_and_duplicate_ids() {
        let mut registry = networking::Registry::default();
        let channel = networking::Channel { direction:networking::Direction::ClientToServer, max_payload:128, max_messages_per_window:20 };
        registry.register(id("m:channel"), channel.clone()).unwrap();
        assert!(registry.register(id("m:channel"), channel).is_err());
        assert!(registry.validate_payload(&id("m:channel"), networking::Direction::ClientToServer, 128).is_ok());
        assert!(registry.validate_payload(&id("m:channel"), networking::Direction::ClientToServer, 129).is_err());
        assert!(registry.validate_payload(&id("m:channel"), networking::Direction::ServerToClient, 1).is_err());
    }

    #[test]
    fn render_and_effect_apis_reject_invalid_values_and_obey_budgets() {
        let mesh = render::Mesh { vertices:vec![
            render::Vertex { position:[0.0,0.0,0.0], normal:[0.0,1.0,0.0], uv:[0.0,0.0] },
            render::Vertex { position:[1.0,0.0,0.0], normal:[0.0,1.0,0.0], uv:[1.0,0.0] },
            render::Vertex { position:[0.0,1.0,0.0], normal:[0.0,1.0,0.0], uv:[0.0,1.0] },
        ], indices:vec![0,1,2] };
        assert!(mesh.validate(3,3).is_ok());
        assert!(mesh.validate(2,3).is_err());
        let mut budget=render::DrawBudget::new(2);
        budget.consume(2).unwrap();
        assert!(budget.consume(1).is_err());
        assert!(effects::Particle { id:id("m:dust"), count:1, spread:[1.0,1.0,1.0], speed:1.0 }.validate().is_ok());
        assert!(effects::Sound { id:id("m:bell"), volume:1.0, pitch:f32::NAN, category:"block".into() }.validate().is_err());
        let mut overlay=debug::Overlay::default();
        overlay.push(debug::Line { from:[0.0,0.0,0.0],to:[1.0,1.0,1.0],rgba:0xFFFFFFFF },1).unwrap();
        assert!(overlay.push(debug::Line { from:[0.0,0.0,0.0],to:[2.0,2.0,2.0],rgba:0xFFFFFFFF },1).is_err());
    }
}


#[cfg(test)]
mod api_v06_final_tests {
    use super::{attachments, model_loading, networking, registry_sync, rendering_fluids, screens, sounds};
    use crate::Id;
    use std::collections::BTreeMap;
    fn id(s: &str) -> Id { Id::parse(s).unwrap() }

    #[test]
    fn required_remote_registry_version_mismatch_fails_even_if_local_entry_is_optional() {
        let local=registry_sync::Manifest { revision:1, entries:BTreeMap::from([(id("m:thing"),registry_sync::Entry{version:1,required:false})]) };
        let mut remote=registry_sync::Manifest { revision:2, entries:BTreeMap::from([(id("m:thing"),registry_sync::Entry{version:2,required:true})]) };
        assert!(registry_sync::negotiate(&local,&remote,16).is_err());
        remote.entries.get_mut(&id("m:thing")).unwrap().version=1;
        assert!(registry_sync::negotiate(&local,&remote,16).is_ok());
        let local_empty=registry_sync::Manifest { revision:1, entries:BTreeMap::new() };
        assert!(registry_sync::negotiate(&local_empty,&remote,16).is_err());
    }

    #[test]
    fn attachment_rejects_non_finite_numbers_and_unbounded_payloads() {
        let mut store=attachments::Store::default();
        assert!(store.set(id("m:player"),id("m:value"),attachments::Value::Number(f64::NAN)).is_err());
        assert!(store.set(id("m:player"),id("m:text"),attachments::Value::Text("x".repeat(1_048_577))).is_err());
        assert!(store.set(id("m:player"),id("m:ok"),attachments::Value::Number(4.5)).is_ok());
    }

    #[test]
    fn network_rate_limiter_counts_per_player_channel_and_window() {
        let mut channels=networking::Registry::default();
        channels.register(id("m:chat"),networking::Channel{direction:networking::Direction::Bidirectional,max_payload:128,max_messages_per_window:1}).unwrap();
        let mut rate=networking::RateLimiter::default();
        assert!(rate.allow(&channels,7,&id("m:chat"),10).is_ok());
        assert!(rate.allow(&channels,7,&id("m:chat"),10).is_err());
        assert!(rate.allow(&channels,8,&id("m:chat"),10).is_ok());
        assert!(rate.allow(&channels,7,&id("m:chat"),11).is_ok());
    }

    #[test]
    fn declarative_screen_and_model_loading_validate_inputs() {
        let screen=screens::Screen{id:id("m:settings"),width:320,height:240,widgets:vec![
            screens::Widget::Button{id:id("m:done"),label:"Done".into(),action:id("m:close")},
            screens::Widget::Slider{id:id("m:volume"),label:"Volume".into(),min:0.0,max:1.0,step:0.1,value:0.5},
        ]};
        assert!(screen.validate().is_ok());
        let model=model_loading::Model{id:id("m:cube"),parent:None,textures:BTreeMap::new(),elements:vec![]};
        let mut registry=model_loading::Registry::default();
        registry.register(model).unwrap();
        assert_eq!(registry.validate_parent_chain(&id("m:cube"),&Default::default()).unwrap(),vec![id("m:cube")]);
        assert!(registry.validate_parent_chain(&id("m:missing"),&Default::default()).is_err());
    }

    #[test]
    fn fluid_mesh_and_weighted_sound_variants_are_bounded() {
        let mesh=rendering_fluids::surface_quad(0.0,1.0,0.0,1.0,1.0).unwrap();
        assert_eq!(mesh.indices.len(),6);
        assert!(rendering_fluids::surface_quad(0.0,1.0,0.0,f32::NAN,1.0).is_err());
        let event=sounds::Event{id:id("m:bell"),variants:vec![
            sounds::Variant{sound:id("m:bell_low"),weight:1,volume:1.0,pitch:1.0,stream:false,preload:false},
            sounds::Variant{sound:id("m:bell_high"),weight:3,volume:0.8,pitch:1.2,stream:false,preload:false},
        ]};
        assert!(event.select(42).is_ok());
        assert!(event.select(42).unwrap().sound.as_str().starts_with("m:bell_"));
    }
}
