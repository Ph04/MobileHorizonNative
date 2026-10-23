# Ordered patch step. See README.md in this directory for scope and order.
# Minecraft Java 26.3 display entities: text_display carries its content in
# entity metadata (indices 23..27). Horizon previously tracked its position only.
pack_patch("crates/horizon-client/src/game/remote.rs", '''struct Tracked {
    kind: String,
    position: [f64; 3],
}''', '''struct Tracked {
    kind: String,
    position: [f64; 3],
}

#[derive(Clone)]
struct RemoteTextDisplay {
    text: Option<horizon_client::text::Text>,
    line_width: i32,
    background_color: i32,
    text_opacity: i8,
    flags: i8,
    transform: RemoteDisplayTransform,
}
impl Default for RemoteTextDisplay {
    fn default() -> Self {
        Self {
            text: None,
            line_width: 200,
            background_color: 0x40000000,
            text_opacity: -1,
            flags: 0,
            transform: RemoteDisplayTransform::default(),
        }
    }
}

#[derive(Clone)]
pub(super) struct RemoteDisplayTransform {
    pub(super) translation: [f32; 3],
    pub(super) scale: [f32; 3],
    pub(super) left_rotation: [f32; 4],
    pub(super) right_rotation: [f32; 4],
}
impl Default for RemoteDisplayTransform {
    fn default() -> Self {
        Self {
            translation: [0.0; 3],
            scale: [1.0; 3],
            left_rotation: [0.0, 0.0, 0.0, 1.0],
            right_rotation: [0.0, 0.0, 0.0, 1.0],
        }
    }
}
impl RemoteDisplayTransform {
    pub(super) fn matrix(&self) -> glam::Mat4 {
        glam::Mat4::from_translation(glam::Vec3::from_array(self.translation))
            * glam::Mat4::from_quat(glam::Quat::from_xyzw(
                self.left_rotation[0], self.left_rotation[1],
                self.left_rotation[2], self.left_rotation[3],
            ))
            * glam::Mat4::from_scale(glam::Vec3::from_array(self.scale))
            * glam::Mat4::from_quat(glam::Quat::from_xyzw(
                self.right_rotation[0], self.right_rotation[1],
                self.right_rotation[2], self.right_rotation[3],
            ))
    }
}
#[derive(Clone, Default)]
struct RemoteItemDisplay {
    item: Option<horizon_client::items::ItemStack>,
    transform_context: u8,
    transform: RemoteDisplayTransform,
}
#[derive(Clone, Default)]
struct RemoteBlockDisplay {
    state: Option<horizon_world::registry::BlockStateId>,
    transform: RemoteDisplayTransform,
}''', "define tracked text display state")

pack_patch("crates/horizon-client/src/game/remote.rs", '''#[derive(Clone, Default)]
struct RemoteItemDisplay {''', '''fn apply_display_transform(transform: &mut RemoteDisplayTransform, values: &[(u8, i32, Value)]) {
    for (index, serializer, value) in values {
        match (*index, *serializer, value) {
            (11, 39, Value::Floats(v)) if v.len() == 3 => transform.translation = [v[0], v[1], v[2]],
            (12, 39, Value::Floats(v)) if v.len() == 3 => transform.scale = [v[0], v[1], v[2]],
            (13, 40, Value::Floats(v)) if v.len() == 4 => transform.left_rotation = [v[0], v[1], v[2], v[3]],
            (14, 40, Value::Floats(v)) if v.len() == 4 => transform.right_rotation = [v[0], v[1], v[2], v[3]],
            _ => {}
        }
    }
}

#[derive(Clone, Default)]
struct RemoteItemDisplay {''', "decode common display transform metadata")

pack_patch("crates/horizon-client/src/game/remote.rs", '''                r.entities.clear();
                r.lerps.clear();
                r.head_lerps.clear();''', '''                r.entities.clear();
                r.text_displays.clear();
                r.item_displays.clear();
                r.block_displays.clear();
                r.lerps.clear();
                r.head_lerps.clear();''', "clear display metadata on remote dimension reset")

pack_patch("crates/horizon-client/src/game/remote.rs", '''    entities: HashMap<i32, Tracked>,
    lerps: HashMap<i32, Lerp>,''', '''    entities: HashMap<i32, Tracked>,
    text_displays: HashMap<i32, RemoteTextDisplay>,
    item_displays: HashMap<i32, RemoteItemDisplay>,
    block_displays: HashMap<i32, RemoteBlockDisplay>,
    lerps: HashMap<i32, Lerp>,''', "store remote text displays")

pack_patch("crates/horizon-client/src/game/remote.rs", '''            entities: HashMap::new(),
            lerps: HashMap::new(),''', '''            entities: HashMap::new(),
            text_displays: HashMap::new(),
            item_displays: HashMap::new(),
            block_displays: HashMap::new(),
            lerps: HashMap::new(),''', "initialize remote text display storage")

pack_patch("crates/horizon-client/src/game/remote.rs", '''                if let Some(r) = self.remote.as_mut() {
                    if kind == "minecraft:player" {
                        let profile_cached = r.skin_textures.contains_key(&p.uuid);
                        let skin_url_present = r.skin_textures.get(&p.uuid)
                            .is_some_and(|textures| textures.skin.is_some());
                        log::info!(
                            "HorizonDiag: player_like_entity_spawned profile_textures_cached={} skin_url_present={}",
                            profile_cached, skin_url_present
                        );
                        r.level_players.insert(p.id, p.uuid);
                    } else {
                        r.level_players.remove(&p.id);
                    }
                }
                self.spawn_entity(p.id, &kind, p.position, rotation);''', '''                if let Some(r) = self.remote.as_mut() {
                    if kind == "minecraft:player" {
                        let profile_cached = r.skin_textures.contains_key(&p.uuid);
                        let skin_url_present = r.skin_textures.get(&p.uuid)
                            .is_some_and(|textures| textures.skin.is_some());
                        log::info!(
                            "HorizonDiag: player_like_entity_spawned profile_textures_cached={} skin_url_present={}",
                            profile_cached, skin_url_present
                        );
                        r.level_players.insert(p.id, p.uuid);
                    } else {
                        r.level_players.remove(&p.id);
                    }
                    if kind == "minecraft:text_display" {
                        r.text_displays.insert(p.id, RemoteTextDisplay::default());
                        log::info!("HorizonDiag: text_display_spawned entity_id={}", p.id);
                    } else if kind == "minecraft:item_display" {
                        r.item_displays.insert(p.id, RemoteItemDisplay::default());
                        log::info!("HorizonDiag: item_display_spawned entity_id={}", p.id);
                    } else if kind == "minecraft:block_display" {
                        r.block_displays.insert(p.id, RemoteBlockDisplay::default());
                        log::info!("HorizonDiag: block_display_spawned entity_id={}", p.id);
                    }
                }
                self.spawn_entity(p.id, &kind, p.position, rotation);''', "track display entities on spawn")

pack_patch("crates/horizon-client/src/game/remote.rs", '''            C::SetEntityData(p) => {
                // Entity.DATA_SHARED_FLAGS_ID, DATA_CUSTOM_NAME and
                // DATA_CUSTOM_NAME_VISIBLE: server NPCs and holograms rely on
                // these instead of saved NBT.
                if let Some(m) = self.mobs.iter_mut().find(|m| m.id == p.id as u32)''', '''            C::SetEntityData(p) => {
                let item_display_item_update = p.values.iter().find_map(|(index, serializer, value)| {
                    if (*index, *serializer) == (23, 7) {
                        if let Value::Item(stack) = value { Some(self.to_stack(stack)) } else { None }
                    } else { None }
                });
                let item_display_context_update = p.values.iter().find_map(|(index, serializer, value)| {
                    if (*index, *serializer) == (24, 0) {
                        if let Value::Byte(context) = value { Some((*context).clamp(0, 9) as u8) } else { None }
                    } else { None }
                });
                let block_display_state_update = p.values.iter().find_map(|(index, serializer, value)| {
                    if (*index, *serializer) == (23, 14) {
                        if let Value::Id(state) = value {
                            Some(horizon_world::registry::BlockStateId((*state).max(0) as u32))
                        } else { None }
                    } else { None }
                });
                if let Some(remote) = self.remote.as_mut() {
                    if let Some(display) = remote.text_displays.get_mut(&p.id) {
                        for (index, serializer, value) in &p.values {
                            match (*index, *serializer, value) {
                                (23, 5, Value::Text(tag)) => display.text = Some(remote_text(tag)),
                                (24, 1, Value::Int(width)) => display.line_width = (*width).clamp(1, 2048),
                                (25, 1, Value::Int(color)) => display.background_color = *color,
                                (26, 0, Value::Byte(opacity)) => display.text_opacity = *opacity,
                                (27, 0, Value::Byte(flags)) => display.flags = *flags,
                                _ => {}
                            }
                        }
                        apply_display_transform(&mut display.transform, &p.values);
                    }
                    if let Some(display) = remote.item_displays.get_mut(&p.id) {
                        if let Some(item) = item_display_item_update { display.item = item; }
                        if let Some(context) = item_display_context_update { display.transform_context = context; }
                        apply_display_transform(&mut display.transform, &p.values);
                    }
                    if let Some(display) = remote.block_displays.get_mut(&p.id) {
                        if let Some(state) = block_display_state_update { display.state = Some(state); }
                        apply_display_transform(&mut display.transform, &p.values);
                    }
                }
                if let Some(m) = self.mobs.iter_mut().find(|m| m.id == p.id as u32)''', "decode text display metadata")

pack_patch("crates/horizon-client/src/game/remote.rs", '''            r.entities.remove(&id);
            r.lerps.remove(&id);''', '''            r.entities.remove(&id);
            r.text_displays.remove(&id);
            r.item_displays.remove(&id);
            r.block_displays.remove(&id);
            r.lerps.remove(&id);''', "discard display state when entity despawns")

pack_patch("crates/horizon-client/src/game/remote.rs", '''            .filter_map(|(id, uuid)| Some((*id as u32, r.players.get(uuid)?.clone())))
            .collect()
    }''', '''            .filter_map(|(id, uuid)| Some((*id as u32, r.players.get(uuid)?.clone())))
            .collect()
    }
    /// Text displays use the same tracked entity positions as other remote entities.
    pub(super) fn remote_text_displays(&self) -> Vec<([f64; 3], horizon_client::text::Text, i32, u32, i8, i8, RemoteDisplayTransform)> {
        let Some(r) = self.remote.as_ref() else { return Vec::new(); };
        r.text_displays.iter().filter_map(|(id, display)| {
            Some((
                r.entities.get(id)?.position,
                display.text.clone()?,
                display.line_width,
                display.background_color as u32,
                display.text_opacity,
                display.flags,
                display.transform.clone(),
            ))
        }).collect()
    }
    pub(super) fn remote_item_displays(&self) -> Vec<(u32, [f64; 3], horizon_client::items::ItemStack, u8, RemoteDisplayTransform)> {
        let Some(r) = self.remote.as_ref() else { return Vec::new(); };
        r.item_displays.iter().filter_map(|(id, display)| {
            Some((*id as u32, r.entities.get(id)?.position, display.item.clone()?, display.transform_context, display.transform.clone()))
        }).collect()
    }
    pub(super) fn remote_block_displays(&self) -> Vec<(u32, [f64; 3], BlockStateId, RemoteDisplayTransform)> {
        let Some(r) = self.remote.as_ref() else { return Vec::new(); };
        r.block_displays.iter().filter_map(|(id, display)| {
            Some((*id as u32, r.entities.get(id)?.position, display.state?, display.transform.clone()))
        }).collect()
    }''', "expose text, item and block display state to renderers")

pack_patch("crates/horizon-client/src/game/names.rs", '''            tags.push(NameTag {
                at: p,
                lines,
                light: l,
                discrete,
            });''', '''            tags.push(NameTag {
                at: p,
                lines,
                light: l,
                discrete,
                background_color: 0x3F000000,
                text_opacity: 255,
                see_through: false,
                display_transform: None,
            });''', "add display-compatible defaults to mob nametags")

pack_patch("crates/horizon-client/src/game/names.rs", '''        // Armor stands name themselves: an invisible one with a visible name
        // is a hologram (its model is skipped, its name tag is not).''', '''        // TextDisplay is a first-class server entity, not a mob nametag.
        // Render its component using the same font atlas, preserving styles.
        for (position, text, line_width, background, opacity, flags, transform) in self.remote_text_displays() {
            let at = glam::DVec3::new(position[0], position[1], position[2]);
            if at.distance_squared(camera) > 128.0 * 128.0 {
                continue;
            }
            let text = text.resolve_legacy();
            let plain = text.plain();
            if plain.is_empty() {
                continue;
            }
            let chars: Vec<char> = plain.chars().collect();
            let max_width = line_width.clamp(1, 2048) as f32;
            let mut split_lines: Vec<(Text, f64)> = Vec::new();
            let mut start = 0usize;
            let mut width = 0.0f32;
            for (i, ch) in chars.iter().copied().enumerate() {
                if ch == '\\n' {
                    split_lines.push((text.slice(start, i), 0.0));
                    start = i + 1;
                    width = 0.0;
                    continue;
                }
                let advance = self.gui.atlas.glyph(ch).map_or(6.0, |g| g.1);
                if i > start && width + advance > max_width {
                    split_lines.push((text.slice(start, i), 0.0));
                    start = i;
                    width = advance;
                } else {
                    width += advance;
                }
            }
            if start < chars.len() {
                split_lines.push((text.slice(start, chars.len()), 0.0));
            }
            split_lines.retain(|(line, _)| !line.is_empty());
            let count = split_lines.len();
            for (i, (_, lift)) in split_lines.iter_mut().enumerate() {
                *lift = ((count as f64 - 1.0) / 2.0 - i as f64) * 0.225;
            }
            if split_lines.is_empty() {
                continue;
            }
            let background_color = if flags & 0x04 != 0 { 0x40000000 } else { background };
            // Text opacity is an unsigned alpha byte; -1 on the wire means 255.
            let text_opacity = opacity as u8;
            let light = self.world.light(
                position[0].floor() as i32,
                position[1].floor() as i32,
                position[2].floor() as i32,
            );
            tags.push(NameTag {
                at,
                lines: split_lines,
                light,
                discrete: false,
                background_color,
                text_opacity,
                see_through: flags & 0x02 != 0,
                display_transform: Some(transform),
            });
        }
        // Armor stands name themselves: an invisible one with a visible name
        // is a hologram (its model is skipped, its name tag is not).''', "render server text display entities")

pack_patch("crates/horizon-client/src/game/names.rs", '''            tags.push(NameTag {
                at: glam::DVec3::new(a.position.x, a.position.y + height + 0.5, a.position.z),
                lines,
                light: l,
                discrete: false,
            });''', '''            tags.push(NameTag {
                at: glam::DVec3::new(a.position.x, a.position.y + height + 0.5, a.position.z),
                lines,
                light: l,
                discrete: false,
                background_color: 0x3F000000,
                text_opacity: 255,
                see_through: false,
                display_transform: None,
            });''', "add display-compatible defaults to armor stand nametags")
pack_patch("crates/horizon-client/src/game/names.rs", '''        for tag in self.name_tags(camera, alpha) {
            let o = tag.at - camera;
            if tag.discrete {
                self.name_tag_quads(o, &tag.lines, FADED, true, 0, vertex_light(tag.light), out);
                continue;
            }
            // LightCoordsUtil.lightCoordsWithEmission(lightCoords, 2).
            let lit = (tag.light >> 4).max(2) << 4 | (tag.light & 15).max(2);
            self.name_tag_quads(o, &tag.lines, 0xFF, false, 0, vertex_light(lit), out);
            // RenderPipelines.TEXT_SEE_THROUGH: no depth test, no lightmap.
            self.name_tag_quads(o, &tag.lines, FADED, true, 2, 0, see_through);
        }''', '''        for tag in self.name_tags(camera, alpha) {
            let o = tag.at - camera;
            let out_start = out.len();
            let see_through_start = see_through.len();
            if tag.discrete {
                self.name_tag_quads(o, &tag.lines, FADED, true, 0, vertex_light(tag.light), tag.background_color, out);
            } else if tag.see_through {
                // Text displays with see_through use only the no-depth-test pipeline.
                self.name_tag_quads(o, &tag.lines, u32::from(tag.text_opacity), true, 2, 0, tag.background_color, see_through);
            } else {
                // LightCoordsUtil.lightCoordsWithEmission(lightCoords, 2).
                let lit = (tag.light >> 4).max(2) << 4 | (tag.light & 15).max(2);
                self.name_tag_quads(o, &tag.lines, u32::from(tag.text_opacity), false, 0, vertex_light(lit), tag.background_color, out);
                // RenderPipelines.TEXT_SEE_THROUGH: no depth test, no lightmap.
                let faded = u32::from(tag.text_opacity) * FADED / 0xFF;
                self.name_tag_quads(o, &tag.lines, faded, true, 2, 0, tag.background_color, see_through);
            }
            if let Some(transform) = &tag.display_transform {
                let center = o.as_vec3();
                let matrix = transform.matrix();
                for vertex in &mut out[out_start..] {
                    let p = glam::Vec3::from_array(vertex.pos);
                    vertex.pos = (center + matrix.transform_point3(p - center)).to_array();
                }
                for vertex in &mut see_through[see_through_start..] {
                    let p = glam::Vec3::from_array(vertex.pos);
                    vertex.pos = (center + matrix.transform_point3(p - center)).to_array();
                }
            }
        }''', "render display opacity and see-through flags")

pack_patch("crates/horizon-client/src/game/names.rs", '''        light: u32,
        out: &mut Vec<Vertex>,
    ) {''', '''        light: u32,
        background_color: u32,
        out: &mut Vec<Vertex>,
    ) {''', "support display background colours in text geometry")

pack_patch("crates/horizon-client/src/game/names.rs", '''        // Options.getBackgroundOpacity(0.25) black, as ARGB.color.
        const BACKGROUND: u32 = 0x3F00_0000;
        let forward = glam::DVec3::from_array(view_vector(self.yaw, self.pitch));''', '''        let forward = glam::DVec3::from_array(view_vector(self.yaw, self.pitch));''', "use name tag supplied background color")

pack_patch("crates/horizon-client/src/game/names.rs", '''                    BACKGROUND,
                    true,
                    2,''', '''                    background_color,
                    true,
                    2,''', "apply per-display background color")

pack_patch("crates/horizon-client/src/game/names.rs", '''    light: u8,
    discrete: bool,
}''', '''    light: u8,
    discrete: bool,
    background_color: u32,
    text_opacity: u8,
    see_through: bool,
    display_transform: Option<super::remote::RemoteDisplayTransform>,
}''', "store text display render properties")

pack_patch("crates/horizon-client/src/item_tooltip.rs", '''    if let Some(name) = stack.custom_name() {
        return name.to_owned();
    }
    if stack.data().is_some_and(|d| d.ominous_banner) {''', '''    if let Some(name) = stack.custom_name() {
        return name.to_owned();
    }
    if let Some(name) = stack.data().and_then(|data| data.other("minecraft:item_name")) {
        return crate::item_data::text_component_plain(name);
    }
    if stack.data().is_some_and(|d| d.ominous_banner) {''', "display server item_name before the vanilla item translation")

pack_patch("crates/horizon-client/src/game/packs.rs", '''        lang::set(lang::Language::load(&[&root.to_path_buf()], &lang::code()));''', '''        let selected_language = lang::code();
        let loaded_language = lang::Language::load(&[&root.to_path_buf()], &selected_language);
        let active_server_packs = self.remote.as_ref().map_or(0, |remote| remote.server_packs.len());
        log::info!(
            "Horizon: resource language reload locale={} translation_keys={} active_server_packs={}",
            selected_language, loaded_language.len(), active_server_packs
        );
        lang::set(loaded_language);''', "log active server-pack language reload")


