# Ordered patch step. See README.md in this directory for scope and order.
# ---------------------------------------------------------------------------
# Bounded end-to-end resource diagnostics. Log packet metadata, decoded
# components, overlay/reload stages, and the actual geometry generated. Never
# log signed URLs, URL query parameters, profile blobs, or arbitrary packet bytes.
pack_patch("crates/horizon-client/src/net.rs", '''        let (id, payload) = reader.recv().map_err(|e| e.to_string())?;
        let packet = match play::Clientbound::decode(id, &payload) {''', '''        let (id, payload) = reader.recv().map_err(|e| e.to_string())?;
        let packet_name = horizon_protocol::ids::name(
            horizon_protocol::State::Play,
            horizon_protocol::Direction::Clientbound,
            id,
        ).unwrap_or("?");
        if packet_name.contains("resource_pack") || packet_name == "login" {
            log::info!(
                "HorizonDiag: packet_received phase=play id={} name={} payload_bytes={}",
                id, packet_name, payload.len()
            );
        }
        if packet_name == "set_entity_data" {
            static SET_ENTITY_DATA_PACKET_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                std::sync::atomic::AtomicUsize::new(0);
            if SET_ENTITY_DATA_PACKET_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 1600 {
                log::info!(
                    "HorizonDiag: packet_received phase=play id={} name={} payload_bytes={}",
                    id, packet_name, payload.len()
                );
            }
        }
        let packet = match play::Clientbound::decode(id, &payload) {''', "trace resource-pack and world-login packet receipt")

pack_patch("crates/horizon-client/src/net.rs", '''        let (id, payload) = reader.recv().map_err(|e| e.to_string())?;
        let packet = match cfg::Clientbound::decode(id, &payload) {''', '''        let (id, payload) = reader.recv().map_err(|e| e.to_string())?;
        let packet_name = horizon_protocol::ids::name(
            horizon_protocol::State::Configuration,
            horizon_protocol::Direction::Clientbound,
            id,
        ).unwrap_or("?");
        if packet_name.contains("resource_pack")
            || packet_name == "registry_data"
            || packet_name == "select_known_packs"
            || packet_name == "finish_configuration"
            || packet_name == "update_tags"
        {
            log::info!(
                "HorizonDiag: packet_received phase=configuration id={} name={} payload_bytes={}",
                id, packet_name, payload.len()
            );
        }
        let packet = match cfg::Clientbound::decode(id, &payload) {''', "trace configuration resource and registry packet receipt")

pack_patch("crates/horizon-client/src/net.rs", '''    let mut registries = Dynamic::default();
    let reply = |p: cfg::Serverbound| -> Result<(), String> {''', '''    let mut registries = Dynamic::default();
    let mut diagnostic_pack_offers = 0usize;
    let reply = |p: cfg::Serverbound| -> Result<(), String> {''', "count configuration phase resource-pack offers")

pack_patch("crates/horizon-client/src/net.rs", '''            cfg::Clientbound::ResourcePackPush(p) => {
                let id = p.id;
                let url = p.url;
                let hash = p.hash;
                let required = p.required;
                let supported_url = supported_pack_url(&url);
                log::info!("Horizon: server resource-pack offer id={id:032x} state=configuration required={required} hash_present={} origin={}", !hash.is_empty(), safe_resource_pack_origin(&url));''', '''            cfg::Clientbound::ResourcePackPush(p) => {
                diagnostic_pack_offers += 1;
                let id = p.id;
                let url = p.url;
                let hash = p.hash;
                let required = p.required;
                let supported_url = supported_pack_url(&url);
                log::info!("HorizonDiag: resource_pack_offer_decoded phase=configuration offer_number={} id={id:032x} required={required} hash_present={} origin={}", diagnostic_pack_offers, !hash.is_empty(), safe_resource_pack_origin(&url));''', "log configuration resource-pack offer count")

pack_patch("crates/horizon-client/src/net.rs", '''            cfg::Clientbound::FinishConfiguration(_) => {
                reply(cfg::Serverbound::FinishConfiguration(Empty))?;
                return Ok(registries);
            }''', '''            cfg::Clientbound::FinishConfiguration(_) => {
                log::info!(
                    "HorizonDiag: configuration_phase_complete resource_pack_offers={}",
                    diagnostic_pack_offers
                );
                reply(cfg::Serverbound::FinishConfiguration(Empty))?;
                return Ok(registries);
            }''', "summarize configuration resource offers and registries")

# Entity metadata is logged after decode, including item IDs and component IDs,
# while text previews are short and escaped. No NBT, texture URLs or profile blobs.
pack_patch("crates/horizon-client/src/game/remote.rs", '''                }
                let item_display_item_update = p.values.iter().find_map(|(index, serializer, value)| {''', '''                }
                let diagnostic_entity_kind = self.remote.as_ref()
                    .and_then(|remote| remote.entities.get(&p.id))
                    .map(|entity| entity.kind.clone())
                    .unwrap_or_else(|| "unknown".to_owned());
                let diagnostic_relevant = diagnostic_entity_kind.contains("frame")
                    || diagnostic_entity_kind.contains("display")
                    || p.values.iter().any(|(_, _, value)| matches!(value, Value::Item(_) | Value::Text(_)));
                if diagnostic_relevant {
                    static ENTITY_METADATA_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                        std::sync::atomic::AtomicUsize::new(0);
                    if ENTITY_METADATA_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 1600 {
                        let diagnostic_fields: Vec<String> = p.values.iter().map(|(index, serializer, value)| {
                            let kind = match value {
                                Value::Byte(_) => "byte",
                                Value::Int(_) => "int",
                                Value::Floats(_) => "floats",
                                Value::Text(_) => "text",
                                Value::Item(_) => "item",
                                Value::Id(_) => "id",
                                _ => "other",
                            };
                            match value {
                                Value::Item(stack) => {
                                    let detail = stack.as_ref().map(|stack| format!(
                                        "item={} count={} added_component_ids={:?} removed_component_ids={:?}",
                                        self.item_name(stack.item), stack.count,
                                        stack.added.iter().map(|(component, _)| *component).collect::<Vec<_>>(),
                                        stack.removed
                                    )).unwrap_or_else(|| "empty_stack".to_owned());
                                    format!("index={index} serializer={serializer} type={kind} {detail}")
                                }
                                Value::Text(tag) => {
                                    let plain = remote_text(tag).plain();
                                    let preview: String = plain.chars().take(96).collect();
                                    format!("index={index} serializer={serializer} type={kind} text_chars={} preview={:?}", plain.chars().count(), preview)
                                }
                                _ => format!("index={index} serializer={serializer} type={kind}"),
                            }
                        }).collect();
                        log::info!(
                            "HorizonDiag: entity_metadata_received entity_id={} kind={} field_count={} fields={:?}",
                            p.id, diagnostic_entity_kind, p.values.len(), diagnostic_fields
                        );
                    }
                }
                let item_display_item_update = p.values.iter().find_map(|(index, serializer, value)| {''', "log decoded metadata field types and safe item/text summaries")



pack_patch("crates/horizon-client/src/game/remote.rs", '''                    if let Some(display) = remote.text_displays.get_mut(&p.id) {
                        for (index, serializer, value) in &p.values {
                            match (*index, *serializer, value) {''', '''                    if let Some(display) = remote.text_displays.get_mut(&p.id) {
                        for (index, serializer, value) in &p.values {
                            match (*index, *serializer, value) {''', "anchor text display state application diagnostics")

pack_patch("crates/horizon-client/src/game/remote.rs", '''                        apply_display_transform(&mut display.transform, &p.values);
                    }
                    if let Some(display) = remote.item_displays.get_mut(&p.id) {''', '''                        apply_display_transform(&mut display.transform, &p.values);
                        static TEXT_DISPLAY_APPLIED_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                            std::sync::atomic::AtomicUsize::new(0);
                        if TEXT_DISPLAY_APPLIED_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 256 {
                            let plain = display.text.as_ref().map(|text| text.plain()).unwrap_or_default();
                            let preview: String = plain.chars().take(96).collect();
                            log::info!(
                                "HorizonDiag: text_display_state_applied entity_id={} text_present={} text_chars={} preview={:?} line_width={} background=0x{:08x} opacity={} flags=0x{:02x} translation={:?} scale={:?}",
                                p.id, display.text.is_some(), plain.chars().count(), preview,
                                display.line_width, display.background_color as u32,
                                display.text_opacity as u8, display.flags as u8,
                                display.transform.translation, display.transform.scale
                            );
                        }
                    }
                    if let Some(display) = remote.item_displays.get_mut(&p.id) {''', "log text display values after applying metadata")

pack_patch("crates/horizon-client/src/game/remote.rs", '''                    if let Some(display) = remote.item_displays.get_mut(&p.id) {
                        if let Some(item) = item_display_item_update { display.item = item; }
                        if let Some(context) = item_display_context_update { display.transform_context = context; }
                        apply_display_transform(&mut display.transform, &p.values);
                    }''', '''                    if let Some(display) = remote.item_displays.get_mut(&p.id) {
                        if let Some(item) = item_display_item_update { display.item = item; }
                        if let Some(context) = item_display_context_update { display.transform_context = context; }
                        apply_display_transform(&mut display.transform, &p.values);
                        static ITEM_DISPLAY_APPLIED_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                            std::sync::atomic::AtomicUsize::new(0);
                        if ITEM_DISPLAY_APPLIED_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 256 {
                            log::info!(
                                "HorizonDiag: item_display_state_applied entity_id={} item_present={} item_id={} transform_context={} translation={:?} scale={:?}",
                                p.id, display.item.is_some(),
                                display.item.as_ref().map_or("-", |stack| stack.item.as_ref()),
                                display.transform_context,
                                display.transform.translation, display.transform.scale
                            );
                        }
                    }''', "log item display values after applying metadata")

pack_patch("crates/horizon-client/src/game/remote.rs", '''                    if let Some(display) = remote.block_displays.get_mut(&p.id) {
                        if let Some(state) = block_display_state_update { display.state = Some(state); }
                        apply_display_transform(&mut display.transform, &p.values);
                    }''', '''                    if let Some(display) = remote.block_displays.get_mut(&p.id) {
                        if let Some(state) = block_display_state_update { display.state = Some(state); }
                        apply_display_transform(&mut display.transform, &p.values);
                        static BLOCK_DISPLAY_APPLIED_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                            std::sync::atomic::AtomicUsize::new(0);
                        if BLOCK_DISPLAY_APPLIED_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 256 {
                            log::info!(
                                "HorizonDiag: block_display_state_applied entity_id={} block_state_present={} translation={:?} scale={:?}",
                                p.id, display.state.is_some(),
                                display.transform.translation, display.transform.scale
                            );
                        }
                    }''', "log block display values after applying metadata")

# Log frame geometry and custom item-model output, rather than only entity creation.
pack_patch("crates/horizon-client/src/game/frames.rs", '''    pub(super) fn frame_geometry(&mut self, camera: glam::DVec3, out: &mut Vec<Vertex>) {
        let sprites = &self.gui.atlas.sprites;''', '''    pub(super) fn frame_geometry(&mut self, camera: glam::DVec3, out: &mut Vec<Vertex>) {
        let geometry_start = out.len();
        let diagnostic_frame_count = self.item_frames.len();
        let diagnostic_item_count = self.item_frames.iter().filter(|frame| frame.item.is_some()).count();
        let diagnostic_map_count = self.item_frames.iter().filter(|frame| frame.item.as_ref().and_then(super::maps::map_id).is_some()).count();
        let diagnostic_model_quads = self.frame_model.len() + self.glow_frame_model.len()
            + self.map_frame_model.len() + self.glow_map_frame_model.len();
        let diagnostic_atlas_sprites = self.gui.atlas.sprites.len();
        let sprites = &self.gui.atlas.sprites;''', "capture item-frame geometry input state")

pack_patch("crates/horizon-client/src/game/frames.rs", '''                    let Some(r) = sprites.get(sprite) else {
                        continue;
                    };''', '''                    let Some(r) = sprites.get(sprite) else {
                        static MISSING_FRAME_SPRITE_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                            std::sync::atomic::AtomicUsize::new(0);
                        if MISSING_FRAME_SPRITE_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 96 {
                            log::warn!(
                                "HorizonDiag: frame_model_sprite_missing frame_entity_id={} sprite={} model_quads={} atlas_sprites={}",
                                f.id, sprite, model.len(), sprites.len()
                            );
                        }
                        continue;
                    };''', "log unresolved item-frame model sprites")

pack_patch("crates/horizon-client/src/game/frames.rs", '''            self.submit_item_world(
                &state,
                m,
                horizon_client::block_entity_render::vertex_light(light),
                out,
            );
            self.cull_entity_vertices(out, entity_start);''', '''            self.submit_item_world(
                &state,
                m,
                horizon_client::block_entity_render::vertex_light(light),
                out,
            );
            self.cull_entity_vertices(out, entity_start);
            static FRAME_ITEM_RENDER_LOGGED: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<u32>>> =
                std::sync::OnceLock::new();
            let log_this_item = FRAME_ITEM_RENDER_LOGGED.get_or_init(|| std::sync::Mutex::new(std::collections::HashSet::new()))
                .lock()
                .map(|mut ids| ids.len() < 128 && ids.insert(id))
                .unwrap_or(false);
            if log_this_item {
                log::info!(
                    "HorizonDiag: frame_item_render_result frame_entity_id={} item_id={} count={} vertices_generated={} result={}",
                    id, stack.item, stack.count, out.len().saturating_sub(entity_start),
                    if out.len() > entity_start { "geometry_generated" } else { "zero_geometry" }
                );
            }''', "log actual item rendering results in item frames")

pack_patch("crates/horizon-client/src/game/frames.rs", '''        for (id, m, light) in maps {
            let entity_start = out.len();
            self.map_vertices(id, m, light, out);
            self.cull_entity_vertices(out, entity_start);
        }
    }
}''', '''        for (id, m, light) in maps {
            let entity_start = out.len();
            self.map_vertices(id, m, light, out);
            self.cull_entity_vertices(out, entity_start);
        }
        let diagnostic_signature = (
            diagnostic_frame_count,
            diagnostic_item_count,
            diagnostic_map_count,
            diagnostic_model_quads,
            diagnostic_atlas_sprites,
        );
        static LAST_FRAME_RENDER_SIGNATURE: std::sync::Mutex<Option<(usize, usize, usize, usize, usize)>> =
            std::sync::Mutex::new(None);
        if let Ok(mut last) = LAST_FRAME_RENDER_SIGNATURE.lock() {
            if last.as_ref() != Some(&diagnostic_signature) {
                *last = Some(diagnostic_signature);
                log::info!(
                    "HorizonDiag: frame_render_snapshot frame_count={} with_items={} with_maps={} baked_model_quads={} atlas_sprites={} vertices_generated_this_pass={}",
                    diagnostic_frame_count, diagnostic_item_count, diagnostic_map_count,
                    diagnostic_model_quads, diagnostic_atlas_sprites, out.len().saturating_sub(geometry_start)
                );
            }
        }
    }
}''', "log item-frame geometry produced and resource lookup totals")

# Attach a source entity ID to text render state, then report whether actual
# text geometry was generated by the font atlas pipeline.
pack_patch("crates/horizon-client/src/game/remote.rs", '''    pub(super) fn remote_text_displays(&self) -> Vec<([f64; 3], horizon_client::text::Text, i32, u32, i8, i8, RemoteDisplayTransform)> {
        let Some(r) = self.remote.as_ref() else { return Vec::new(); };
        r.text_displays.iter().filter_map(|(id, display)| {
            Some((
                r.entities.get(id)?.position,''', '''    pub(super) fn remote_text_displays(&self) -> Vec<(i32, [f64; 3], horizon_client::text::Text, i32, u32, i8, i8, RemoteDisplayTransform)> {
        let Some(r) = self.remote.as_ref() else { return Vec::new(); };
        r.text_displays.iter().filter_map(|(id, display)| {
            Some((
                *id,
                r.entities.get(id)?.position,''', "carry display entity IDs into text geometry")

pack_patch("crates/horizon-client/src/game/names.rs", '''        for (position, text, line_width, background, opacity, flags, transform) in self.remote_text_displays() {
            let at = glam::DVec3::new(position[0], position[1], position[2]);''', '''        for (entity_id, position, text, line_width, background, opacity, flags, transform) in self.remote_text_displays() {
            let at = glam::DVec3::new(position[0], position[1], position[2]);''', "retain text display entity IDs in renderer")

pack_patch("crates/horizon-client/src/game/names.rs", '''            tags.push(NameTag {
                at: p,
                lines,
                light: l,
                discrete,
                background_color: 0x3F000000,
                text_opacity: 255,
                see_through: false,
                display_transform: None,
            });''', '''            tags.push(NameTag {
                at: p,
                lines,
                light: l,
                discrete,
                background_color: 0x3F000000,
                text_opacity: 255,
                see_through: false,
                display_transform: None,
                source_entity_id: None,
            });''', "mark mob name tags as non-display text")

pack_patch("crates/horizon-client/src/game/names.rs", '''            tags.push(NameTag {
                at: glam::DVec3::new(a.position.x, a.position.y + height + 0.5, a.position.z),
                lines,
                light: l,
                discrete: false,
                background_color: 0x3F000000,
                text_opacity: 255,
                see_through: false,
                display_transform: None,
            });''', '''            tags.push(NameTag {
                at: glam::DVec3::new(a.position.x, a.position.y + height + 0.5, a.position.z),
                lines,
                light: l,
                discrete: false,
                background_color: 0x3F000000,
                text_opacity: 255,
                see_through: false,
                display_transform: None,
                source_entity_id: None,
            });''', "mark armor stand name tags as non-display text")

pack_patch("crates/horizon-client/src/game/names.rs", '''                see_through: flags & 0x02 != 0,
                display_transform: Some(transform),
            });''', '''                see_through: flags & 0x02 != 0,
                display_transform: Some(transform),
                source_entity_id: Some(entity_id),
            });''', "retain source IDs for text display geometry diagnostics")

pack_patch("crates/horizon-client/src/game/names.rs", '''    display_transform: Option<super::remote::RemoteDisplayTransform>,
}''', '''    display_transform: Option<super::remote::RemoteDisplayTransform>,
    source_entity_id: Option<i32>,
}''', "store text display source entity ID")

pack_patch("crates/horizon-client/src/game/names.rs", '''            if let Some(transform) = &tag.display_transform {
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
        }''', '''            if let Some(transform) = &tag.display_transform {
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
            if let Some(entity_id) = tag.source_entity_id {
                static TEXT_DISPLAY_GEOMETRY_LOGGED: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<i32>>> =
                    std::sync::OnceLock::new();
                let log_this = TEXT_DISPLAY_GEOMETRY_LOGGED.get_or_init(|| std::sync::Mutex::new(std::collections::HashSet::new()))
                    .lock()
                    .map(|mut ids| ids.len() < 128 && ids.insert(entity_id))
                    .unwrap_or(false);
                if log_this {
                    log::info!(
                        "HorizonDiag: text_display_geometry_result entity_id={} lines={} depth_vertices={} see_through_vertices={} opacity={} see_through={} transformed={} result={}",
                        entity_id, tag.lines.len(), out.len().saturating_sub(out_start),
                        see_through.len().saturating_sub(see_through_start), tag.text_opacity,
                        tag.see_through, tag.display_transform.is_some(),
                        if out.len() > out_start || see_through.len() > see_through_start { "geometry_generated" } else { "zero_geometry" }
                    );
                }
            }
        }''', "log generated text display geometry")

# Trace overlay sources and reload stages so an absent pack offer is distinct
# from a downloaded pack which failed validation, overlay construction or reload.
pack_patch("crates/horizon-client/src/game/packs.rs", '''    let all = packs::discover(vanilla, &packs_dir());
    let mut enabled = packs::enabled(&all, selected);
    for (id, path) in server_packs {''', '''    let all = packs::discover(vanilla, &packs_dir());
    let mut enabled = packs::enabled(&all, selected);
    log::info!(
        "HorizonDiag: resource_overlay_build_start discovered_client_packs={} selected_client_packs={} server_pack_layers={}",
        all.len(), selected.len(), server_packs.len()
    );
    for (id, path) in server_packs {''', "trace active overlay inputs")

pack_patch("crates/horizon-client/src/game/packs.rs", '''    packs::resource_root(vanilla, &enabled, &cache_dir())
}''', '''    for (id, path) in server_packs {
        log::info!(
            "HorizonDiag: resource_overlay_server_pack id={id:032x} sha1={} zip_bytes={}",
            path.file_stem().and_then(|value| value.to_str()).unwrap_or("unknown"),
            std::fs::metadata(path).map(|metadata| metadata.len()).unwrap_or(0)
        );
    }
    let root = packs::resource_root(vanilla, &enabled, &cache_dir())?;
    log::info!(
        "HorizonDiag: resource_overlay_build_complete enabled_layers={} server_pack_layers={} overlay_created=true",
        enabled.len(), server_packs.len()
    );
    Ok(root)
}''', "trace overlay construction result")

pack_patch("crates/horizon-client/src/game/packs.rs", '''    fn reload_resources(&mut self, root: &Path) -> horizon_client::Result<()> {
        let render = std::sync::Arc::new(self.pack.assets.reload(root)?);
        let loaded_sprite_layers = render.sprite_layers.len();
        let mut atlas = HudAtlas::build(root)?;
        atlas.font.uniform = self.settings.force_unicode;''', '''    fn reload_resources(&mut self, root: &Path) -> horizon_client::Result<()> {
        log::info!("HorizonDiag: resource_reload_stage=assets_reload status=begin");
        let render = std::sync::Arc::new(self.pack.assets.reload(root)?);
        let loaded_sprite_layers = render.sprite_layers.len();
        log::info!(
            "HorizonDiag: resource_reload_stage=assets_reload status=success sprite_layers={}",
            loaded_sprite_layers
        );
        log::info!("HorizonDiag: resource_reload_stage=hud_atlas_build status=begin");
        let mut atlas = HudAtlas::build(root)?;
        log::info!(
            "HorizonDiag: resource_reload_stage=hud_atlas_build status=success atlas_sprites={} font_uniform_before={}",
            atlas.sprites.len(), atlas.font.uniform
        );
        atlas.font.uniform = self.settings.force_unicode;''', "trace asset and atlas reload stages")

pack_patch("crates/horizon-client/src/game/packs.rs", '''        let pack = super::PackResources::load(
            root,
            self.pack.assets.clone(),
            render,
            self.res.facts(),
            self.pack.context.biomes.clone(),
            self.pack.context.light.clone(),
        )?;''', '''        log::info!("HorizonDiag: resource_reload_stage=pack_resources_load status=begin");
        let pack = super::PackResources::load(
            root,
            self.pack.assets.clone(),
            render,
            self.res.facts(),
            self.pack.context.biomes.clone(),
            self.pack.context.light.clone(),
        )?;
        log::info!("HorizonDiag: resource_reload_stage=pack_resources_load status=success");''', "trace model and texture resource load stage")

pack_patch("crates/horizon-client/src/game/packs.rs", '''        self.streaming.reload_resources(&mut self.world, context);
        self.pending_uploads.clear();''', '''        self.streaming.reload_resources(&mut self.world, context);
        self.pending_uploads.clear();
        log::info!(
            "HorizonDiag: resource_reload_stage=streaming_refresh status=success server_pack_layers={} atlas_sprites={}",
            self.remote.as_ref().map_or(0, |remote| remote.server_packs.len()),
            self.gui.atlas.sprites.len()
        );''', "log completed streaming resource refresh")

pack_patch("crates/horizon-client/src/game/remote.rs", '''        log::info!("Horizon: applying server resource pack id={id:032x} required={required}");
        self.pending_server_pack_responses.push((id, phase));''', '''        log::info!(
            "HorizonDiag: server_resource_pack_activation id={id:032x} required={required} phase={:?} cache_file={} zip_bytes={} active_server_pack_layers={}",
            phase,
            path.file_name().and_then(|value| value.to_str()).unwrap_or("unknown"),
            std::fs::metadata(&path).map(|metadata| metadata.len()).unwrap_or(0),
            remote.server_packs.len()
        );
        self.pending_server_pack_responses.push((id, phase));''', "trace server pack activation and cache metadata")



