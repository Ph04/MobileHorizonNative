# Ordered patch step. See README.md in this directory for scope and order.
# Make multiplayer limitations visible in the game log rather than silently
# falling back to default skins or closing a server menu without context.
replace_once(skins,
    """    let response = ureq::AgentBuilder::new()
        .timeout(std::time::Duration::from_secs(15))
        .user_agent("Java")
        .build()
        .get(url)
        .call()
        .ok()?;
    let mut body = Vec::new();
    response
        .into_reader()
        .take(16 << 20)
        .read_to_end(&mut body)
        .ok()?;
    Some(body)""",
    """    let texture = texture_hash(url).unwrap_or_else(|| "unknown".into());
    let response = match ureq::AgentBuilder::new()
        .timeout(std::time::Duration::from_secs(15))
        .user_agent("Java")
        .build()
        .get(url)
        .call()
    {
        Ok(response) => response,
        Err(error) => {
            log::warn!("Horizon: player texture download failed for hash {texture}: {error}");
            return None;
        }
    };
    let mut body = Vec::new();
    if let Err(error) = response.into_reader().take(16 << 20).read_to_end(&mut body) {
        log::warn!("Horizon: player texture read failed for hash {texture}: {error}");
        return None;
    }
    if body.is_empty() {
        log::warn!("Horizon: player texture download returned an empty body for hash {texture}");
        return None;
    }
    Some(body)""")

replace_once(remote,
    """                                    r.skin_textures.entry(e.uuid).or_insert(textures);
                                }""",
    """                                    log::info!(
                                        "HorizonDiag: profile_textures_received signed={} skin_present={} cape_present={} elytra_present={}",
                                        textures.signed, textures.skin.is_some(),
                                        textures.cape.is_some(), textures.elytra.is_some()
                                    );
                                    r.skin_textures.entry(e.uuid).or_insert(textures);
                                } else {
                                    log::warn!(
                                        "HorizonDiag: player profile {name} did not provide a textures property; an NPC/player may depend on a server resource pack"
                                    );
                                }""")

replace_once(remote,
    """            _ => {
                // Unsupported menu: tell the server we closed it.
                self.send(S::ContainerClose(play::ContainerClose {""",
    """            _ => {
                // Unsupported menus can explain a blank server GUI; leave evidence in the
                // game log so this is distinguishable from a transport/protocol disconnect.
                log::warn!(
                    "Horizon: unsupported server menu '{}' (container id {}); sending ContainerClose",
                    menu, p.container
                );
                self.send(S::ContainerClose(play::ContainerClose {""")

# Removed: Horizon v0.2.50 now handles these packets in its native server_packs manager.


# Removed: Horizon v0.2.50 now handles these packets in its native server_packs manager.



replace_once(remote,
    """        self.send_suggestion_request();
        Ok(())
    }
    /// ClientSuggestionProvider.customSuggestion: the chat's completion""",
    """        self.update_remote_skins();
        self.send_suggestion_request();
        Ok(())
    }
    /// ClientSuggestionProvider.customSuggestion: the chat's completion""")

# Multiplayer diagnostics: log packet boundaries, custom-item component loss and
# profile-texture lifecycle without dumping raw payloads, chat, URLs or profile blobs.
replace_once(net,
    """fn receive_play(
""",
    """fn safe_resource_pack_origin(raw: &str) -> String {
    let Some((scheme, remainder)) = raw.split_once("://") else {
        return "<unparsed-url>".to_owned();
    };
    // Keep scheme and authority only; never log query strings, paths or userinfo.
    let authority = remainder
        .split(|c| c == '/' || c == '?' || c == '#')
        .next()
        .unwrap_or("unknown");
    let host = authority.rsplit('@').next().unwrap_or("unknown");
    format!("{scheme}://{host}")
}

fn receive_play(
""")

replace_once(net,
    """                let _ = send.send(Event::DecodeError(format!("{name}: {err}")));""",
    """                let _ = send.send(Event::DecodeError(format!(
                    "{name}: {err}; payload_len={} bytes",
                    payload.len()
                )));""")

# Removed: Horizon v0.2.50 now handles these packets in its native server_packs manager.


# Removed: Horizon v0.2.50 now handles these packets in its native server_packs manager.


replace_once(net,
    """        let (id, payload) = reader.recv().map_err(|e| e.to_string())?;
        match cfg::Clientbound::decode(id, &payload).map_err(|e| e.to_string())? {""",
    """        let (id, payload) = reader.recv().map_err(|e| e.to_string())?;
        let packet = match cfg::Clientbound::decode(id, &payload) {
            Ok(packet) => packet,
            Err(error) => {
                let name = horizon_protocol::ids::name(
                    horizon_protocol::State::Configuration,
                    horizon_protocol::Direction::Clientbound,
                    id,
                ).unwrap_or("?");
                let message = format!("{name}: {error}; payload_len={} bytes", payload.len());
                log::error!("HorizonDiag: configuration_packet_decode_failed {message}");
                return Err(message);
            }
        };
        match packet {""")

replace_once(net,
    """            cfg::Clientbound::SelectKnownPacks(_) => {
                reply(cfg::Serverbound::SelectKnownPacks(cfg::SelectKnownPacks {
                    packs: vec![],
                }))?
            }
            cfg::Clientbound::RegistryData(r) => registries.add(r),""",
    """            cfg::Clientbound::SelectKnownPacks(_) => {
                log::info!("HorizonDiag: known_pack_negotiation client_sent_empty_list");
                reply(cfg::Serverbound::SelectKnownPacks(cfg::SelectKnownPacks {
                    packs: vec![],
                }))?
            }
            cfg::Clientbound::RegistryData(r) => {
                let with_data = r.entries.iter().filter(|entry| entry.data.is_some()).count();
                log::info!(
                    "HorizonDiag: registry_received name={} entries={} entries_with_data={}",
                    r.registry, r.entries.len(), with_data
                );
                registries.add(r)
            }""")

replace_once(remote,
    """    pub(super) fn to_stack(&self, s: &Option<Stack>) -> Option<ItemStack> {
        let s = s.as_ref()?;
        let mut out = ItemStack {""",
    """    pub(super) fn to_stack(&self, s: &Option<Stack>) -> Option<ItemStack> {
        let s = s.as_ref()?;
        let ignored_components: Vec<i32> = s.added.iter()
            .map(|(kind, _)| *kind)
            .filter(|kind| !matches!(
                *kind,
                horizon_protocol::item::component::DAMAGE
                    | horizon_protocol::item::component::DYED_COLOR
                    | horizon_protocol::item::component::MAP_ID
                    | horizon_protocol::item::component::CUSTOM_NAME
                    | horizon_protocol::item::component::ITEM_NAME
                    | horizon_protocol::item::component::ITEM_MODEL
                    | horizon_protocol::item::component::LORE
                    | horizon_protocol::item::component::CUSTOM_DATA
                    | horizon_protocol::item::component::CUSTOM_MODEL_DATA
            ))
            .collect();
        let removed_unhandled_components: Vec<i32> = s.removed.iter().copied()
            .filter(|kind| !matches!(
                *kind,
                horizon_protocol::item::component::CUSTOM_NAME
                    | horizon_protocol::item::component::ITEM_NAME
                    | horizon_protocol::item::component::ITEM_MODEL
                    | horizon_protocol::item::component::LORE
                    | horizon_protocol::item::component::CUSTOM_DATA
                    | horizon_protocol::item::component::CUSTOM_MODEL_DATA
            ))
            .collect();
        if !ignored_components.is_empty() || !removed_unhandled_components.is_empty() {
            static COMPONENT_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                std::sync::atomic::AtomicUsize::new(0);
            if COMPONENT_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 128 {
                let source_menu = self.remote.as_ref()
                    .and_then(|r| r.menu.as_ref())
                    .map(|(_, menu)| menu.as_str())
                    .unwrap_or("inventory");
                log::warn!(
                    "HorizonDiag: item_stack_components_not_preserved source_menu={} item={} added_component_ids={:?} removed_unhandled_component_ids={:?}",
                    source_menu, self.item_name(s.item), ignored_components, removed_unhandled_components
                );
            }
        }
        let mut out = ItemStack {""")

replace_once(remote,
    """            C::ContainerSetContent(p) => {
                if let Some(r) = self.remote.as_mut() {
                    r.state_id = p.state;
                }
                for (slot, item) in p.items.iter().enumerate() {""",
    """            C::ContainerSetContent(p) => {
                let with_item_model = p.items.iter().flatten().filter(|stack|
                    stack.component(horizon_protocol::item::component::ITEM_MODEL).is_some()
                ).count();
                let with_custom_model_data = p.items.iter().flatten().filter(|stack|
                    stack.component(horizon_protocol::item::component::CUSTOM_MODEL_DATA).is_some()
                ).count();
                let with_any_component_patch = p.items.iter().flatten().filter(|stack|
                    !stack.added.is_empty() || !stack.removed.is_empty()
                ).count();
                log::info!(
                    "HorizonDiag: container_set_content window={} state={} slots={} stacks_with_item_model={} stacks_with_custom_model_data={} stacks_with_component_patch={}",
                    p.container, p.state, p.items.len(), with_item_model, with_custom_model_data, with_any_component_patch
                );
                if let Some(r) = self.remote.as_mut() {
                    r.state_id = p.state;
                }
                for (slot, item) in p.items.iter().enumerate() {""")

replace_once(remote,
    """        let target = self
            .remote
            .as_ref()
            .and_then(|r| r.use_target)
            .map(|t| (t[0], t[1], t[2]));""",
    """        log::info!(
            "HorizonDiag: open_screen_received menu_type={} window_id={}",
            menu, p.container
        );
        let target = self
            .remote
            .as_ref()
            .and_then(|r| r.use_target)
            .map(|t| (t[0], t[1], t[2]));""")

replace_once(remote,
    """                    if kind == "minecraft:player" {
                        r.level_players.insert(p.id, p.uuid);
                    } else {""",
    """                    if kind == "minecraft:player" {
                        let profile_cached = r.skin_textures.contains_key(&p.uuid);
                        let skin_url_present = r.skin_textures.get(&p.uuid)
                            .is_some_and(|textures| textures.skin.is_some());
                        log::info!(
                            "HorizonDiag: player_like_entity_spawned profile_textures_cached={} skin_url_present={}",
                            profile_cached, skin_url_present
                        );
                        r.level_players.insert(p.id, p.uuid);
                    } else {""")

replace_once(remote,
    """                    for u in p.uuids {
                        r.players.remove(&u);
                        r.tab_players.remove(&u);
                        r.skin_textures.remove(&u);""",
    """                    for u in p.uuids {
                        let had_textures = r.skin_textures.contains_key(&u);
                        let entity_still_present = r.level_players.values().any(|profile| *profile == u);
                        if had_textures || entity_still_present {
                            log::warn!(
                                "HorizonDiag: profile_removed had_texture_record={} matching_entity_still_present={}",
                                had_textures, entity_still_present
                            );
                        }
                        r.players.remove(&u);
                        r.tab_players.remove(&u);
                        r.skin_textures.remove(&u);""")

replace_once(remote,
    """    pub(super) fn store_skin(&mut self, location: String, rgba: Vec<u8>) {
        use horizon_client::hud::SKIN_SLOTS;""",
    """    pub(super) fn store_skin(&mut self, location: String, rgba: Vec<u8>) {
        log::info!(
            "HorizonDiag: player_texture_processed atlas_location={} rgba_bytes={}",
            location, rgba.len()
        );
        use horizon_client::hud::SKIN_SLOTS;""")

