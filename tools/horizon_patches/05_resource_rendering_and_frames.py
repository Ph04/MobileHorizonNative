# Ordered patch step. See README.md in this directory for scope and order.
# Visual-resource diagnostics: verify both ZIP contents and the final loaded assets.
# Use the normal vanilla item-model pipeline for item_display and the block
# model catalog for block_display. The display's interpolation transform is
# applied around the entity origin rather than inventing a second model system.
pack_patch("crates/horizon-client/src/game/item_draw.rs", '''    /// `ItemEntityRenderer.submit` for every dropped item.''', '''    /// Render Java 26.3 item_display and block_display entities with the same
    /// model resolvers used by dropped items and world blocks.
    pub(super) fn remote_display_geometry(&self, camera: glam::DVec3, out: &mut Vec<Vertex>) {
        for (id, position, stack, context_id, transform) in self.remote_item_displays() {
            let at = glam::DVec3::from_array(position);
            let relative = at - camera;
            if relative.length_squared() > 128.0 * 128.0 {
                continue;
            }
            let context = match context_id {
                0 => DisplayContext::None,
                1 => DisplayContext::ThirdPersonLeftHand,
                2 => DisplayContext::ThirdPersonRightHand,
                3 => DisplayContext::FirstPersonLeftHand,
                4 => DisplayContext::FirstPersonRightHand,
                5 => DisplayContext::Head,
                6 => DisplayContext::Gui,
                7 => DisplayContext::Ground,
                8 => DisplayContext::Fixed,
                9 => DisplayContext::OnShelf,
                _ => DisplayContext::None,
            };
            let owner = owner_at(position, 0.0, "minecraft:item_display");
            let state = self.resolve_item(&stack, context, Some(&owner), id as i32);
            if state.is_empty() {
                continue;
            }
            let packed = self.world.light(
                position[0].floor() as i32,
                position[1].floor() as i32,
                position[2].floor() as i32,
            );
            let pose = glam::Mat4::from_translation(relative.as_vec3()) * transform.matrix();
            let start = out.len();
            self.submit_item_world(&state, pose, vertex_light(packed), out);
            self.cull_entity_vertices(out, start);
        }
        for (id, position, state, transform) in self.remote_block_displays() {
            let at = glam::DVec3::from_array(position);
            let relative = at - camera;
            if relative.length_squared() > 128.0 * 128.0 || state.0 == 0 {
                continue;
            }
            let packed = self.world.light(
                position[0].floor() as i32,
                position[1].floor() as i32,
                position[2].floor() as i32,
            );
            let pose = glam::Mat4::from_translation(relative.as_vec3())
                * transform.matrix()
                * glam::Mat4::from_translation(glam::Vec3::splat(-0.5));
            self.block_model_vertices(
                state,
                i64::from(id),
                pose,
                vertex_light(packed),
                true,
                out,
            );
        }
    }
    /// `ItemEntityRenderer.submit` for every dropped item.''', "render item and block display entities")

pack_patch("crates/horizon-client/src/game.rs", '''        // `ItemEntityRenderer`.
        self.dropped_item_geometry(camera, alpha, &mut out);''', '''        // `ItemEntityRenderer` and server-driven display entities.
        self.dropped_item_geometry(camera, alpha, &mut out);
        self.remote_display_geometry(camera, &mut out);''', "submit remote display model geometry")

pack_patch("crates/horizon-client/src/game/packs.rs", '''        let render = std::sync::Arc::new(self.pack.assets.reload(root)?);
        let mut atlas = HudAtlas::build(root)?;''', '''        let render = std::sync::Arc::new(self.pack.assets.reload(root)?);
        let loaded_sprite_layers = render.sprite_layers.len();
        let mut atlas = HudAtlas::build(root)?;''', "capture loaded sprite-layer count")

pack_patch("crates/horizon-client/src/game/packs.rs", '''            self.pack.context.light.clone(),
        )?;
        // Keep the working GPU resources, but release its exclusive window surface.''', '''            self.pack.context.light.clone(),
        )?;
        let active_server_packs = self.remote.as_ref().map_or(0, |remote| remote.server_packs.len());
        log::info!(
            "Horizon: visual resource load locale={} active_server_packs={} sprite_layers={} item_definitions={} item_prototypes={} resource_root={}",
            lang::code(), active_server_packs, loaded_sprite_layers,
            pack.item_renderer.models.definitions.len(),
            pack.item_renderer.models.prototypes.len(),
            root.display()
        );
        // Keep the working GPU resources, but release its exclusive window surface.''', "log loaded visual resource counts")

pack_patch("crates/horizon-client/src/game/remote.rs", '''                let with_any_component_patch = p.items.iter().flatten().filter(|stack|
                    !stack.added.is_empty() || !stack.removed.is_empty()
                ).count();
                log::info!(
                    "HorizonDiag: container_set_content window={} state={} slots={} stacks_with_item_model={} stacks_with_custom_model_data={} stacks_with_component_patch={}",
                    p.container, p.state, p.items.len(), with_item_model, with_custom_model_data, with_any_component_patch
                );''', '''                let with_custom_data = p.items.iter().flatten().filter(|stack|
                    stack.component(horizon_protocol::item::component::CUSTOM_DATA).is_some()
                ).count();
                let with_custom_name = p.items.iter().flatten().filter(|stack|
                    stack.component(horizon_protocol::item::component::CUSTOM_NAME).is_some()
                ).count();
                let with_item_name = p.items.iter().flatten().filter(|stack|
                    stack.component(horizon_protocol::item::component::ITEM_NAME).is_some()
                ).count();
                let with_lore = p.items.iter().flatten().filter(|stack|
                    stack.component(horizon_protocol::item::component::LORE).is_some()
                ).count();
                let with_any_component_patch = p.items.iter().flatten().filter(|stack|
                    !stack.added.is_empty() || !stack.removed.is_empty()
                ).count();
                log::info!(
                    "HorizonDiag: container_set_content window={} state={} slots={} stacks_with_item_model={} stacks_with_custom_model_data={} stacks_with_custom_data={} stacks_with_custom_name={} stacks_with_item_name={} stacks_with_lore={} stacks_with_component_patch={}",
                    p.container, p.state, p.items.len(), with_item_model, with_custom_model_data,
                    with_custom_data, with_custom_name, with_item_name, with_lore, with_any_component_patch
                );''', "measure visual item component types in server menus")



# Keep item packets decodable when servers use the full 26.3 component registry.
# Components with known wire codecs but no renderer support are preserved as raw
# values; truly unknown codecs still fail closed so subsequent fields cannot desync.
pack_patch("crates/horizon-protocol/src/item.rs", '''    pub const MAP_ID: i32 = 48;
}''', '''    pub const MAP_ID: i32 = 48;
    pub const ATTRIBUTE_MODIFIERS: i32 = 16;
    pub const PROFILE: i32 = 72;
}''', "register attribute-modifiers and profile component IDs")

pack_patch("crates/horizon-protocol/src/item.rs", '''    TooltipDisplay {
        hide: bool,
        hidden: Vec<i32>,
    },
}''', '''    TooltipDisplay {
        hide: bool,
        hidden: Vec<i32>,
    },
    /// A recognized component whose value is safely skippable but not yet rendered.
    /// Payload bytes are retained verbatim so decoding does not desynchronize the slot.
    Raw(Vec<u8>),
}''', "preserve recognized-but-unrendered component values")

pack_patch("crates/horizon-protocol/src/item.rs", '''impl Component {
    fn read(kind: i32, r: &mut Reader) -> Result<Self> {''', '''fn skip_attribute_modifiers(r: &mut Reader) -> Result<()> {
    let count = r.count(4096)?;
    for _ in 0..count {
        // Holder<Attribute>, AttributeModifier (Identifier, f64, operation id),
        // EquipmentSlotGroup id, then Display (default=0, hidden=1, override=2).
        r.varint()?;
        r.string(32767)?;
        r.f64()?;
        r.varint()?;
        r.varint()?;
        match r.varint()? {
            0 | 1 => {}
            2 => { r.nbt()?; }
            value => return Err(Error::new(format!("invalid attribute modifier display type {value}"))),
        }
    }
    Ok(())
}

fn skip_profile_properties(r: &mut Reader) -> Result<()> {
    // GameProfile properties are bounded to 16 entries; property names and
    // signatures use their protocol-specific limits.
    let count = r.count(16)?;
    for _ in 0..count {
        r.string(64)?;
        r.string(32767)?;
        if r.bool()? {
            r.string(1024)?;
        }
    }
    Ok(())
}

fn skip_profile(r: &mut Reader) -> Result<()> {
    // ResolvableProfile begins with a VarInt enum (0=partial, 1=complete),
    // followed by the profile and PlayerSkin.Patch's optional textures/model.
    match r.varint()? {
        1 => {
            r.uuid()?;
            r.string(16)?;
            skip_profile_properties(r)?;
        }
        0 => {
            if r.bool()? { r.string(16)?; }
            if r.bool()? { r.uuid()?; }
            skip_profile_properties(r)?;
        }
        other => return Err(Error::new(format!("invalid resolvable profile kind {other}"))),
    }
    for _ in 0..3 {
        if r.bool()? { r.string(32767)?; }
    }
    // Optional PlayerModelType id (the ID mapper is a VarInt in 26.3).
    if r.bool()? { r.varint()?; }
    Ok(())
}

fn skip_filterable_string(r: &mut Reader, max: usize) -> Result<()> {
    r.string(max)?;
    if r.bool()? {
        r.string(max)?;
    }
    Ok(())
}

fn skip_filterable_text(r: &mut Reader) -> Result<()> {
    r.nbt()?;
    if r.bool()? {
        r.nbt()?;
    }
    Ok(())
}

fn skip_writable_book_content(r: &mut Reader) -> Result<()> {
    let pages = r.count(100)?;
    for _ in 0..pages {
        skip_filterable_string(r, 1024)?;
    }
    Ok(())
}

fn skip_written_book_content(r: &mut Reader) -> Result<()> {
    // Filterable<String> title, author, generation, pages of
    // Filterable<Component>, and the resolved flag (26.3 STREAM_CODEC order).
    skip_filterable_string(r, 32)?;
    r.string(32767)?;
    r.varint()?;
    let pages = r.count(100)?;
    for _ in 0..pages {
        skip_filterable_text(r)?;
    }
    r.bool()?;
    Ok(())
}

fn skip_raw_component(kind: i32, r: &mut Reader) -> Result<()> {
    use component::*;
    match kind {
        // Only skip values whose exact 26.3 wire codec is known here. Complex
        // values must fail closed until their complete codec is implemented: treating
        // them as NBT or a VarInt can silently desynchronize every later slot.
        7 | 54 => { r.f32()?; } // minimum_attack_charge, potion_duration_scale
        8 | 12 | 19 | 31 | 42 | 50 | 65 | 75 | 88..=117 | 121 => {
            r.varint()?;
        }
        20 | 22 | 34 | 120 => {} // creative_slot_lock, intangible_projectile, glider, waxed
        21 => { r.bool()?; } // enchantment_glint_override
        35 | 73 => { r.string(32767)?; } // tooltip_style, note_block_sound
        56 => skip_writable_book_content(r)?,
        57 => skip_written_book_content(r)?,
        60 | 62 => { r.varint()?; r.nbt()?; } // typed entity/block-entity data
        61 => { r.nbt()?; } // bucket_entity_data
        ATTRIBUTE_MODIFIERS => skip_attribute_modifiers(r)?,
        PROFILE => skip_profile(r)?,
        other => return Err(Error::new(format!("unsupported item component {other} (no safe wire codec)"))),
    }
    Ok(())
}

impl Component {
    fn read(kind: i32, r: &mut Reader) -> Result<Self> {''', "add safe readers for common 26.3 component codecs")

pack_patch("crates/horizon-protocol/src/item.rs", '''            other => return Err(Error::new(format!("unsupported item component {other}"))),''', '''            other => {
                let start = r.position();
                skip_raw_component(other, r)?;
                Self::Raw(r.since(start).to_vec())
            },''', "preserve raw component payloads after safe decoding")

pack_patch("crates/horizon-protocol/src/item.rs", '''            Self::TooltipDisplay { hide, hidden } => {
                w.bool(*hide);
                w.varint(hidden.len() as i32);
                hidden.iter().for_each(|h| w.varint(*h));
            }
        }''', '''            Self::TooltipDisplay { hide, hidden } => {
                w.bool(*hide);
                w.varint(hidden.len() as i32);
                hidden.iter().for_each(|h| w.varint(*h));
            }
            Self::Raw(bytes) => w.bytes(bytes),
        }''', "write preserved raw component bytes")

pack_patch("crates/horizon-protocol/src/item.rs", '''    #[test]
    fn unknown_components_are_errors() {
        let bytes = [1, 5, 1, 0, 99];
        assert!(Slot::read(&mut Reader::new(&bytes)).is_err());
    }''', '''    #[test]
    fn unsupported_components_with_known_wire_types_do_not_discard_slots() {
        // Attribute modifiers: empty list. Profile: empty partial profile and empty skin patch.
        let bytes = [
            1, 5, 2, 0,
            16, 0,
            72, 0, 0, 0, 0, 0, 0, 0, 0,
        ];
        let mut reader = Reader::new(&bytes);
        let stack = Slot::read(&mut reader).unwrap().unwrap();
        assert_eq!(stack.added.len(), 2);
        assert!(matches!(stack.added[0], (component::ATTRIBUTE_MODIFIERS, Component::Raw(_))));
        assert!(matches!(stack.added[1], (component::PROFILE, Component::Raw(_))));
        assert_eq!(reader.remaining(), 0);
        let mut writer = Writer::new();
        Slot::write(&Some(stack), &mut writer);
        assert_eq!(writer.data, bytes);
    }

    #[test]
    fn nonempty_attribute_modifiers_are_consumed_and_round_trip() {
        let mut writer = Writer::new();
        writer.varint(1);
        writer.varint(5);
        writer.varint(1);
        writer.varint(0);
        writer.varint(component::ATTRIBUTE_MODIFIERS);
        writer.varint(1);
        writer.varint(2); // attribute registry holder
        writer.string("test:modifier");
        writer.f64(1.5);
        writer.varint(0); // operation
        writer.varint(0); // equipment slot group
        writer.varint(0); // default display
        let bytes = writer.data.clone();
        let mut reader = Reader::new(&bytes);
        let stack = Slot::read(&mut reader).unwrap().unwrap();
        assert_eq!(reader.remaining(), 0);
        let mut encoded = Writer::new();
        Slot::write(&Some(stack), &mut encoded);
        assert_eq!(encoded.data, bytes);
    }

    #[test]
    fn partial_profile_and_skin_patch_are_consumed_and_round_trip() {
        let mut writer = Writer::new();
        writer.varint(1);
        writer.varint(5);
        writer.varint(1);
        writer.varint(0);
        writer.varint(component::PROFILE);
        writer.varint(0); // partial profile
        writer.bool(true);
        writer.string("alex");
        writer.bool(false); // no UUID
        writer.varint(0); // no properties
        writer.bool(false); // no body override
        writer.bool(false); // no cape override
        writer.bool(false); // no elytra override
        writer.bool(false); // no model override
        let bytes = writer.data.clone();
        let mut reader = Reader::new(&bytes);
        let stack = Slot::read(&mut reader).unwrap().unwrap();
        assert_eq!(reader.remaining(), 0);
        let mut encoded = Writer::new();
        Slot::write(&Some(stack), &mut encoded);
        assert_eq!(encoded.data, bytes);
    }

    #[test]
    fn written_book_and_typed_entity_data_are_consumed_and_round_trip() {
        let mut writer = Writer::new();
        writer.varint(1);
        writer.varint(5);
        writer.varint(3);
        writer.varint(0);
        writer.varint(56); // writable_book_content
        writer.varint(1); // one page
        writer.string("hello");
        writer.bool(false); // no filtered page
        writer.varint(57); // written_book_content
        writer.string("title");
        writer.bool(false); // no filtered title
        writer.string("author");
        writer.varint(0); // generation
        writer.varint(1); // one page
        writer.nbt(&Tag::String("hello".into()));
        writer.bool(false); // no filtered page
        writer.bool(false); // unresolved
        writer.varint(60); // entity_data: type id + NBT
        writer.varint(2);
        writer.nbt(&Tag::compound([("id", Tag::String("minecraft:pig".into()))]));
        let bytes = writer.data.clone();
        let mut reader = Reader::new(&bytes);
        let stack = Slot::read(&mut reader).unwrap().unwrap();
        assert_eq!(stack.added.len(), 3);
        assert!(stack.added.iter().all(|(_, value)| matches!(value, Component::Raw(_))));
        assert_eq!(reader.remaining(), 0);
        let mut encoded = Writer::new();
        Slot::write(&Some(stack), &mut encoded);
        assert_eq!(encoded.data, bytes);
    }

    #[test]
    fn common_unrendered_component_values_are_preserved() {
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
        let mut reader = Reader::new(&writer.data);
        let stack = Slot::read(&mut reader).unwrap().unwrap();
        assert_eq!(stack.added.len(), 5);
        assert!(stack.added.iter().all(|(_, value)| matches!(value, Component::Raw(_))));
        assert_eq!(reader.remaining(), 0);
    }

    #[test]
    fn unknown_components_are_errors() {
        let bytes = [1, 5, 1, 0, 99];
        assert!(Slot::read(&mut Reader::new(&bytes)).is_err());
    }''', "test complex and scalar component preservation") 




# Resolve modern minecraft:profile item components instead of leaving all custom
# player heads on the vanilla Steve fallback. The PROFILE value is safely decoded
# above for packet framing; here we preserve the fields needed by the item renderer.
pack_patch("crates/horizon-client/src/game/remote.rs", '''fn apply_display_transform(transform: &mut RemoteDisplayTransform, values: &[(u8, i32, Value)]) {''', '''struct ProfileComponentCursor<'a> {
    bytes: &'a [u8],
    pos: usize,
}
impl ProfileComponentCursor<'_> {
    fn take(&mut self, count: usize) -> Option<&[u8]> {
        let end = self.pos.checked_add(count)?;
        if end > self.bytes.len() { return None; }
        let start = self.pos;
        self.pos = end;
        Some(&self.bytes[start..end])
    }
    fn varint(&mut self) -> Option<i32> {
        let mut out = 0u32;
        for shift in (0..35).step_by(7) {
            let byte = *self.take(1)?.first()?;
            out |= u32::from(byte & 0x7f).checked_shl(shift)?;
            if byte & 0x80 == 0 { return Some(out as i32); }
        }
        None
    }
    fn boolean(&mut self) -> Option<bool> {
        Some(*self.take(1)?.first()? != 0)
    }
    fn string(&mut self, limit: usize) -> Option<String> {
        let length = usize::try_from(self.varint()?).ok()?;
        if length > limit { return None; }
        String::from_utf8(self.take(length)?.to_vec()).ok()
    }
    fn uuid(&mut self) -> Option<String> {
        let b = self.take(16)?;
        Some(format!(
            "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
            b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
            b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]
        ))
    }
}
fn profile_component_tag(raw: &[u8]) -> Option<horizon_world::nbt::Tag> {
    use horizon_world::nbt::Tag;
    let mut reader = ProfileComponentCursor { bytes: raw, pos: 0 };
    let mut fields: Vec<(&str, Tag)> = Vec::new();
    match reader.varint()? {
        1 => {
            fields.push(("id", Tag::String(reader.uuid()?.as_str().into())));
            fields.push(("name", Tag::String(reader.string(16)?.as_str().into())));
        }
        0 => {
            if reader.boolean()? {
                fields.push(("name", Tag::String(reader.string(16)?.as_str().into())));
            }
            if reader.boolean()? {
                fields.push(("id", Tag::String(reader.uuid()?.as_str().into())));
            }
        }
        _ => return None,
    }
    let count = reader.varint()?;
    if !(0..=16).contains(&count) { return None; }
    let mut properties = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let name = reader.string(64)?;
        let value = reader.string(32767)?;
        let mut property: Vec<(&str, Tag)> = vec![
            ("name", Tag::String(name.as_str().into())),
            ("value", Tag::String(value.as_str().into())),
        ];
        if reader.boolean()? {
            property.push(("signature", Tag::String(reader.string(1024)?.as_str().into())));
        }
        properties.push(Tag::compound(property));
    }
    fields.push(("properties", Tag::List(properties)));
    for key in ["skin", "cape", "elytra"] {
        if reader.boolean()? {
            fields.push((key, Tag::String(reader.string(32767)?.as_str().into())));
        }
    }
    if reader.boolean()? {
        fields.push(("model", Tag::Int(reader.varint()?)));
    }
    if reader.pos != raw.len() { return None; }
    Some(Tag::compound(fields))
}

fn apply_display_transform(transform: &mut RemoteDisplayTransform, values: &[(u8, i32, Value)]) {''', "decode player-head profile component for the item model pipeline")

pack_patch("crates/horizon-client/src/game/remote.rs", '''        // An absent component in the network patch can suppress its default component
        // in a modern item definition; StackView interprets the leading ! convention.''', '''        if let Some(Component::Raw(raw)) =
            s.component(horizon_protocol::item::component::PROFILE)
        {
            if let Some(profile) = profile_component_tag(raw) {
                out.data_mut().set_other("minecraft:profile", Some(profile));
            } else {
                static BAD_PROFILE_COMPONENTS: std::sync::atomic::AtomicUsize =
                    std::sync::atomic::AtomicUsize::new(0);
                if BAD_PROFILE_COMPONENTS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 8 {
                    log::warn!("HorizonDiag: could not decode a minecraft:profile item component for a player head");
                }
            }
        }

        // An absent component in the network patch can suppress its default component
        // in a modern item definition; StackView interprets the leading ! convention.''', "pass profile data into custom player-head rendering")

pack_patch("crates/horizon-client/src/game/remote.rs", '''                    | horizon_protocol::item::component::CUSTOM_MODEL_DATA
            ))
            .collect();
        let removed_unhandled_components: Vec<i32> = s.removed.iter().copied()''', '''                    | horizon_protocol::item::component::CUSTOM_MODEL_DATA
                    | horizon_protocol::item::component::PROFILE
            ))
            .collect();
        let removed_unhandled_components: Vec<i32> = s.removed.iter().copied()''', "classify profile components as handled")

pack_patch("crates/horizon-client/src/game/remote.rs", '''                component::CUSTOM_MODEL_DATA => Some("minecraft:custom_model_data"),
                _ => None,''', '''                component::CUSTOM_MODEL_DATA => Some("minecraft:custom_model_data"),
                component::PROFILE => Some("minecraft:profile"),
                _ => None,''', "preserve explicit removal of profile components")

pack_patch("crates/horizon-client/src/game/remote.rs", '''        resolved.retain(|(_, skin)| !skin.starts_with("skins/") || self.show_skin(skin));''', '''        // Player-head displays may carry their own profile textures without a player-list entry.
        // Queue those URLs through Horizon's shared downloader so its normal atlas update
        // can make the skin available to the item renderer.
        let head_profiles = self.remote.as_ref().map(|remote| {
            remote.item_displays.values().filter_map(|display| {
                let stack = display.item.as_ref()?;
                horizon_client::item_render::stack_view(stack)
                    .patch.get("minecraft:profile")
                    .and_then(|value| value.clone())
            }).collect::<Vec<_>>()
        }).unwrap_or_default();
        for profile in head_profiles {
            if let Some(url) = profile.get("skin").and_then(serde_json::Value::as_str) {
                if url.starts_with("https://textures.minecraft.net/texture/")
                    || url.starts_with("http://textures.minecraft.net/texture/")
                {
                    let textures = horizon_client::skins::Textures {
                        skin: Some(url.to_owned()),
                        ..Default::default()
                    };
                    let mut downloads = self.skin_downloads.borrow_mut();
                    let _ = horizon_client::skins::lookup(&textures, false, |kind, url| {
                        downloads.state(kind, url)
                    });
                    continue;
                }
            }
            let properties = profile.get("properties").and_then(serde_json::Value::as_array);
            let Some(property) = properties.and_then(|items| items.iter().find(|property| {
                property.get("name").and_then(serde_json::Value::as_str) == Some("textures")
            })) else { continue; };
            let Some(encoded) = property.get("value").and_then(serde_json::Value::as_str) else {
                continue;
            };
            let signature = if property.get("signature").and_then(serde_json::Value::as_str).is_some() {
                horizon_client::skins::Signature::Signed
            } else {
                horizon_client::skins::Signature::Unsigned
            };
            let textures = horizon_client::skins::unpack(
                encoded, signature, &horizon_client::skins::TEXTURE_URIS
            );
            if textures.skin.is_some() {
                let mut downloads = self.skin_downloads.borrow_mut();
                let _ = horizon_client::skins::lookup(&textures, false, |kind, url| {
                    downloads.state(kind, url)
                });
            }
        }
        resolved.retain(|(_, skin)| !skin.starts_with("skins/") || self.show_skin(skin));''', "download textures for custom player heads with current Horizon skin lifecycle")

pack_patch("crates/horizon-client/src/game/item_draw.rs", '''    fn shield_texture(&self, pattern: &str) -> Option<String> {
        self.banners
            .pattern(pattern)
            .map(|_| self.banners.shield_texture(pattern))
    }
}''', '''    fn shield_texture(&self, pattern: &str) -> Option<String> {
        self.banners
            .pattern(pattern)
            .map(|_| self.banners.shield_texture(pattern))
    }
    fn player_skin(&self, profile: &Value) -> Option<String> {
        // A profile can name an already-packed resource location or provide the
        // standard signed/unsigned textures property. URLs are validated by the
        // existing skin decoder before being mapped into the dynamic skin atlas.
        if let Some(skin) = profile.get("skin").and_then(Value::as_str) {
            if skin.starts_with("https://textures.minecraft.net/texture/")
                || skin.starts_with("http://textures.minecraft.net/texture/")
            {
                let hash = horizon_client::skins::texture_hash(skin)?;
                return Some(horizon_client::skins::skin_location(&hash));
            }
            return Some(skin.to_owned());
        }
        let properties = profile.get("properties")?.as_array()?;
        let property = properties.iter().find(|property| {
            property.get("name").and_then(Value::as_str) == Some("textures")
        })?;
        let encoded = property.get("value").and_then(Value::as_str)?;
        let signature = if property.get("signature").and_then(Value::as_str).is_some() {
            horizon_client::skins::Signature::Signed
        } else {
            horizon_client::skins::Signature::Unsigned
        };
        let textures = horizon_client::skins::unpack(
            encoded, signature, &horizon_client::skins::TEXTURE_URIS
        );
        let url = textures.skin?;
        let hash = horizon_client::skins::texture_hash(&url)?;
        Some(horizon_client::skins::skin_location(&hash))
    }
}''', "resolve profile texture paths for player-head special models")





# Server-world item frames were not being fed into Game::item_frames, although
# Game::frame_geometry already renders those structs. Keep remote frame state in
# the same renderer path so contents, rotation, glow and maps are visible online.
pack_patch("crates/horizon-client/src/game/remote.rs", '''                self.spawn_entity(p.id, &kind, p.position, rotation);
                if !self.spawn_remote_object(p.id, &kind, p.position, rotation[0], p.data) {''', '''                self.spawn_entity(p.id, &kind, p.position, rotation);
                if kind == "minecraft:item_frame" || kind == "minecraft:glow_item_frame" {
                    let frame = horizon_client::entities::ItemFrame {
                        id: p.id as u32,
                        pos: p.position.map(|value| value.floor() as i32),
                        facing: p.data.clamp(0, 5) as u8,
                        glow: kind == "minecraft:glow_item_frame",
                        item: None,
                        rotation: 0,
                        drop_chance: 1.0,
                        fixed: false,
                        invisible: false,
                        check_interval: 0,
                    };
                    self.item_frames.retain(|known| known.id != frame.id);
                    log::info!(
                        "HorizonDiag: remote_item_frame_spawned entity_id={} kind={} block_pos={:?} facing={} glow={}",
                        frame.id, kind, frame.pos, frame.facing, frame.glow
                    );
                    self.item_frames.push(frame);
                }
                if !self.spawn_remote_object(p.id, &kind, p.position, rotation[0], p.data) {''', "track server item frames in the existing frame renderer")

pack_patch("crates/horizon-client/src/game/remote.rs", '''            C::SetEntityData(p) => {
                let item_display_item_update = p.values.iter().find_map(|(index, serializer, value)| {''', '''            C::SetEntityData(p) => {
                // ItemFrame.DATA_ITEM is metadata index 8 and DATA_ROTATION is 9.
                // Convert the complete server stack with the same component bridge used
                // by inventories and item_display entities before borrowing the frame.
                let item_frame_stack_update: Option<Option<horizon_client::items::ItemStack>> =
                    p.values.iter().find_map(|(index, _, value)| {
                        if *index == 8 || *index == 9 {
                            if let Value::Item(stack) = value {
                                Some(self.to_stack(stack))
                            } else { None }
                        } else { None }
                    });
                let item_frame_rotation_update = p.values.iter().find_map(|(index, _, value)| {
                    if *index == 10 || *index == 9 {
                        match value {
                            Value::Int(rotation) => Some(*rotation),
                            Value::Byte(rotation) => Some(i32::from(*rotation)),
                            _ => None,
                        }
                    } else { None }
                });
                if item_frame_stack_update.is_some() || item_frame_rotation_update.is_some() {
                    if let Some(frame) = self.item_frames.iter_mut().find(|frame| frame.id == p.id as u32) {
                        if let Some(item) = item_frame_stack_update {
                            frame.item = item;
                        }
                        if let Some(rotation) = item_frame_rotation_update {
                            frame.rotation = rotation.rem_euclid(8) as u8;
                        }
                        log::info!(
                            "HorizonDiag: remote_item_frame_updated entity_id={} item_present={} item_id={} rotation={} source_metadata_fields={:?}",
                            frame.id,
                            frame.item.is_some(),
                            frame.item.as_ref().map_or("-", |stack| stack.item.as_ref()),
                            frame.rotation,
                            p.values.iter().map(|(index, serializer, value)| (*index, *serializer, match value {
                                Value::Item(Some(stack)) => format!("item:{}", stack.item),
                                Value::Item(None) => "item:empty".to_owned(),
                                Value::Byte(v) => format!("byte:{}", v),
                                Value::Int(v) => format!("int:{}", v),
                                _ => format!("{:?}", std::mem::discriminant(value)),
                            })).collect::<Vec<_>>()
                        );
                    } else {
                        log::warn!(
                            "HorizonDiag: item_frame_metadata_without_spawn entity_id={} fields={:?}",
                            p.id,
                            p.values.iter().map(|(index, serializer, value)| (*index, *serializer, match value {
                                Value::Item(Some(stack)) => format!("item:{}", stack.item),
                                Value::Item(None) => "item:empty".to_owned(),
                                Value::Byte(v) => format!("byte:{}", v),
                                Value::Int(v) => format!("int:{}", v),
                                _ => format!("{:?}", std::mem::discriminant(value)),
                            })).collect::<Vec<_>>()
                        );
                    }
                }
                let item_display_item_update = p.values.iter().find_map(|(index, serializer, value)| {''', "apply server item frame contents and rotation")

pack_patch("crates/horizon-client/src/game/remote.rs", '''    fn remove_entity(&mut self, id: i32) {
        self.mobs.retain(|m| m.id != id as u32);''', '''    fn remove_entity(&mut self, id: i32) {
        self.item_frames.retain(|frame| frame.id != id as u32);
        self.mobs.retain(|m| m.id != id as u32);''', "remove server item frames when their entities despawn")

# Resource packs can override item-frame models too. The four baked models were
# loaded only once at startup, so re-bake them when the active root changes.
pack_patch("crates/horizon-client/src/game/packs.rs", '''        self.pack = pack;
        self.gui = gui;
        horizon_client::ui_theme::reset_atlas();''', '''        self.pack = pack;
        self.gui = gui;
        self.frame_model = super::frames::bake_frame(root, "item_frame");
        self.glow_frame_model = super::frames::bake_frame(root, "glow_item_frame");
        self.map_frame_model = super::frames::bake_frame(root, "item_frame_map");
        self.glow_map_frame_model = super::frames::bake_frame(root, "glow_item_frame_map");
        log::info!(
            "Horizon: item-frame models refreshed normal={} glow={} map={} glow_map={} atlas_sprites={}",
            self.frame_model.len(), self.glow_frame_model.len(),
            self.map_frame_model.len(), self.glow_map_frame_model.len(),
            self.gui.atlas.sprites.len()
        );
        horizon_client::ui_theme::reset_atlas();''', "reload item-frame models from active resource packs")




