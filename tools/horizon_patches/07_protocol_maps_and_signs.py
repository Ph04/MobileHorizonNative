# Ordered patch step. See README.md in this directory for scope and order.
# Minecraft Java 26.3 component ID 74 is banner_patterns. Its exact wire codec
# is a VarInt list of Holder<BannerPattern> and DyeColor enum values. Preserve
# the original bytes after safely consuming the schema so metadata packets do
# not get discarded just because the renderer doesn't yet interpret the patterns.
pack_patch("crates/horizon-protocol/src/item.rs", '''    pub const PROFILE: i32 = 72;
    pub const DYED_COLOR: i32 = 47;''', '''    pub const PROFILE: i32 = 72;
    pub const BANNER_PATTERNS: i32 = 74;
    pub const DYED_COLOR: i32 = 47;''', "register banner_patterns component ID")

pack_patch("crates/horizon-protocol/src/item.rs", '''fn skip_raw_component(kind: i32, r: &mut Reader) -> Result<()> {''', '''fn skip_banner_patterns(r: &mut Reader) -> Result<()> {
    let count = r.count(4096)?;
    for _ in 0..count {
        // ByteBufCodecs.holder: id + 1, with zero followed by an inline
        // BannerPattern (asset id and translation key).
        if r.varint()? == 0 {
            r.string(32767)?;
            r.string(32767)?;
        }
        // DyeColor is an enum encoded as a VarInt in 26.3.
        r.varint()?;
    }
    Ok(())
}

fn skip_raw_component(kind: i32, r: &mut Reader) -> Result<()> {''', "read banner pattern component payloads")

pack_patch("crates/horizon-protocol/src/item.rs", '''        ATTRIBUTE_MODIFIERS => skip_attribute_modifiers(r)?,
        PROFILE => skip_profile(r)?,
        other => return Err(Error::new(format!("unsupported item component {other} (no safe wire codec)"))),''', '''        ATTRIBUTE_MODIFIERS => skip_attribute_modifiers(r)?,
        PROFILE => skip_profile(r)?,
        BANNER_PATTERNS => skip_banner_patterns(r)?,
        other => return Err(Error::new(format!("unsupported item component {other} (no safe wire codec)"))),''', "preserve raw banner patterns in item stacks")

pack_patch("crates/horizon-protocol/src/item.rs", '''    #[test]
    fn common_26_3_component_values_are_consumed_and_round_trip() {
        // minimum_attack_charge (f32), rarity (VarInt), glider (unit),
        // intangible_projectile (unit), enchantable (VarInt).
        let mut writer = Writer::new();
        writer.varint(1);
        writer.varint(5);
        writer.varint(5);
        writer.varint(0);
        writer.varint(7);
        writer.f32(0.5);
        writer.varint(12);
        writer.varint(2);
        writer.varint(22);
        writer.varint(34);
        writer.varint(31);
        writer.varint(3);
        let bytes = writer.data.clone();
        let mut reader = Reader::new(&bytes);
        let stack = Slot::read(&mut reader).unwrap().unwrap();
        assert_eq!(stack.added.len(), 5);
        assert_eq!(reader.remaining(), 0);
        let mut encoded = Writer::new();
        Slot::write(&Some(stack), &mut encoded);
        assert_eq!(encoded.data, bytes);
    }''', '''    #[test]
    fn common_26_3_component_values_are_consumed_and_round_trip() {
        // minimum_attack_charge (f32), rarity (VarInt), glider (unit),
        // intangible_projectile (unit), enchantable (VarInt).
        let mut writer = Writer::new();
        writer.varint(1);
        writer.varint(5);
        writer.varint(5);
        writer.varint(0);
        writer.varint(7);
        writer.f32(0.5);
        writer.varint(12);
        writer.varint(2);
        writer.varint(22);
        writer.varint(34);
        writer.varint(31);
        writer.varint(3);
        let bytes = writer.data.clone();
        let mut reader = Reader::new(&bytes);
        let stack = Slot::read(&mut reader).unwrap().unwrap();
        assert_eq!(stack.added.len(), 5);
        assert_eq!(reader.remaining(), 0);
        let mut encoded = Writer::new();
        Slot::write(&Some(stack), &mut encoded);
        assert_eq!(encoded.data, bytes);
    }

    #[test]
    fn banner_patterns_component_is_preserved_without_dropping_slot() {
        // One BannerPattern layer: registered pattern ID (holder id + 1), then dye color.
        let bytes = [1, 5, 1, 0, 74, 1, 3, 2];
        let mut reader = Reader::new(&bytes);
        let stack = Slot::read(&mut reader).unwrap().unwrap();
        assert_eq!(reader.remaining(), 0);
        assert!(matches!(stack.added[0], (component::BANNER_PATTERNS, Component::Raw(_))));
        let mut encoded = Writer::new();
        Slot::write(&Some(stack), &mut encoded);
        assert_eq!(encoded.data, bytes);
    }''', "test banner-pattern stack round-trip")

# MapItemData was not in the clientbound packet enum. This decoder follows the
# Java 26.3 layout: map id, scale, locked, optional decorations, optional color patch.
pack_patch("crates/horizon-client/src/net.rs", '''        if packet_name == "set_entity_data" {
            static SET_ENTITY_DATA_PACKET_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                std::sync::atomic::AtomicUsize::new(0);
            if SET_ENTITY_DATA_PACKET_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 1600 {
                log::info!(
                    "HorizonDiag: packet_received phase=play id={} name={} payload_bytes={}",
                    id, packet_name, payload.len()
                );
            }
        }
        let packet = match play::Clientbound::decode(id, &payload) {''', '''        if packet_name == "set_entity_data" {
            static SET_ENTITY_DATA_PACKET_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                std::sync::atomic::AtomicUsize::new(0);
            if SET_ENTITY_DATA_PACKET_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 1600 {
                log::info!(
                    "HorizonDiag: packet_received phase=play id={} name={} payload_bytes={}",
                    id, packet_name, payload.len()
                );
            }
        }
        if packet_name == "map_item_data" || packet_name == "block_entity_data" {
            static VISUAL_WORLD_PACKET_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                std::sync::atomic::AtomicUsize::new(0);
            if VISUAL_WORLD_PACKET_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 2400 {
                log::info!(
                    "HorizonDiag: packet_received phase=play id={} name={} payload_bytes={}",
                    id, packet_name, payload.len()
                );
            }
        }
        if packet_name == "level_chunk_with_light" {
            static CHUNK_PACKET_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                std::sync::atomic::AtomicUsize::new(0);
            if CHUNK_PACKET_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 512 {
                log::info!(
                    "HorizonDiag: packet_received phase=play id={} name={} payload_bytes={}",
                    id, packet_name, payload.len()
                );
            }
        }
        let packet = match play::Clientbound::decode(id, &payload) {''', "record incoming map and block-entity data packets")

pack_patch("crates/horizon-client/src/hud.rs", '''pub const MAP_SLOTS: usize = 256;''', '''pub const MAP_SLOTS: usize = 640;''', "reserve map textures for large server map walls")

pack_patch("crates/horizon-client/src/game/remote.rs", '''        let key = (pos[0], pos[1], pos[2]);
        let Some(name) = self
            .remote
            .as_ref()
            .and_then(|r| r.builtin.get("minecraft:block_entity_type")?.name(kind))
        else {
            return;
        };
        let Some(entity) = self.world.block_entities.get(&key) else {
            return;
        };
        let Tag::Compound(fields) = tag else {
            return;
        };
        if entity.id() != name || fields.is_empty() {
            return;
        }
        let mut full = fields.clone();
        full.insert("id".into(), Tag::String(name.into()));
        for (k, v) in ["x", "y", "z"].into_iter().zip(pos) {
            full.insert(k.into(), Tag::Int(v));
        }
        if let Some((_, loaded)) = horizon_client::save::decode_block_entity(&Tag::Compound(full)) {
            self.world.block_entities.insert(key, loaded);
            // The section is drawn again with what the block entity shows.
            let state = self.world.block(pos[0], pos[1], pos[2]);
            let _ = self.world.set(pos[0], pos[1], pos[2], state);
        }''', '''        let key = (pos[0], pos[1], pos[2]);
        let name = self.remote.as_ref()
            .and_then(|r| r.builtin.get("minecraft:block_entity_type")?.name(kind))
            .map(str::to_owned);
        let is_sign = name.as_deref().is_some_and(|name| name.ends_with("sign"));
        static BLOCK_ENTITY_RECEIVE_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
            std::sync::atomic::AtomicUsize::new(0);
        let diagnostic_count = BLOCK_ENTITY_RECEIVE_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        if is_sign || diagnostic_count < 1024 {
            let tag_keys: Vec<String> = match tag {
                Tag::Compound(fields) => fields.keys().map(|key| key.to_string_lossy()).collect(),
                _ => Vec::new(),
            };
            log::info!(
                "HorizonDiag: block_entity_data_received pos={:?} kind={} registry_name={} tag_type={} tag_keys={:?}",
                pos, kind, name.as_deref().unwrap_or("unresolved"),
                if matches!(tag, Tag::Compound(_)) { "compound" } else { "other" }, tag_keys
            );
        }
        let Some(name) = name.as_deref() else {
            log::warn!("HorizonDiag: block_entity_data_not_applied pos={:?} kind={} reason=registry_type_unresolved", pos, kind);
            return;
        };
        // A sign update can race the chunk block-entity list. Rebuild the local
        // block entity from the *current block state* before applying the NBT;
        // this preserves the state-authoritative behavior and avoids dropping a
        // valid text update solely because the local SignData was not instantiated.
        if is_sign {
            let state = self.world.block(pos[0], pos[1], pos[2]);
            self.sync_block_entity(key, state);
            log::info!(
                "HorizonDiag: sign_block_entity_synced_from_block_state pos={:?} block={} local_entity_present={}",
                pos,
                self.res.facts().get(state).name,
                self.world.block_entities.get(&key).is_some_and(|entity| matches!(entity, horizon_client::containers::BlockEntity::Sign(_)))
            );
        }
        let Some(entity) = self.world.block_entities.get(&key) else {
            if is_sign {
                log::warn!("HorizonDiag: sign_text_not_applied pos={:?} type={} reason=world_has_no_sign_block_entity_after_block_sync", pos, name);
            }
            return;
        };
        let Tag::Compound(fields) = tag else {
            if is_sign {
                log::warn!("HorizonDiag: sign_text_not_applied pos={:?} type={} reason=tag_is_not_compound", pos, name);
            }
            return;
        };
        if entity.id() != name || fields.is_empty() {
            if is_sign {
                log::warn!(
                    "HorizonDiag: sign_text_not_applied pos={:?} packet_type={} world_type={} tag_empty={} reason=type_mismatch_or_empty_tag",
                    pos, name, entity.id(), fields.is_empty()
                );
            }
            return;
        }
        let mut full = fields.clone();
        full.insert("id".into(), Tag::String(name.into()));
        for (k, v) in ["x", "y", "z"].into_iter().zip(pos) {
            full.insert(k.into(), Tag::Int(v));
        }
        if let Some((_, loaded)) = horizon_client::save::decode_block_entity(&Tag::Compound(full)) {
            if let horizon_client::containers::BlockEntity::Sign(sign) = &loaded {
                let front = sign.front.lines.iter().filter(|line| !line.is_empty()).cloned().collect::<Vec<_>>().join(" / ");
                let back = sign.back.lines.iter().filter(|line| !line.is_empty()).cloned().collect::<Vec<_>>().join(" / ");
                let front_preview: String = front.chars().take(96).collect();
                let back_preview: String = back.chars().take(96).collect();
                log::info!(
                    "HorizonDiag: sign_text_applied pos={:?} front_nonempty={} back_nonempty={} front_preview={:?} back_preview={:?} front_glowing={} back_glowing={}",
                    pos, sign.front.lines.iter().filter(|line| !line.is_empty()).count(),
                    sign.back.lines.iter().filter(|line| !line.is_empty()).count(),
                    front_preview, back_preview, sign.front.glowing, sign.back.glowing
                );
            }
            self.world.block_entities.insert(key, loaded);
            // The section is drawn again with what the block entity shows.
            let state = self.world.block(pos[0], pos[1], pos[2]);
            let _ = self.world.set(pos[0], pos[1], pos[2], state);
        } else if is_sign {
            log::warn!("HorizonDiag: sign_text_not_applied pos={:?} type={} reason=decode_block_entity_rejected_tag", pos, name);
        }''', "trace sign receive failures and successful decoded lines")

# Replace only the function body anchor; avoid depending on the original backtick description.
pack_patch("crates/horizon-client/src/signs.rs", '''fn component_text(tag: &Tag) -> String {
    match tag {
        Tag::String(s) => {
            let s = s.to_string_lossy();
            match serde_json::from_str::<serde_json::Value>(&s) {
                Ok(serde_json::Value::String(text)) => text,
                Ok(v) if v.get("text").is_some() => {
                    v["text"].as_str().unwrap_or_default().to_owned()
                }
                _ => s,
            }
        }
        Tag::Compound(_) => match tag.get("text") {
            Some(Tag::String(t)) => t.to_string_lossy(),
            _ => String::new(),
        },
        _ => String::new(),
    }
}''', '''fn append_json_component(value: &serde_json::Value, out: &mut String) {
    match value {
        serde_json::Value::String(text) => out.push_str(text),
        serde_json::Value::Array(children) => {
            for child in children { append_json_component(child, out); }
        }
        serde_json::Value::Object(fields) => {
            if let Some(text) = fields.get("text").and_then(serde_json::Value::as_str) {
                out.push_str(text);
            } else if let Some(keybind) = fields.get("keybind").and_then(serde_json::Value::as_str) {
                out.push_str(keybind);
            } else if let Some(fallback) = fields.get("fallback").and_then(serde_json::Value::as_str) {
                out.push_str(fallback);
            } else if let Some(translate) = fields.get("translate").and_then(serde_json::Value::as_str) {
                out.push_str(translate);
            } else if let Some(value) = fields.get("score").and_then(|score| score.get("value")).and_then(serde_json::Value::as_str) {
                out.push_str(value);
            } else if let Some(selector) = fields.get("selector").and_then(serde_json::Value::as_str) {
                out.push_str(selector);
            } else if let Some(nbt) = fields.get("nbt").and_then(serde_json::Value::as_str) {
                out.push_str(nbt);
            } else if let Some(score_name) = fields.get("score").and_then(|score| score.get("name")).and_then(serde_json::Value::as_str) {
                out.push_str(score_name);
            }
            if let Some(extra) = fields.get("extra") {
                append_json_component(extra, out);
            }
        }
        _ => {}
    }
}
fn append_nbt_component(tag: &Tag, out: &mut String) {
    match tag {
        Tag::String(s) => {
            let text = s.to_string_lossy();
            match serde_json::from_str::<serde_json::Value>(&text) {
                Ok(component) => append_json_component(&component, out),
                Err(_) => out.push_str(&text),
            }
        }
        Tag::List(items) => {
            for item in items { append_nbt_component(item, out); }
        }
        Tag::Compound(_) => {
            if let Some(Tag::String(text)) = tag.get("text") {
                append_nbt_component(&Tag::String(text.clone()), out);
            } else if let Some(Tag::String(keybind)) = tag.get("keybind") {
                out.push_str(&keybind.to_string_lossy());
            } else if let Some(Tag::String(fallback)) = tag.get("fallback") {
                out.push_str(&fallback.to_string_lossy());
            } else if let Some(Tag::String(translate)) = tag.get("translate") {
                out.push_str(&translate.to_string_lossy());
            } else if let Some(Tag::Compound(score)) = tag.get("score") {
                if let Some(Tag::String(value)) = score.iter().find_map(|(key, item)| (key.to_string_lossy() == "value").then_some(item)) {
                    out.push_str(&value.to_string_lossy());
                } else if let Some(Tag::String(name)) = score.iter().find_map(|(key, item)| (key.to_string_lossy() == "name").then_some(item)) {
                    out.push_str(&name.to_string_lossy());
                }
            } else if let Some(Tag::String(selector)) = tag.get("selector") {
                out.push_str(&selector.to_string_lossy());
            } else if let Some(Tag::String(nbt)) = tag.get("nbt") {
                out.push_str(&nbt.to_string_lossy());
            }
            if let Some(Tag::List(extra)) = tag.get("extra") {
                for child in extra { append_nbt_component(child, out); }
            }
        }
        _ => {}
    }
}
fn component_text(tag: &Tag) -> String {
    // Sign NBT stores each message as either a JSON component string or a
    // structured NBT component. Reuse Horizon's normal component decoder so
    // translate/with, keybinds, siblings and nested extra values get handled
    // consistently with chat and display entities.
    let json = if let Tag::String(source) = tag {
        let source = source.to_string_lossy();
        match serde_json::from_str::<serde_json::Value>(&source) {
            Ok(component) => component,
            Err(_) => return source,
        }
    } else {
        crate::scoreboard::tag_to_json(tag)
    };
    let parsed = crate::component::to_text(
        &json,
        &mut |value| crate::text::Text::literal(value),
    ).plain();
    if !parsed.is_empty() {
        return parsed;
    }
    // Preserve plain text on odd/legacy component forms that the standard
    // decoder cannot interpret; empty JSON components remain empty.
    let mut out = String::new();
    append_nbt_component(tag, &mut out);
    out
}''', "preserve nested and styled sign text components")

pack_patch("crates/horizon-client/src/signs.rs", '''    #[test]
    fn names_and_colors() {''', '''    #[test]
    fn nested_sign_components_keep_all_visible_text() {
        let json = Tag::String(r#"{"text":"","extra":[{"text":"[Shop] "},{"text":"Hello","color":"gold"}]}"#.into());
        assert_eq!(component_text(&json), "[Shop] Hello");
        let nbt = Tag::compound([
            ("extra", Tag::List(vec![
                Tag::compound([("text", Tag::String("A".into()))]),
                Tag::compound([("extra", Tag::List(vec![
                    Tag::compound([("text", Tag::String("B".into()))])
                ]))]),
            ]))
        ]);
        assert_eq!(component_text(&nbt), "AB");
    }

    #[test]
    fn names_and_colors() {''', "test nested sign component flattening")

pack_patch("crates/horizon-client/src/game/packs.rs", '''            "Horizon: visual resource load locale={} active_server_packs={} sprite_layers={} item_definitions={} item_prototypes={} resource_root={}",
            lang::code(), active_server_packs, loaded_sprite_layers,
            pack.item_renderer.models.definitions.len(),
            pack.item_renderer.models.prototypes.len(),
            root.display()''', '''            "HorizonDiag: visual_resource_load locale={} active_server_packs={} sprite_layers={} item_definitions={} item_prototypes={} atlas_width={} atlas_height={} map_atlas_slots={} resource_root={}",
            lang::code(), active_server_packs, loaded_sprite_layers,
            pack.item_renderer.models.definitions.len(),
            pack.item_renderer.models.prototypes.len(),
            gui.atlas.width, gui.atlas.height, horizon_client::hud::MAP_SLOTS,
            root.display()''', "log atlas memory dimensions and map slot capacity")



print("Applied Java-compatible server resource packs, detailed resource/item/text diagnostics, and multiplayer skin fixes.")
