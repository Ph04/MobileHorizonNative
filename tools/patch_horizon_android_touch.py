#!/usr/bin/env python3
"""Patch the pinned Horizon Rust client so Android touch becomes mouse input, then add a NativeActivity cdylib entry point."""
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
repo_root = Path(__file__).resolve().parents[1]
game = root / "crates/horizon-client/src/game.rs"
lib = root / "crates/horizon-client/src/lib.rs"
audio = root / "crates/horizon-client/src/audio.rs"
skins = root / "crates/horizon-client/src/skins.rs"
remote = root / "crates/horizon-client/src/game/remote.rs"
names = root / "crates/horizon-client/src/game/names.rs"
item_draw = root / "crates/horizon-client/src/game/item_draw.rs"
net = root / "crates/horizon-client/src/net.rs"
client_cargo = root / "crates/horizon-client/Cargo.toml"
clipboard = root / "crates/horizon-client/src/clipboard.rs"
pad_menu = root / "crates/horizon-client/src/game/pad_menu.rs"
startup_menu = root / "crates/horizon-client/src/game/startup_menu.rs"
workspace_cargo = root / "Cargo.toml"

for path in (game, lib, audio, skins, remote, names, item_draw, net, client_cargo, clipboard, pad_menu, startup_menu, workspace_cargo):
    if not path.is_file():
        raise SystemExit(f"Missing pinned Horizon source file: {path}")

def replace_once(path: Path, old: str, new: str) -> None:
    content = path.read_text(encoding="utf-8")
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement for {old[:90]!r}, found {count}")
    path.write_text(content.replace(old, new, 1), encoding="utf-8")

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

replace_once(net,
    """            play::Clientbound::ResourcePackPush(p) => {
                // Declined: Horizon has no server resource packs.
                reply(play::Serverbound::ResourcePack(cfg::ResourcePackResponse {""",
    """            play::Clientbound::ResourcePackPush(p) => {
                // Resource-pack handling is not implemented yet. Keep the reason visible:
                // servers that require a pack may disconnect this client after the decline.
                log::warn!(
                    "Horizon: declined server resource pack in play (required={}, hash_present={}); download and application are not implemented yet",
                    p.required, !p.hash.is_empty()
                );
                reply(play::Serverbound::ResourcePack(cfg::ResourcePackResponse {""")

replace_once(net,
    """            cfg::Clientbound::ResourcePackPush(p) => {
                reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse {
                    id: p.id,
                    action: 1,
                }))?
            }""",
    """            cfg::Clientbound::ResourcePackPush(p) => {
                log::warn!(
                    "Horizon: declined server resource pack during configuration (required={}, hash_present={}); download and application are not implemented yet",
                    p.required, !p.hash.is_empty()
                );
                reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse {
                    id: p.id,
                    action: 1,
                }))?
            }""")


# Drive remote-player skin downloads independently of the player-list overlay.
# Horizon already receives texture properties and downloads skins for tab-list heads,
# but the world player's MobType stayed pinned to Steve and never used that result.
replace_once(remote,
    """    /// Puts a downloaded skin in an atlas skin slot: a free one, else the
    /// least recently shown.
    fn store_skin(&mut self, location: String, rgba: Vec<u8>) {""",
    """    /// Start skin downloads and apply them to live player entities even when
    /// the player-list overlay is closed. The shared downloader and atlas are
    /// the same ones used by the tab list.
    fn update_remote_entity_skins(&mut self) {
        let players = {
            let Some(remote) = self.remote.as_ref() else { return; };
            remote.level_players.iter().filter_map(|(&entity_id, &uuid)| {
                remote.skin_textures.get(&uuid)
                    .cloned()
                    .map(|textures| (entity_id as u32, uuid, textures, remote.profile))
            }).collect::<Vec<_>>()
        };
        let mut skins = Vec::with_capacity(players.len());
        for (entity_id, uuid, textures, profile) in players {
            let downloader = &mut self.skin_downloads;
            if let Some(location) = horizon_client::skins::lookup(
                &textures,
                uuid != profile,
                |kind, url| downloader.state(kind, url),
            ) {
                skins.push((entity_id, location));
            }
        }
        for (location, rgba) in self.skin_downloads.take_ready() {
            self.store_skin(location, rgba);
        }
        for (entity_id, location) in skins {
            if !self.skin_slots.iter().any(|(known, _)| known == &location) {
                continue;
            }
            if let Some(mob) = self.mobs.iter_mut().find(|mob| mob.id == entity_id) {
                if mob.kind.id != "minecraft:player" || mob.kind.texture == location {
                    continue;
                }
                let mut kind = (*mob.kind).clone();
                kind.texture = location;
                mob.kind = Arc::new(kind);
            }
        }
    }

    /// Puts a downloaded skin in an atlas skin slot: a free one, else the
    /// least recently shown.
    fn store_skin(&mut self, location: String, rgba: Vec<u8>) {""")

replace_once(remote,
    """        self.send_suggestion_request();
        Ok(())
    }
    /// ClientSuggestionProvider.customSuggestion: the chat's completion""",
    """        self.update_remote_entity_skins();
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

replace_once(net,
    """            play::Clientbound::ResourcePackPush(p) => {
                // Resource-pack handling is not implemented yet. Keep the reason visible:
                // servers that require a pack may disconnect this client after the decline.
                log::warn!(
                    "Horizon: declined server resource pack in play (required={}, hash_present={}); download and application are not implemented yet",
                    p.required, !p.hash.is_empty()
                );""",
    """            play::Clientbound::ResourcePackPush(p) => {
                log::warn!(
                    "HorizonDiag: server_resource_pack phase=play decision=decline id={} required={} hash_present={} hash_len={} origin={}",
                    p.id, p.required, !p.hash.is_empty(), p.hash.len(),
                    safe_resource_pack_origin(&p.url)
                );""")

replace_once(net,
    """            cfg::Clientbound::ResourcePackPush(p) => {
                log::warn!(
                    "Horizon: declined server resource pack during configuration (required={}, hash_present={}); download and application are not implemented yet",
                    p.required, !p.hash.is_empty()
                );""",
    """            cfg::Clientbound::ResourcePackPush(p) => {
                log::warn!(
                    "HorizonDiag: server_resource_pack phase=configuration decision=decline id={} required={} hash_present={} hash_len={} origin={}",
                    p.id, p.required, !p.hash.is_empty(), p.hash.len(),
                    safe_resource_pack_origin(&p.url)
                );""")

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
    """    fn store_skin(&mut self, location: String, rgba: Vec<u8>) {
        use horizon_client::hud::SKIN_SLOTS;""",
    """    fn store_skin(&mut self, location: String, rgba: Vec<u8>) {
        log::info!(
            "HorizonDiag: player_texture_processed atlas_location={} rgba_bytes={}",
            location, rgba.len()
        );
        use horizon_client::hud::SKIN_SLOTS;""")

replace_once(audio,
    """#[cfg(feature = "audio")]
fn open_device(
    device: &cpal::Device,
    mixer: Arc<Mutex<Mixer>>,
) -> Result<(cpal::Stream, cpal::SupportedStreamConfig)> {
    use cpal::SampleFormat as F;
    let config = device.default_output_config()?;
    let stream = match config.sample_format() {
        F::F32 => build_stream::<f32>(device, &config, mixer),
        F::F64 => build_stream::<f64>(device, &config, mixer),
        F::I16 => build_stream::<i16>(device, &config, mixer),
        F::I32 => build_stream::<i32>(device, &config, mixer),
        F::I8 => build_stream::<i8>(device, &config, mixer),
        F::U16 => build_stream::<u16>(device, &config, mixer),
        F::U32 => build_stream::<u32>(device, &config, mixer),
        F::U8 => build_stream::<u8>(device, &config, mixer),
        other => Err(format!("unsupported sample format {other}").into()),
    }?;
    stream.play()?;
    Ok((stream, config))
}""",
    """#[cfg(feature = "audio")]
fn open_device(
    device: &cpal::Device,
    mixer: Arc<Mutex<Mixer>>,
) -> Result<(cpal::Stream, cpal::SupportedStreamConfig)> {
    use cpal::SampleFormat as F;

    // Android may reject the preferred config: try the device's supported formats/rates.
    let preferred_rate = |range: &cpal::SupportedStreamConfigRange| {
        let min = range.min_sample_rate().0;
        let max = range.max_sample_rate().0;
        for rate in [48_000u32, 44_100, 32_000, 22_050] {
            if min <= rate && rate <= max { return rate; }
        }
        max
    };
    let mut configs: Vec<_> = device.supported_output_configs()?.collect();
    configs.sort_by_key(|range| {
        let format_priority = match range.sample_format() { F::F32 => 0u8, F::I16 => 1u8, _ => 2u8 };
        let channel_priority = if range.channels() >= 2 { 0u8 } else { 1u8 };
        (format_priority, preferred_rate(range).abs_diff(48_000), channel_priority)
    });

    let mut errors = Vec::new();
    for range in configs {
        let rate = preferred_rate(&range);
        let config = range.with_sample_rate(cpal::SampleRate(rate));
        let description = format!("{} Hz, {} channel(s), {}", config.sample_rate().0, config.channels(), config.sample_format());
        let stream = match config.sample_format() {
            F::F32 => build_stream::<f32>(device, &config, mixer.clone()),
            F::F64 => build_stream::<f64>(device, &config, mixer.clone()),
            F::I16 => build_stream::<i16>(device, &config, mixer.clone()),
            F::I32 => build_stream::<i32>(device, &config, mixer.clone()),
            F::I8 => build_stream::<i8>(device, &config, mixer.clone()),
            F::U16 => build_stream::<u16>(device, &config, mixer.clone()),
            F::U32 => build_stream::<u32>(device, &config, mixer.clone()),
            F::U8 => build_stream::<u8>(device, &config, mixer.clone()),
            other => Err(format!("unsupported sample format {other}").into()),
        };
        match stream {
            Ok(stream) => match stream.play() {
                Ok(()) => {
                    log::info!("Horizon: audio output {description}");
                    return Ok((stream, config));
                }
                Err(error) => errors.push(format!("{description}: stream start failed: {error}")),
            },
            Err(error) => errors.push(format!("{description}: {error}")),
        }
    }
    if errors.is_empty() {
        Err("Android audio device reported no usable output configurations".into())
    } else {
        Err(format!("no usable output audio configuration: {}", errors.join("; ")).into())
    }
}""")

replace_once(client_cargo, 'winit = "0.30.13"',
             'winit = { version = "0.30.13", features = ["android-native-activity"] }')

# Keep the desktop clipboard for desktop builds, but don't compile it on Android.
replace_once(client_cargo,
    'arboard = { version = "3.6.1", default-features = false, features = ["wayland-data-control"] }',
    'arboard = { version = "3.6.1", default-features = false, features = ["wayland-data-control"], optional = true }')
replace_once(client_cargo,
    'default = ["audio", "controllers"]',
    'default = ["audio", "controllers", "system-clipboard"]')
replace_once(client_cargo,
    'controllers = ["dep:gilrs", "dep:horizon-dualsense"]',
    'controllers = ["dep:gilrs", "dep:horizon-dualsense"]\nsystem-clipboard = ["dep:arboard"]')

# Keep the existing SystemClipboard type, but make its desktop-only backend optional.
# The Android NativeActivity is built with --no-default-features and uses memory clipboard.
clipboard_struct_old = """#[derive(Default)]
pub struct SystemClipboard {
    system: Option<arboard::Clipboard>,
    /// The text Horizon last copied (`horizon-ctl status`).
    copied: Option<String>,
}"""
clipboard_struct_new = """#[derive(Default)]
pub struct SystemClipboard {
    #[cfg(feature = "system-clipboard")]
    system: Option<arboard::Clipboard>,
    /// The text Horizon last copied (`horizon-ctl status`).
    copied: Option<String>,
}"""
replace_once(clipboard, clipboard_struct_old, clipboard_struct_new)

clipboard_method_old = """    fn system(&mut self) -> Option<&mut arboard::Clipboard> {
        if self.system.is_none() {
            self.system = arboard::Clipboard::new().ok();
        }
        self.system.as_mut()
    }"""
clipboard_method_new = """    #[cfg(feature = "system-clipboard")]
    fn system(&mut self) -> Option<&mut arboard::Clipboard> {
        if self.system.is_none() {
            self.system = arboard::Clipboard::new().ok();
        }
        self.system.as_mut()
    }"""
replace_once(clipboard, clipboard_method_old, clipboard_method_new)

clipboard_impl_old = """impl Clipboard for SystemClipboard {
    fn get(&mut self) -> String {
        let Some(system) = self.system() else {
            return self.copied.clone().unwrap_or_default();
        };
        match system.get_text() {
            Ok(text) => text,
            Err(arboard::Error::ContentNotAvailable) => String::new(),
            Err(_) => {
                // A lost connection (a restarted compositor): reconnect next time.
                self.system = None;
                String::new()
            }
        }
    }
    fn set(&mut self, text: &str) {
        if text.is_empty() {
            return;
        }
        self.copied = Some(text.to_owned());
        if let Some(system) = self.system()
            && system.set_text(text.to_owned()).is_err()
        {
            self.system = None;
        }
    }
}"""
clipboard_impl_new = """impl Clipboard for SystemClipboard {
    fn get(&mut self) -> String {
        #[cfg(feature = "system-clipboard")]
        {
            let Some(system) = self.system() else {
                return self.copied.clone().unwrap_or_default();
            };
            match system.get_text() {
                Ok(text) => text,
                Err(arboard::Error::ContentNotAvailable) => String::new(),
                Err(_) => {
                    // A lost connection (a restarted compositor): reconnect next time.
                    self.system = None;
                    self.copied.clone().unwrap_or_default()
                }
            }
        }
        #[cfg(not(feature = "system-clipboard"))]
        {
            self.copied.clone().unwrap_or_default()
        }
    }
    fn set(&mut self, text: &str) {
        if text.is_empty() {
            return;
        }
        self.copied = Some(text.to_owned());
        #[cfg(feature = "system-clipboard")]
        {
            if let Some(system) = self.system()
                && system.set_text(text.to_owned()).is_err()
            {
                self.system = None;
            }
        }
    }
}"""
replace_once(clipboard, clipboard_impl_old, clipboard_impl_new)

# The Android keyboard can edit title/menu fields before a world exists, including world names
# and multiplayer names/addresses. Use Horizon's own focused-field editing APIs.
replace_once(pad_menu,
    "    fn pad_osk_output(&mut self, out: osk::Output) {",
    "    pub(super) fn pad_osk_output(&mut self, out: osk::Output) {")

replace_once(startup_menu,
    "    pub(super) fn menu_event(",
    """    pub(super) fn android_keyboard_text(&mut self, text: &str, submit: bool) {
        if self.menu.pending.is_some() {
            return;
        }
        if self.menu.options_open {
            if let Some(gui) = self.gui.as_ref() {
                self.menu.options.type_text(&gui.atlas, text);
            }
            return;
        }
        let (Some(title), clipboard) =
            (self.menu.title.as_mut(), &mut self.menu.clipboard)
        else {
            return;
        };
        if title.focused_field().is_none() {
            return;
        }
        if title.has_caret_field() {
            title.field_shortcut(
                horizon_client::clipboard::EditShortcut::SelectAll,
                clipboard,
            );
            if text.is_empty() {
                title.field_key(winit::keyboard::KeyCode::Backspace, false, false);
            } else {
                title.type_text(text);
            }
        } else {
            if let Some((_, value, _)) = title.focused_field() {
                value.clear();
            }
            title.type_text(text);
        }
        if submit {
            title.confirm_edit();
        }
        self.save_menu_address();
    }

    pub(super) fn menu_event(""")

replace_once(lib, 'pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;',
'''pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

#[derive(Clone, Debug)]
pub enum AndroidInjectedInput {
    CursorMoved { x: f64, y: f64 },
    MouseButton { button: u32, pressed: bool, x: f64, y: f64 },
    MouseScroll { amount: f32, x: f64, y: f64 },
    /// Android KeyEvent key code and edge state from the touchscreen control layer.
    /// Relative movement from a finger drag; consumed as DeviceEvent::MouseMotion when captured.
    MouseMotion { dx: f64, dy: f64 },
    KeyInput { key_code: i32, pressed: bool },
    /// Text from Android's keyboard and whether to commit the focused editor.
    KeyboardText { text: String, submit: bool },
}

static HORIZON_ANDROID_GAMEPLAY: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

pub fn set_android_gameplay(active: bool) {
    HORIZON_ANDROID_GAMEPLAY.store(active, std::sync::atomic::Ordering::Relaxed);
}

pub fn android_is_gameplay() -> bool {
    HORIZON_ANDROID_GAMEPLAY.load(std::sync::atomic::Ordering::Relaxed)
}

static HORIZON_ANDROID_WORLD_ACTIVE: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

pub fn set_android_world_active(active: bool) {
    HORIZON_ANDROID_WORLD_ACTIVE.store(active, std::sync::atomic::Ordering::Relaxed);
}

pub fn android_has_world() -> bool {
    HORIZON_ANDROID_WORLD_ACTIVE.load(std::sync::atomic::Ordering::Relaxed)
}

static HORIZON_ANDROID_EVENT_LOOP_PROXY:
    std::sync::OnceLock<std::sync::Mutex<Option<winit::event_loop::EventLoopProxy<AndroidInjectedInput>>>> =
    std::sync::OnceLock::new();

pub fn set_android_event_loop_proxy(proxy: winit::event_loop::EventLoopProxy<AndroidInjectedInput>) {
    let slot = HORIZON_ANDROID_EVENT_LOOP_PROXY.get_or_init(|| std::sync::Mutex::new(None));
    if let Ok(mut guard) = slot.lock() {
        *guard = Some(proxy);
    }
}

pub fn inject_android_input(event: AndroidInjectedInput) {
    let Some(slot) = HORIZON_ANDROID_EVENT_LOOP_PROXY.get() else { return; };
    if let Ok(guard) = slot.lock() {
        if let Some(proxy) = guard.as_ref() {
            let _ = proxy.send_event(event);
        }
    }
}

#[cfg(target_os = "android")]
extern crate self as horizon_client;

#[cfg(target_os = "android")]
pub mod game;

#[cfg(target_os = "android")]
std::thread_local! {
    static HORIZON_ANDROID_APP: std::cell::RefCell<Option<winit::platform::android::activity::AndroidApp>> =
        const { std::cell::RefCell::new(None) };
}

#[cfg(target_os = "android")]
pub fn set_android_app(app: winit::platform::android::activity::AndroidApp) {
    HORIZON_ANDROID_APP.with(|slot| *slot.borrow_mut() = Some(app));
}

#[cfg(target_os = "android")]
pub fn android_app() -> Result<winit::platform::android::activity::AndroidApp> {
    HORIZON_ANDROID_APP.with(|slot| {
        slot.borrow().as_ref().cloned().ok_or_else(|| {
            std::io::Error::new(std::io::ErrorKind::NotConnected, "Android NativeActivity is not initialized").into()
        })
    })
}''')

# NativeGameActivity sets the private data root to filesDir/horizon, whereas the
# launcher imports/exports the actual prepared resource tree under filesDir/.local.
# Honor an absolute resource root passed by the Android Activity instead of resolving
# .local/horizon-client/26.3 relative to the game's save/log directory.
replace_once(game,
    '''            resources: if sibling.join("horizon-resources.json").exists() {
                sibling
            } else {
                PathBuf::from(".local/horizon-client/26.3")
            },''',
    '''            resources: std::env::var_os("HORIZON_RESOURCES")
                .map(PathBuf::from)
                .unwrap_or_else(|| {
                    if sibling.join("horizon-resources.json").exists() {
                        sibling
                    } else {
                        PathBuf::from(".local/horizon-client/26.3")
                    }
                }),''')

replace_once(game,
    'event::{DeviceEvent, DeviceId, ElementState, MouseButton, MouseScrollDelta, WindowEvent},',
    'event::{DeviceEvent, DeviceId, ElementState, MouseButton, MouseScrollDelta, TouchPhase, WindowEvent},')
replace_once(game,
    '    agent_pointer: bool,\n',
    '    agent_pointer: bool,\n    /// First active touchscreen pointer translated to mouse input.\n    touch_mouse: Option<u64>,\n    /// A simultaneous second pointer requests a right-click.\n    touch_auxiliary: Option<u64>,\n    touch_is_right: bool,\n')
replace_once(game, '            agent_pointer: false,\n',
             '            agent_pointer: false,\n            touch_mouse: None,\n            touch_auxiliary: None,\n            touch_is_right: false,\n')

impl_start = "impl ApplicationHandler for Game<'_> {"
idx = game.read_text(encoding="utf-8").find(impl_start)
if idx < 0:
    raise SystemExit("Could not find Game ApplicationHandler implementation")
injected_impl = """impl ApplicationHandler<horizon_client::AndroidInjectedInput> for Game<'_> {
    fn user_event(&mut self, event_loop: &ActiveEventLoop, event: horizon_client::AndroidInjectedInput) {
        let Some(id) = self.window.as_ref().map(|window| window.id()) else { return; };
        let device_id = DeviceId::dummy();
        match event {
            horizon_client::AndroidInjectedInput::CursorMoved { x, y } => {
                self.window_event(event_loop, id, WindowEvent::CursorMoved {
                    device_id,
                    position: winit::dpi::PhysicalPosition::new(x, y),
                });
            }
            horizon_client::AndroidInjectedInput::MouseButton { button, pressed, x, y } => {
                // A virtual pointer substitutes for the physical cursor on touch-only Android.
                // Set this before handling the press, otherwise the desktop capture check can
                // consume each click because Android cannot lock a hardware cursor.
                if self.screen == Screen::Playing && self.player.is_some() {
                    self.agent_pointer = true;
                }
                self.window_event(event_loop, id, WindowEvent::CursorMoved {
                    device_id,
                    position: winit::dpi::PhysicalPosition::new(x, y),
                });
                let button = match button {
                    1 => Some(MouseButton::Left),
                    2 => Some(MouseButton::Right),
                    4 => Some(MouseButton::Middle),
                    _ => None,
                };
                if let Some(button) = button {
                    self.window_event(event_loop, id, WindowEvent::MouseInput {
                        device_id,
                        state: if pressed { ElementState::Pressed } else { ElementState::Released },
                        button,
                    });
                }
            }
            horizon_client::AndroidInjectedInput::MouseScroll { amount, x, y } => {
                self.window_event(event_loop, id, WindowEvent::CursorMoved {
                    device_id,
                    position: winit::dpi::PhysicalPosition::new(x, y),
                });
                self.window_event(event_loop, id, WindowEvent::MouseWheel {
                    device_id,
                    delta: MouseScrollDelta::LineDelta(0.0, amount),
                    phase: TouchPhase::Moved,
                });
            }
            horizon_client::AndroidInjectedInput::MouseMotion { dx, dy } => {
                // Android generally cannot lock/grab the cursor like a desktop window.
                // Treat a finger drag as a grabbed pointer while a world is active, so
                // Game::apply_mouse_look consumes MouseHandler's relative-delta buffer.
                if self.focused && self.screen == Screen::Playing && self.player.is_some() {
                    self.agent_pointer = true;
                    self.mouse.on_move(0.0, 0.0, dx, dy, true, true);
                    self.last_input = Instant::now();
                }
            }
            horizon_client::AndroidInjectedInput::KeyInput { key_code, pressed } => {
                // Activity.dispatchKeyEvent is not delivered to the NativeActivity's
                // Winit queue. Translate Android key codes at the native input boundary.
                let key = match key_code {
                    4 | 111 => Some(KeyCode::Escape), // BACK / ESCAPE
                    7 => Some(KeyCode::Digit0),
                    8 => Some(KeyCode::Digit1),
                    9 => Some(KeyCode::Digit2),
                    10 => Some(KeyCode::Digit3),
                    11 => Some(KeyCode::Digit4),
                    12 => Some(KeyCode::Digit5),
                    13 => Some(KeyCode::Digit6),
                    14 => Some(KeyCode::Digit7),
                    15 => Some(KeyCode::Digit8),
                    16 => Some(KeyCode::Digit9),
                    19 => Some(KeyCode::ArrowUp),
                    20 => Some(KeyCode::ArrowDown),
                    21 => Some(KeyCode::ArrowLeft),
                    22 => Some(KeyCode::ArrowRight),
                    29 => Some(KeyCode::KeyA), 30 => Some(KeyCode::KeyB),
                    31 => Some(KeyCode::KeyC), 32 => Some(KeyCode::KeyD),
                    33 => Some(KeyCode::KeyE), 34 => Some(KeyCode::KeyF),
                    35 => Some(KeyCode::KeyG), 36 => Some(KeyCode::KeyH),
                    37 => Some(KeyCode::KeyI), 38 => Some(KeyCode::KeyJ),
                    39 => Some(KeyCode::KeyK), 40 => Some(KeyCode::KeyL),
                    41 => Some(KeyCode::KeyM), 42 => Some(KeyCode::KeyN),
                    43 => Some(KeyCode::KeyO), 44 => Some(KeyCode::KeyP),
                    45 => Some(KeyCode::KeyQ), 46 => Some(KeyCode::KeyR),
                    47 => Some(KeyCode::KeyS), 48 => Some(KeyCode::KeyT),
                    49 => Some(KeyCode::KeyU), 50 => Some(KeyCode::KeyV),
                    51 => Some(KeyCode::KeyW), 52 => Some(KeyCode::KeyX),
                    53 => Some(KeyCode::KeyY), 54 => Some(KeyCode::KeyZ),
                    55 => Some(KeyCode::Comma), 56 => Some(KeyCode::Period),
                    57 => Some(KeyCode::AltLeft), 58 => Some(KeyCode::AltRight),
                    59 => Some(KeyCode::ShiftLeft), 60 => Some(KeyCode::ShiftRight),
                    61 => Some(KeyCode::Tab), 62 => Some(KeyCode::Space),
                    66 | 160 => Some(KeyCode::Enter), 67 => Some(KeyCode::Backspace),
                    68 => Some(KeyCode::Backquote), 69 => Some(KeyCode::Minus),
                    70 => Some(KeyCode::Equal), 71 => Some(KeyCode::BracketLeft),
                    72 => Some(KeyCode::BracketRight), 73 => Some(KeyCode::Backslash),
                    74 => Some(KeyCode::Semicolon), 75 => Some(KeyCode::Quote),
                    76 => Some(KeyCode::Slash), 81 => Some(KeyCode::NumpadAdd),
                    92 => Some(KeyCode::PageUp), 93 => Some(KeyCode::PageDown),
                    112 => Some(KeyCode::Delete), 113 => Some(KeyCode::ControlLeft),
                    114 => Some(KeyCode::ControlRight), 115 => Some(KeyCode::CapsLock),
                    122 => Some(KeyCode::Home), 123 => Some(KeyCode::End),
                    124 => Some(KeyCode::Insert),
                    131 => Some(KeyCode::F1), 132 => Some(KeyCode::F2),
                    133 => Some(KeyCode::F3), 134 => Some(KeyCode::F4),
                    135 => Some(KeyCode::F5), 136 => Some(KeyCode::F6),
                    137 => Some(KeyCode::F7), 138 => Some(KeyCode::F8),
                    139 => Some(KeyCode::F9), 140 => Some(KeyCode::F10),
                    141 => Some(KeyCode::F11), 142 => Some(KeyCode::F12),
                    _ => None,
                };
                if let Some(key) = key {
                    if pressed {
                        self.physical_keys.insert(key);
                    } else {
                        self.physical_keys.remove(&key);
                    }
                    self.last_input = Instant::now();
                    // Deliver unbound keys to Horizon's mod/extension hotkey hooks as
                    // well as translating vanilla key bindings (custom controls may use R, G, etc.).
                    self.extras_key(key, pressed, false);
                    if self.screen == Screen::Playing
                        && pressed
                        && horizon_client::keybinds::key_name(key)
                            .is_some_and(|name| self.settings.keys.get("key.smoothCamera") == name)
                    {
                        self.smooth_camera = !self.smooth_camera;
                    }
                    let result = match self.settings.keys.translate(key) {
                        Some(Ok(mapped_key)) => self.mapped_key(mapped_key, pressed, false),
                        Some(Err(button)) if self.screen == Screen::Playing => self.play_mouse(button, pressed),
                        _ => Ok(()),
                    };
                    if let Err(error) = result {
                        log::warn!("Horizon: Android touchscreen key input failed: {error}");
                    }
                }
            }
            horizon_client::AndroidInjectedInput::KeyboardText { text, submit } => {
                if self.title.is_some()
                    && !matches!(self.screen, Screen::Options | Screen::WorldOptions)
                {
                    let (Some(title), clipboard) =
                        (self.title.as_mut(), &mut self.clipboard)
                    else { return; };
                    if title.focused_field().is_some() {
                        if title.has_caret_field() {
                            title.field_shortcut(
                                horizon_client::clipboard::EditShortcut::SelectAll,
                                clipboard,
                            );
                            if text.is_empty() {
                                title.field_key(KeyCode::Backspace, false, false);
                            } else {
                                title.type_text(&text);
                            }
                        } else {
                            if let Some((_, value, _)) = title.focused_field() {
                                value.clear();
                            }
                            title.type_text(&text);
                        }
                        if submit {
                            title.confirm_edit();
                        }
                    }
                } else if self.screen == Screen::Playing
                    && self.player.is_some()
                    && self.sign_edit.is_none()
                    && self.book.is_none()
                {
                    if !text.trim().is_empty() {
                        self.open_chat("");
                        self.chat_type(&text);
                        if submit {
                            self.chat_key_code(KeyCode::Enter, None);
                        }
                    }
                } else {
                    self.pad_osk_output(horizon_client::osk::Output::Text(text.clone()));
                    if submit {
                        self.pad_osk_output(horizon_client::osk::Output::Enter);
                    }
                }
            }
        }
        horizon_client::set_android_world_active(self.player.is_some() && self.title.is_none());
    }

"""
content = game.read_text(encoding="utf-8")
content = content[:idx] + content[idx:].replace(impl_start, injected_impl, 1)
idx = content.find("impl ApplicationHandler<horizon_client::AndroidInjectedInput> for Game<'_> {")
if idx < 0:
    raise SystemExit("Could not replace Game ApplicationHandler implementation")
guard = '''        if self.window.as_ref().is_none_or(|w| w.id() != id) {
            return;
        }
'''
gidx = content.find(guard, idx)
if gidx < 0:
    raise SystemExit("Could not find Game window event guard")
if content.find(guard, gidx + len(guard)) >= 0 and content.find(guard, gidx + len(guard)) < content.find("impl ApplicationHandler for App", idx):
    # Ensure we picked the first guard inside Game, not a later handler.
    pass
touch_handler = '''        if self.window.as_ref().is_none_or(|w| w.id() != id) {
            return;
        }

        // Android touch arrives as WindowEvent::Touch; Horizon screens consume
        // CursorMoved and MouseInput. Translate single-finger taps to left-clicks
        // and simultaneous two-finger taps to right-clicks.
        let touch_input = match &event {
            WindowEvent::Touch(touch) => Some((
                touch.id,
                touch.phase.clone(),
                touch.device_id.clone(),
                touch.location.clone(),
            )),
            _ => None,
        };
        if let Some((touch_id, phase, device_id, position)) = touch_input {
            let cursor_event = || WindowEvent::CursorMoved {
                device_id: device_id.clone(),
                position: position.clone(),
            };
            let click = |button: MouseButton, state: ElementState| WindowEvent::MouseInput {
                device_id: device_id.clone(),
                state,
                button,
            };
            match phase {
                TouchPhase::Started => {
                    if self.touch_mouse.is_none() {
                        self.touch_mouse = Some(touch_id);
                        self.touch_auxiliary = None;
                        self.touch_is_right = false;
                        self.window_event(event_loop, id, cursor_event());
                    } else if self.touch_mouse != Some(touch_id) && self.touch_auxiliary.is_none() {
                        self.touch_auxiliary = Some(touch_id);
                        self.touch_is_right = true;
                    }
                }
                TouchPhase::Moved if self.touch_mouse == Some(touch_id) => {
                    self.window_event(event_loop, id, cursor_event());
                }
                TouchPhase::Ended
                    if self.touch_mouse == Some(touch_id)
                        || self.touch_auxiliary == Some(touch_id) =>
                {
                    self.window_event(event_loop, id, cursor_event());
                    let button = if self.touch_is_right { MouseButton::Right } else { MouseButton::Left };
                    self.window_event(event_loop, id, click(button, ElementState::Pressed));
                    self.window_event(event_loop, id, click(button, ElementState::Released));
                    self.touch_mouse = None;
                    self.touch_auxiliary = None;
                    self.touch_is_right = false;
                }
                TouchPhase::Cancelled
                    if self.touch_mouse == Some(touch_id)
                        || self.touch_auxiliary == Some(touch_id) =>
                {
                    self.touch_mouse = None;
                    self.touch_auxiliary = None;
                    self.touch_is_right = false;
                }
                _ => {}
            }
            return;
        }
'''
content = content[:gidx] + content[gidx:].replace(guard, touch_handler, 1)
capture_condition = 'if pressed && !self.captured {'
if content.count(capture_condition) != 1:
    raise SystemExit(f"Expected one pointer-capture guard, found {content.count(capture_condition)}")
content = content.replace(
    capture_condition,
    'if pressed && !self.captured && !self.agent_pointer {',
    1,
)
game.write_text(content, encoding="utf-8");

# Use the game's cursor capture request as the virtual cursor visibility state.
replace_once(game,
    """        if capture && !self.focused {
            return;
        }""",
    """        if capture && !self.focused {
            return;
        }
        #[cfg(target_os = "android")]
        horizon_client::set_android_gameplay(capture);""")

# Publish whether Horizon is in the active world or currently displaying a menu.
# Java polls this lightweight atomic to hide its virtual cursor during gameplay.
replace_once(game,
    """        if let Err(e) = result {
            self.error = Some(e);
            event_loop.exit();
        }
    }""",
    """        if let Err(e) = result {
            self.error = Some(e);
            event_loop.exit();
        }
        horizon_client::set_android_world_active(self.player.is_some() && self.title.is_none());
    }""")

replace_once(game,
    "fn run_event_loop(mut game: Game<'_>) -> Result<()> {\n    EventLoop::new()?.run_app(&mut game)?;",
    """fn new_event_loop<T: 'static + Send>() -> Result<EventLoop<T>> {
    #[cfg(target_os = "android")]
    {
        use winit::platform::android::EventLoopBuilderExtAndroid;
        let mut builder = EventLoop::<T>::with_user_event();
        builder.with_android_app(crate::android_app()?);
        Ok(builder.build()?)
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(EventLoop::<T>::new()?)
    }
}

fn run_event_loop(mut game: Game<'_>) -> Result<()> {
    let event_loop = new_event_loop::<horizon_client::AndroidInjectedInput>()?;
    horizon_client::set_android_event_loop_proxy(event_loop.create_proxy());
    event_loop.run_app(&mut game)?;""")
replace_once(game,
    'impl ApplicationHandler for App {\n    fn resumed(&mut self, event_loop: &ActiveEventLoop) {',
    '''impl ApplicationHandler<horizon_client::AndroidInjectedInput> for App {
    fn user_event(&mut self, event_loop: &ActiveEventLoop, event: horizon_client::AndroidInjectedInput) {
        match (self, event) {
            (App::Loading(loading), horizon_client::AndroidInjectedInput::KeyboardText { text, submit }) => {
                loading.android_keyboard_text(&text, submit);
            }
            (App::Ready(game), event) => game.user_event(event_loop, event),
            (App::Loading(_), _) => {}
        }
    }
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {''')

replace_once(game,
    '    EventLoop::new()?.run_app(&mut app)?;',
    '''    let event_loop = new_event_loop::<horizon_client::AndroidInjectedInput>()?;
    horizon_client::set_android_event_loop_proxy(event_loop.create_proxy());
    event_loop.run_app(&mut app)?;''')

replace_once(workspace_cargo, '"crates/horizon-client",',
             '"crates/horizon-client", "crates/mobilehorizon-android",')

dst = root / "crates/mobilehorizon-android"
(dst / "src").mkdir(parents=True, exist_ok=True)
shutil.copyfile(repo_root / "android-native/Cargo.toml", dst / "Cargo.toml")
shutil.copyfile(repo_root / "android-native/src/lib.rs", dst / "src/lib.rs")

print("Patched Horizon Android touch input and created the NativeActivity cdylib target.")


# ---------------------------------------------------------------------------
# MobileHorizon: server resource packs and multiplayer profile texture lifetime
# Apply this overlay to the pinned upstream source before the Android build.
from pathlib import Path
import sys

pack_root = Path(sys.argv[1])
repo_root = Path(__file__).resolve().parent.parent
server_pack_module = repo_root / "tools" / "horizon_server_packs.rs"
if not server_pack_module.is_file():
    raise SystemExit("Missing tools/horizon_server_packs.rs")
(pack_root / "crates/horizon-client/src/server_packs.rs").write_text(
    server_pack_module.read_text(encoding="utf-8"), encoding="utf-8"
)
def pack_patch(rel, old, new, label):
    path = pack_root / rel
    data = path.read_text(encoding="utf-8")
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one source anchor, found {count} in {rel}")
    path.write_text(data.replace(old, new, 1), encoding="utf-8")

pack_patch("crates/horizon-client/Cargo.toml",
    'sha2 = "0.10.9"\n',
    'sha2 = "0.10.9"\nsha1 = "0.10"\nurl = "2.5"\n',
    "resource-pack SHA-1 dependency")

pack_patch("crates/horizon-client/src/lib.rs", '''pub mod skins;''', '''pub mod server_packs;
pub mod skins;''', "register server resource-pack module")

pack_patch("crates/horizon-client/src/net.rs",
    '''pub const NOT_SIGNED_IN: &str = "This server is in online mode. Sign in with your Microsoft account in the Horizon launcher to join it.";''',
    '''pub const NOT_SIGNED_IN: &str = "This server requires Microsoft/Minecraft online authentication. Sign in with a verified account to join this server; servers configured for offline mode still accept an offline nickname.";''',
    "explain account requirement only for this server")

pack_patch("crates/horizon-client/src/net.rs",
    '''            login::Clientbound::Hello(hello) => {
                status(send, &tr(lang, "connect.authorizing"));''',
    '''            login::Clientbound::Hello(hello) => {
                log::info!("Horizon: server login challenge auth_required={} microsoft_session_present={}", hello.should_authenticate, auth.is_some());
                if hello.should_authenticate && auth.is_none() {
                    log::warn!("Horizon: this server requested official online authentication; offline nickname remains supported on offline-mode servers");
                }
                status(send, &tr(lang, "connect.authorizing"));''',

    "diagnose online/offline authentication per server")

# Do not block an offline profile locally on a hybrid server's encryption challenge.
# A server that truly requires official online authentication remains responsible for
# rejecting the unauthenticated profile; this client never fabricates a Microsoft session.
pack_patch("crates/horizon-client/src/net.rs", '''                if hello.should_authenticate {
                    let account = auth.ok_or_else(|| NOT_SIGNED_IN.to_owned())?;
                    let hash = server_hash(&hello.server_id, &secret, &hello.public_key);
                    if let Err(err) = crate::auth::join_server(account, &hash) {
                        let reason = match err.reason() {
                            Ok(key) => tr(lang, key),
                            Err(message) => message.to_owned(),
                        };
                        return Err(plain(
                            &translatable("disconnect.loginFailedInfo", &[&reason]),
                            lang,
                        ));
                    }
                }''', '''                if hello.should_authenticate {
                    if let Some(account) = auth {
                        let hash = server_hash(&hello.server_id, &secret, &hello.public_key);
                        if let Err(err) = crate::auth::join_server(account, &hash) {
                            let reason = match err.reason() {
                                Ok(key) => tr(lang, key),
                                Err(message) => message.to_owned(),
                            };
                            return Err(plain(
                                &translatable("disconnect.loginFailedInfo", &[&reason]),
                                lang,
                            ));
                        }
                    } else {
                        log::warn!(
                            "Horizon: server requested online authentication but no Microsoft session is present; sending the normal encrypted login response and letting the server decide whether offline fallback is allowed"
                        );
                    }
                }''', "let hybrid servers decide offline fallback after encryption challenge")


pack_patch("crates/horizon-client/src/net.rs", '''use std::{
    sync::{
        Arc, Mutex,
        mpsc::{Receiver, Sender, channel},
    },
    thread::JoinHandle,
    time::Duration,
};''', '''use sha1::Digest;
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU8, Ordering},
        mpsc::{Receiver, Sender, channel},
    },
    thread::JoinHandle,
    time::Duration,
};
const PACK_STATE_CONFIG: u8 = 1;
const PACK_STATE_PLAY: u8 = 2;
const PACK_MAX_BYTES: u64 = 512 * 1024 * 1024;
const PACK_MAX_EXPANDED: u64 = 1024 * 1024 * 1024;
const PACK_MAX_ENTRIES: usize = 100_000;
''', "resource-pack networking imports")

pack_patch("crates/horizon-client/src/net.rs", '''    /// The server sent us back to configuration and finished it again.
    Reconfigured(Arc<Dynamic>),
    /// The connection ended.''', '''    /// The server sent us back to configuration and finished it again.
    Reconfigured(Arc<Dynamic>),
    ServerResourcePackReady { id: u128, path: PathBuf, required: bool },
    ServerResourcePackFailed { id: u128, required: bool, reason: String },
    ServerResourcePackPop { id: Option<u128> },
    /// The connection ended.''', "resource-pack events")

pack_patch("crates/horizon-client/src/net.rs", '''    /// HORIZON_NET_DEBUG=1 logs every packet sent from the game thread.
    debug: bool,
}''', '''    /// HORIZON_NET_DEBUG=1 logs every packet sent from the game thread.
    debug: bool,
    protocol_state: Arc<AtomicU8>,
}''', "network state field")

pack_patch("crates/horizon-client/src/net.rs", '''        let (send, events) = channel();
        let writer = Arc::new(Mutex::new(None));
        let counters = Arc::new(Mutex::new(None));
        let (thread_writer, thread_counters) = (writer.clone(), counters.clone());''', '''        let (send, events) = channel();
        let writer = Arc::new(Mutex::new(None));
        let counters = Arc::new(Mutex::new(None));
        let protocol_state = Arc::new(AtomicU8::new(0));
        let (thread_writer, thread_counters, thread_protocol_state) =
            (writer.clone(), counters.clone(), protocol_state.clone());''', "protocol state initialization")

pack_patch("crates/horizon-client/src/net.rs", '''                    &thread_writer,
                    &thread_counters,
                );''', '''                    &thread_writer,
                    &thread_counters,
                    &thread_protocol_state,
                );''', "network thread state argument")

pack_patch("crates/horizon-client/src/net.rs", '''            counters,
            debug,
        }''', '''            counters,
            debug,
            protocol_state,
        }''', "session state initialization")

pack_patch("crates/horizon-client/src/net.rs", '''    pub fn disconnect(&mut self) {
        if let Some(w) = self.writer.lock().unwrap().take() {''', '''    pub fn send_resource_pack_response(&self, id: u128, action: i32) {
        send_pack_status(&self.writer, &self.protocol_state, id, action);
    }
    pub fn disconnect(&mut self) {
        if let Some(w) = self.writer.lock().unwrap().take() {''', "resource-pack status method")

pack_patch("crates/horizon-client/src/net.rs", '''    },
}

pub struct Session {''', '''    },
}

fn supported_pack_url(url: &str) -> bool {
    crate::server_packs::is_supported_url(url)
}

fn download_server_pack(
    id: u128,
    url: &str,
    expected_sha1: &str,
    events: &Sender<Event>,
) -> Result<PathBuf, String> {
    log::info!(
        "Horizon: server resource-pack download start id={id:032x} origin={} sha1_present={}",
        safe_resource_pack_origin(url),
        !expected_sha1.is_empty()
    );
    let mut next_progress_log = 4 * 1024 * 1024u64;
    let result = crate::server_packs::fetch(
        &crate::logging::game_home(),
        id,
        url,
        expected_sha1,
        |downloaded, total| {
            let status = match total {
                Some(total) => format!(
                    "Downloading server resource pack: {} / {} MiB",
                    downloaded / (1024 * 1024),
                    total / (1024 * 1024)
                ),
                None => format!(
                    "Downloading server resource pack: {} MiB",
                    downloaded / (1024 * 1024)
                ),
            };
            if downloaded >= next_progress_log {
                match total {
                    Some(total) => log::info!(
                        "Horizon: server resource-pack download progress id={id:032x} bytes={downloaded} total={total}"
                    ),
                    None => log::info!(
                        "Horizon: server resource-pack download progress id={id:032x} bytes={downloaded} total=unknown"
                    ),
                }
                next_progress_log = downloaded.saturating_add(4 * 1024 * 1024);
            }
            let _ = events.send(Event::Status(status));
        },
    );
    match &result {
        Ok(path) => log::info!(
            "Horizon: server resource-pack download complete id={id:032x} cache_file={} ",
            path.file_name().and_then(|v| v.to_str()).unwrap_or("<unknown>")
        ),
        Err(error) => log::warn!(
            "Horizon: server resource-pack download error id={id:032x}: {error}"
        ),
    }
    result.map_err(|error| error.to_string())
}

fn send_pack_status(writer: &Mutex<Option<ConnWriter>>, state: &AtomicU8, id: u128, action: i32) {
    let bytes = if state.load(Ordering::Acquire) == PACK_STATE_CONFIG {
        cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action }).encode()
    } else {
        play::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action }).encode()
    };
    if let Some(w) = writer.lock().unwrap().as_mut() && w.send(&bytes).is_err() {
        log::warn!("Horizon: failed sending resource-pack status id={id:032x} action={action}");
        w.shutdown();
    }
}

pub struct Session {''', "validated server pack downloader")

pack_patch("crates/horizon-client/src/net.rs", '''    shared_writer: &Mutex<Option<ConnWriter>>,
    shared_counters: &Mutex<Option<Arc<Counters>>>,
) -> Result<String, String> {''', '''    shared_writer: &Arc<Mutex<Option<ConnWriter>>>,
    shared_counters: &Mutex<Option<Arc<Counters>>>,
    protocol_state: &Arc<AtomicU8>,
) -> Result<String, String> {''', "run signature")

pack_patch("crates/horizon-client/src/net.rs", '''    *shared_writer.lock().unwrap() = Some(writer);
    let registries = configure(&mut reader, shared_writer, lang, send)?;
    let _ = send.send(Event::Joined {''', '''    *shared_writer.lock().unwrap() = Some(writer);
    protocol_state.store(PACK_STATE_CONFIG, Ordering::Release);
    let registries = configure(&mut reader, shared_writer, lang, send)?;
    protocol_state.store(PACK_STATE_PLAY, Ordering::Release);
    let _ = send.send(Event::Joined {''', "initial configuration/play state")

pack_patch("crates/horizon-client/src/net.rs", '''    receive_play(&mut reader, shared_writer, lang, send)
}''', '''    receive_play(&mut reader, Arc::clone(shared_writer), Arc::clone(protocol_state), lang, send)
}''', "play state handoff")

pack_patch("crates/horizon-client/src/net.rs", '''fn receive_play(
    reader: &mut ConnReader,
    shared_writer: &Mutex<Option<ConnWriter>>,
    lang: &Language,
    send: &Sender<Event>,
) -> Result<String, String> {''', '''fn receive_play(
    reader: &mut ConnReader,
    shared_writer: Arc<Mutex<Option<ConnWriter>>>,
    protocol_state: Arc<AtomicU8>,
    lang: &Language,
    send: &Sender<Event>,
) -> Result<String, String> {''', "play packet reader signature")

pack_patch("crates/horizon-client/src/net.rs", '''    pub fn send(&self, packet: play::Serverbound) {
        if self.debug''', '''    pub fn send(&self, packet: play::Serverbound) {
        // Serialize the phase check with packet writes. A state check before
        // acquiring this mutex still allows a PLAY packet to slip behind the
        // ConfigurationAcknowledged packet if the game thread is preempted.
        let mut writer_guard = self.writer.lock().unwrap();
        if self.protocol_state.load(Ordering::Acquire) != PACK_STATE_PLAY {
            static SUPPRESSED_PHASE_PACKETS: std::sync::atomic::AtomicUsize =
                std::sync::atomic::AtomicUsize::new(0);
            if SUPPRESSED_PHASE_PACKETS.fetch_add(1, Ordering::Relaxed) < 16 {
                log::warn!(
                    "Horizon: suppressed PLAY packet during protocol reconfiguration: {:?}",
                    packet
                );
            }
            return;
        }
        if self.debug''', "serialize play phase check with packet writes")

pack_patch("crates/horizon-client/src/net.rs", '''        if let Some(w) = self.writer.lock().unwrap().as_mut()
            && w.send(&packet.encode()).is_err()
        {''', '''        if let Some(w) = writer_guard.as_mut()
            && w.send(&packet.encode()).is_err()
        {''', "reuse serialized writer guard for play packet writes")

pack_patch("crates/horizon-client/src/net.rs", '''            play::Clientbound::ResourcePackPush(p) => {
                log::warn!(
                    "HorizonDiag: server_resource_pack phase=play decision=decline id={} required={} hash_present={} hash_len={} origin={}",
                    p.id, p.required, !p.hash.is_empty(), p.hash.len(),
                    safe_resource_pack_origin(&p.url)
                );
                reply(play::Serverbound::ResourcePack(cfg::ResourcePackResponse {
                    id: p.id,
                    action: 1,
                }));
            }
            play::Clientbound::StartConfiguration(_) => {
                reply(play::Serverbound::ConfigurationAcknowledged(Empty));
                let registries = configure(reader, shared_writer, lang, send)?;
                let _ = send.send(Event::Reconfigured(Arc::new(registries)));
            }''', '''            play::Clientbound::ResourcePackPop(p) => {
                let _ = send.send(Event::ServerResourcePackPop { id: p.id });
            }
            play::Clientbound::ResourcePackPush(p) => {
                let id = p.id;
                let url = p.url;
                let hash = p.hash;
                let required = p.required;
                log::info!("Horizon: server resource-pack offer id={id:032x} state=play required={required} hash_present={} origin={}", !hash.is_empty(), safe_resource_pack_origin(&url));
                reply(play::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action: 3 }));
                let writer = Arc::clone(&shared_writer);
                let state = Arc::clone(&protocol_state);
                let events = send.clone();
                let started = std::thread::Builder::new().name("horizon-pack-download".into()).spawn(move || {
                    match download_server_pack(id, &url, &hash, &events) {
                        Ok(path) => {
                            send_pack_status(&writer, &state, id, 4);
                            let _ = events.send(Event::ServerResourcePackReady { id, path, required });
                        }
                        Err(reason) => {
                            send_pack_status(&writer, &state, id, if supported_pack_url(&url) { 2 } else { 5 });
                            log::warn!("Horizon: server resource-pack download failed id={id:032x} required={required}: {reason}");
                            let _ = events.send(Event::ServerResourcePackFailed { id, required, reason });
                            if required {
                                if let Some(w) = writer.lock().unwrap().as_mut() { w.shutdown(); }
                            }
                        }
                    }
                });
                if let Err(e) = started {
                    log::warn!("Horizon: cannot start resource-pack downloader id={id:032x}: {e}");
                    reply(play::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action: 2 }));
                    let _ = send.send(Event::ServerResourcePackFailed { id, required, reason: e.to_string() });
                }
            }
            play::Clientbound::StartConfiguration(_) => {
                // Change phase before the acknowledgement lets the server switch packet registries.
                protocol_state.store(PACK_STATE_CONFIG, Ordering::Release);
                reply(play::Serverbound::ConfigurationAcknowledged(Empty));
                let registries = configure(reader, &shared_writer, lang, send)?;
                protocol_state.store(PACK_STATE_PLAY, Ordering::Release);
                let _ = send.send(Event::Reconfigured(Arc::new(registries)));
            }''', "play resource-pack handling")

pack_patch("crates/horizon-client/src/net.rs", '''            cfg::Clientbound::SelectKnownPacks(_) => {
                log::info!("HorizonDiag: known_pack_negotiation client_sent_empty_list");
                reply(cfg::Serverbound::SelectKnownPacks(cfg::SelectKnownPacks {
                    packs: vec![],
                }))?
            }''', '''            // No registry cache is advertised yet; this is separate from server resource packs.
            cfg::Clientbound::SelectKnownPacks(p) => {
                log::info!("Horizon: known-pack negotiation offered={} supported=0; requesting full registries (not a resource-pack rejection)", p.packs.len());
                reply(cfg::Serverbound::SelectKnownPacks(cfg::SelectKnownPacks { packs: vec![] }))?
            }''', "known-pack negotiation diagnostics")

pack_patch("crates/horizon-client/src/net.rs", '''            cfg::Clientbound::ResourcePackPush(p) => {
                log::warn!(
                    "HorizonDiag: server_resource_pack phase=configuration decision=decline id={} required={} hash_present={} hash_len={} origin={}",
                    p.id, p.required, !p.hash.is_empty(), p.hash.len(),
                    safe_resource_pack_origin(&p.url)
                );
                reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse {
                    id: p.id,
                    action: 1,
                }))?
            }
            cfg::Clientbound::CookieRequest(q) => {''', '''            cfg::Clientbound::ResourcePackPush(p) => {
                let id = p.id;
                log::info!("Horizon: server resource-pack offer id={id:032x} state=configuration required={} hash_present={} origin={}", p.required, !p.hash.is_empty(), safe_resource_pack_origin(&p.url));
                reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action: 3 }))?;
                match download_server_pack(id, &p.url, &p.hash, send) {
                    Ok(path) => {
                        reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action: 4 }))?;
                        let _ = send.send(Event::ServerResourcePackReady { id, path, required: p.required });
                    }
                    Err(reason) => {
                        let action = if supported_pack_url(&p.url) { 2 } else { 5 };
                        reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action }))?;
                        log::warn!("Horizon: server resource-pack download failed id={id:032x} required={}: {reason}", p.required);
                        let _ = send.send(Event::ServerResourcePackFailed { id, required: p.required, reason: reason.clone() });
                        if p.required { return Err(format!("required resource pack {id:032x} failed: {reason}")); }
                    }
                }
            }
            cfg::Clientbound::ResourcePackPop(p) => {
                let _ = send.send(Event::ServerResourcePackPop { id: p.id });
            }
            cfg::Clientbound::CookieRequest(q) => {''', "configuration resource-pack handling")


pack_patch("crates/horizon-protocol/src/packets/play.rs", '''            ClientInformation, CustomPayload, Disconnect, KeepAlive, Ping, ResourcePackPush,
            ResourcePackResponse, UpdateTags,''', '''            ClientInformation, CustomPayload, Disconnect, KeepAlive, Ping, ResourcePackPop,
            ResourcePackPush, ResourcePackResponse, UpdateTags,''', "play packet imports")

pack_patch("crates/horizon-protocol/src/packets/play.rs", '''    ResetScore(ResetScore) = RESET_SCORE,
    ResourcePackPush(ResourcePackPush) = RESOURCE_PACK_PUSH,''', '''    ResetScore(ResetScore) = RESET_SCORE,
    ResourcePackPop(ResourcePackPop) = RESOURCE_PACK_POP,
    ResourcePackPush(ResourcePackPush) = RESOURCE_PACK_PUSH,''', "play ResourcePackPop decoder")

pack_patch("crates/horizon-client/src/packs.rs", '''    })
}
/// Built-in and `resourcepacks/` packs (vanilla itself excluded), sorted like''', '''    })
}
/// Construct a resource pack from a verified server ZIP outside resourcepacks/.
pub fn from_server(id: String, title: Text, zip: PathBuf) -> Option<Pack> {
    pack(id, title, Source::Zip(zip))
}
/// Built-in and `resourcepacks/` packs (vanilla itself excluded), sorted like''', "public server pack constructor")

pack_patch("crates/horizon-client/src/game/packs.rs", '''pub fn startup_root(vanilla: &Path, selected: &[String]) -> std::io::Result<PathBuf> {
    let all = packs::discover(vanilla, &packs_dir());
    let enabled = packs::enabled(&all, selected);
    packs::resource_root(vanilla, &enabled, &cache_dir())
}''', '''pub fn startup_root(vanilla: &Path, selected: &[String]) -> std::io::Result<PathBuf> {
    startup_root_with_server(vanilla, selected, &[])
}
pub fn startup_root_with_server(vanilla: &Path, selected: &[String], server_packs: &[(u128, PathBuf)]) -> std::io::Result<PathBuf> {
    let all = packs::discover(vanilla, &packs_dir());
    let mut enabled = packs::enabled(&all, selected);
    for (id, path) in server_packs {
        let sha1 = path.file_stem().and_then(|v| v.to_str()).unwrap_or("unknown");
        let pack_id = format!("server/{id:032x}/{sha1}");
        if let Some(pack) = packs::from_server(pack_id, Text::literal(format!("Server resource pack {id:032x}")), path.clone()) {
            enabled.push(pack);
        } else {
            log::warn!("Horizon: invalid server resource pack metadata id={id:032x}");
        }
    }
    packs::resource_root(vanilla, &enabled, &cache_dir())
}''', "server pack resource overlays")

pack_patch("crates/horizon-client/src/game/packs.rs", '''    let mut infos: Vec<PackInfo> = packs::discover(vanilla, &packs_dir())
        .iter()
        .map(info)
        .collect();''', '''    let mut infos: Vec<PackInfo> = packs::discover(vanilla, &packs_dir())
        .iter()
        .filter(|pack| !pack.id.starts_with("file/serverpack-"))
        .map(info)
        .collect();''', "hide cached server packs from global selector")

pack_patch("crates/horizon-client/src/game/packs.rs", '''    pub(super) fn reload_packs(&mut self) {
        // Pack reload updates tail-owned mob metadata. Keep menus responsive
        // until those resources exist, then apply the latest selection once.
        if self.rest_loader.is_some() {
            self.pending_pack_reload = true;
            return;
        }''', '''    pub(super) fn reload_packs(&mut self) -> bool {
        // Pack reload updates tail-owned mob metadata. Keep menus responsive
        // until those resources exist, then apply the latest selection once.
        if self.rest_loader.is_some() {
            self.pending_pack_reload = true;
            return false;
        }''', "reload status return type")

pack_patch("crates/horizon-client/src/game/options.rs", '''                    "resourcePacks" | "incompatibleResourcePacks" => self.reload_packs(),''', '''                    "resourcePacks" | "incompatibleResourcePacks" => { self.reload_packs(); },''', "ignore resource reload result in settings match")

pack_patch("crates/horizon-client/src/game/packs.rs", '''        let result = startup_root(&vanilla, &self.settings.resource_packs)
            .map_err(Into::into)
            .and_then(|root| self.reload_resources(&root));
        if let Err(e) = result {''', '''        let server_packs: Vec<(u128, PathBuf)> = self.remote.as_ref()
            .map(|r| r.server_packs.clone())
            .unwrap_or_default();
        let result = startup_root_with_server(&vanilla, &self.settings.resource_packs, &server_packs)
            .map_err(Into::into)
            .and_then(|root| self.reload_resources(&root));
        let succeeded = result.is_ok();
        if let Err(e) = result {''', "apply active server pack list")

pack_patch("crates/horizon-client/src/game/packs.rs", '''            self.ui.options.resource_error = Some(e.to_string());
        }
    }
    fn reload_resources(&mut self, root: &Path) -> horizon_client::Result<()> {''', '''            self.ui.options.resource_error = Some(e.to_string());
        }
        succeeded
    }
    fn reload_resources(&mut self, root: &Path) -> horizon_client::Result<()> {''', "report actual resource reload result")

pack_patch("crates/horizon-client/src/game/remote.rs", '''use std::{collections::HashMap, sync::Arc, time::Instant};''', '''use std::{collections::HashMap, fs, path::PathBuf, sync::Arc, time::Instant};''', "resource-pack path imports")

pack_patch("crates/horizon-client/src/game/remote.rs", '''    skin_textures: HashMap<u128, horizon_client::skins::Textures>,
    listed: std::collections::HashSet<u128>,''', '''    skin_textures: HashMap<u128, horizon_client::skins::Textures>,
    pub(super) server_packs: Vec<(u128, PathBuf)>,
    listed: std::collections::HashSet<u128>,''', "active server-pack state")

pack_patch("crates/horizon-client/src/game/remote.rs", '''            skin_textures: HashMap::new(),
            listed: Default::default(),''', '''            skin_textures: HashMap::new(),
            server_packs: Vec::new(),
            listed: Default::default(),''', "initialize active server-pack state")

pack_patch("crates/horizon-client/src/game/remote.rs", '''                Event::Reconfigured(registries) => self.set_registries(registries),
                Event::DecodeError(e) => {''', '''                Event::Reconfigured(registries) => self.set_registries(registries),
                Event::ServerResourcePackReady { id, path, required } => self.activate_server_pack(id, path, required),
                Event::ServerResourcePackFailed { id, required, reason } => {
                    log::warn!("Horizon: resource pack failed id={id:032x} required={required}: {reason}");
                }
                Event::ServerResourcePackPop { id } => self.pop_server_packs(id),
                Event::DecodeError(e) => {''', "resource-pack events to game")

pack_patch("crates/horizon-client/src/game/remote.rs", '''    fn handle_packet(&mut self, packet: C) -> Result<()> {''', '''    fn activate_server_pack(&mut self, id: u128, path: PathBuf, required: bool) {
        let Some(remote) = self.remote.as_mut() else {
            crate::server_packs::mark_active(&path, false);
            return;
        };
        remote.server_packs.retain(|(old, old_path)| {
            if *old == id {
                crate::server_packs::mark_active(old_path, false);
                false
            } else { true }
        });
        crate::server_packs::mark_active(&path, true);
        remote.server_packs.push((id, path.clone()));
        log::info!("Horizon: applying server resource pack id={id:032x} required={required}");
        self.pending_server_pack_responses.push(id);
        if self.rest_loader.is_some() {
            self.pending_pack_reload = true;
        } else {
            let loaded = self.reload_packs();
            self.finish_pending_server_pack_responses(loaded);
        }
    }

    pub(super) fn finish_pending_server_pack_responses(&mut self, success: bool) {
        let ids = std::mem::take(&mut self.pending_server_pack_responses);
        if ids.is_empty() { return; }
        if !success {
            let mut paths = Vec::new();
            if let Some(remote) = self.remote.as_mut() {
                remote.server_packs.retain(|(id, path)| {
                    if ids.contains(id) { paths.push(path.clone()); false } else { true }
                });
            }
            for path in paths { crate::server_packs::mark_active(&path, false); }
            let _ = self.reload_packs();
        }
        if let Some(remote) = self.remote.as_ref() {
            for id in ids { remote.session.send_resource_pack_response(id, if success { 0 } else { 6 }); }
        }
        if success { log::info!("Horizon: server resource packs applied and reloaded"); }
        else { log::warn!("Horizon: server pack reload failed; invalid pack disabled"); }
    }

    fn pop_server_packs(&mut self, id: Option<u128>) {
        let mut removed = Vec::new();
        if let Some(remote) = self.remote.as_mut() {
            let mut keep = Vec::new();
            for pack in remote.server_packs.drain(..) {
                if id.is_none() || id == Some(pack.0) { removed.push(pack); } else { keep.push(pack); }
            }
            remote.server_packs = keep;
        }
        if removed.is_empty() { log::info!("Horizon: server pack pop id={id:?}, no active match"); return; }
        if self.rest_loader.is_some() { self.pending_pack_reload = true; }
        else if !self.reload_packs() { log::warn!("Horizon: pack reload after pop was deferred or failed"); }
        if let Some(remote) = self.remote.as_ref() {
            for (pack_id, path) in removed {
                remote.session.send_resource_pack_response(pack_id, 7);
                crate::server_packs::mark_active(&path, false);
            }
        }
        log::info!("Horizon: removed server resource pack(s) requested by server");
    }

    fn handle_packet(&mut self, packet: C) -> Result<()> {''', "game-thread resource-pack lifecycle")

pack_patch("crates/horizon-client/src/game/remote.rs", '''r.skin_textures.entry(e.uuid).or_insert(textures);''', '''r.skin_textures.insert(e.uuid, textures);''', "refresh profile textures instead of keeping stale entries")

pack_patch("crates/horizon-client/src/game/remote.rs", '''            C::PlayerInfoRemove(p) => {
                if let Some(r) = self.remote.as_mut() {
                    for u in p.uuids {
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
                        r.skin_textures.remove(&u);
                        r.listed.remove(&u);
                    }
                }
            }''', '''            C::PlayerInfoRemove(p) => {
                if let Some(r) = self.remote.as_mut() {
                    for u in p.uuids {
                        r.tab_players.remove(&u);
                        r.listed.remove(&u);
                        let entity_present = r.level_players.values().any(|profile| *profile == u);
                        if entity_present && u != r.profile {
                            // Vanilla player entities retain their GameProfile after the
                            // server removes the temporary tab-list entry. NPC plugins do
                            // this intentionally; do not erase the world nametag's source.
                            if let Some(name) = r.players.get(&u) {
                                log::info!(
                                    "HorizonDiag: retained world profile name={} while entity remains=true",
                                    name
                                );
                            }
                        } else {
                            r.players.remove(&u);
                        }
                        if !entity_present && u != r.profile {
                            r.skin_textures.remove(&u);
                        }
                    }
                }
            }''', "retain world profile name and texture data while entity exists")

pack_patch("crates/horizon-client/src/game/remote.rs", '''            C::RemoveEntities(p) => {
                for id in p.ids {
                    if let Some(r) = self.remote.as_mut() {
                        r.level_players.remove(&id);
                    }
                    self.remove_entity(id);
                }
            }''', '''            C::RemoveEntities(p) => {
                for id in p.ids {
                    if let Some(r) = self.remote.as_mut() {
                        if let Some(uuid) = r.level_players.remove(&id) {
                            let still_present = r.level_players.values().any(|profile| *profile == uuid);
                            if !still_present && !r.tab_players.contains_key(&uuid) && uuid != r.profile {
                                // A profile removed from the tab list may have been retained
                                // solely so an NPC keeps its world name. Drop it with the
                                // last matching entity, not when PlayerInfoRemove arrives.
                                r.players.remove(&uuid);
                                r.skin_textures.remove(&uuid);
                                log::info!(
                                    "HorizonDiag: removed world profile name after final entity despawn entity_id={id}"
                                );
                            }
                        }
                    }
                    self.remove_entity(id);
                }
            }''', "prune retained NPC names and textures after final entity leaves")

pack_patch("crates/horizon-client/src/game/remote.rs", '''        if let Some(mut r) = self.remote.take() {
            r.session.disconnect();
        }
        // Single player's server thread saves the world before the title shows.''', '''        let mut server_pack_paths = Vec::new();
        if let Some(mut r) = self.remote.take() {
            server_pack_paths.extend(r.server_packs.iter().map(|(_, path)| path.clone()));
            for path in &server_pack_paths { crate::server_packs::mark_active(path, false); }
            r.server_packs.clear();
            r.session.disconnect();
        }
        if !server_pack_paths.is_empty() {
            if self.rest_loader.is_some() { self.pending_pack_reload = true; }
            else { let _ = self.reload_packs(); }
            // Keep verified, content-addressed ZIPs for the next server join.
            let _ = server_pack_paths;
            log::info!("Horizon: cleared server resource packs after leaving multiplayer");
        }
        // Single player's server thread saves the world before the title shows.''', "clear active server packs on disconnect")

pack_patch("crates/horizon-client/src/game.rs", '''    pending_pack_reload: bool,
    world_renderer_ready: bool,''', '''    pending_pack_reload: bool,
    pending_server_pack_responses: Vec<u128>,
    world_renderer_ready: bool,''', "deferred pack status field")

pack_patch("crates/horizon-client/src/game.rs", '''            pending_pack_reload: false,
            world_renderer_ready: false,''', '''            pending_pack_reload: false,
            pending_server_pack_responses: Vec::new(),
            world_renderer_ready: false,''', "initialize deferred pack statuses")

pack_patch("crates/horizon-client/src/game.rs", '''        if std::mem::take(&mut self.pending_pack_reload) {
            self.reload_packs();
        }''', '''        if std::mem::take(&mut self.pending_pack_reload) {
            let loaded = self.reload_packs();
            self.finish_pending_server_pack_responses(loaded);
        }''', "report deferred resource reload outcome")

# Resource-pack responses must use the same protocol phase in which each offer arrived.
# The configuration reader is shared with a download worker, rather than blocked on HTTP.
pack_patch("crates/horizon-client/src/net.rs", '''/// What the network thread reports to the game.
pub enum Event {''', '''#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ResourcePackPhase { Configuration, Play }

/// What the network thread reports to the game.
pub enum Event {''', "resource-pack protocol phase")
pack_patch("crates/horizon-client/src/net.rs", '''    ServerResourcePackReady { id: u128, path: PathBuf, required: bool },
    ServerResourcePackFailed { id: u128, required: bool, reason: String },
    ServerResourcePackPop { id: Option<u128> },''', '''    ServerResourcePackReady { id: u128, path: PathBuf, required: bool, phase: ResourcePackPhase },
    ServerResourcePackFailed { id: u128, required: bool, reason: String, phase: ResourcePackPhase },
    ServerResourcePackPop { id: Option<u128>, phase: ResourcePackPhase },''', "attach protocol phase to resource-pack events")
pack_patch("crates/horizon-client/src/net.rs", '''    pub fn send_resource_pack_response(&self, id: u128, action: i32) {
        send_pack_status(&self.writer, &self.protocol_state, id, action);
    }''', '''    pub fn send_resource_pack_response(&self, id: u128, action: i32) {
        send_pack_status(&self.writer, &self.protocol_state, id, action);
    }
    pub fn send_resource_pack_response_in_phase(&self, id: u128, action: i32, phase: ResourcePackPhase) {
        send_pack_status_in_phase(&self.writer, id, action, phase);
    }''', "phase-specific resource-pack response API")
pack_patch("crates/horizon-client/src/net.rs", '''fn send_pack_status(writer: &Mutex<Option<ConnWriter>>, state: &AtomicU8, id: u128, action: i32) {
    let bytes = if state.load(Ordering::Acquire) == PACK_STATE_CONFIG {
        cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action }).encode()
    } else {
        play::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action }).encode()
    };
    if let Some(w) = writer.lock().unwrap().as_mut() && w.send(&bytes).is_err() {
        log::warn!("Horizon: failed sending resource-pack status id={id:032x} action={action}");
        w.shutdown();
    }
}''', '''fn send_pack_status(writer: &Mutex<Option<ConnWriter>>, state: &AtomicU8, id: u128, action: i32) {
    let phase = if state.load(Ordering::Acquire) == PACK_STATE_CONFIG {
        ResourcePackPhase::Configuration
    } else {
        ResourcePackPhase::Play
    };
    send_pack_status_in_phase(writer, id, action, phase);
}

fn send_pack_status_in_phase(writer: &Mutex<Option<ConnWriter>>, id: u128, action: i32, phase: ResourcePackPhase) {
    let bytes = match phase {
        ResourcePackPhase::Configuration => cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action }).encode(),
        ResourcePackPhase::Play => play::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action }).encode(),
    };
    if let Some(w) = writer.lock().unwrap().as_mut() && w.send(&bytes).is_err() {
        log::warn!("Horizon: failed sending resource-pack status id={id:032x} action={action} phase={phase:?}");
        w.shutdown();
    }
}''', "phase-specific resource-pack packet encoder")
pack_patch("crates/horizon-client/src/net.rs", '''fn configure(
    reader: &mut ConnReader,
    writer: &Mutex<Option<ConnWriter>>,
    lang: &Language,
    send: &Sender<Event>,
) -> Result<Dynamic, String> {''', '''fn configure(
    reader: &mut ConnReader,
    writer: Arc<Mutex<Option<ConnWriter>>>,
    lang: &Language,
    send: &Sender<Event>,
) -> Result<Dynamic, String> {''', "share writer with configuration download workers")
pack_patch("crates/horizon-client/src/net.rs", '''let registries = configure(&mut reader, shared_writer, lang, send)?;''', '''let registries = configure(&mut reader, Arc::clone(shared_writer), lang, send)?;''', "clone connection writer for initial configuration")
pack_patch("crates/horizon-client/src/net.rs", '''let registries = configure(reader, &shared_writer, lang, send)?;''', '''let registries = configure(reader, Arc::clone(&shared_writer), lang, send)?;''', "clone connection writer for reconfiguration")
pack_patch("crates/horizon-client/src/net.rs", '''send_pack_status(&writer, &state, id, 4);
                            let _ = events.send(Event::ServerResourcePackReady { id, path, required });''', '''send_pack_status_in_phase(&writer, id, 4, ResourcePackPhase::Play);
                            let _ = events.send(Event::ServerResourcePackReady { id, path, required, phase: ResourcePackPhase::Play });''', "route play download completion through play packet type")
pack_patch("crates/horizon-client/src/net.rs", '''send_pack_status(&writer, &state, id, if supported_pack_url(&url) { 2 } else { 5 });
                            log::warn!("Horizon: server resource-pack download failed id={id:032x} required={required}: {reason}");
                            let _ = events.send(Event::ServerResourcePackFailed { id, required, reason });''', '''send_pack_status_in_phase(&writer, id, if supported_pack_url(&url) { 2 } else { 5 }, ResourcePackPhase::Play);
                            log::warn!("Horizon: server resource-pack download failed id={id:032x} required={required}: {reason}");
                            let _ = events.send(Event::ServerResourcePackFailed { id, required, reason, phase: ResourcePackPhase::Play });''', "route play download failure through play packet type")
pack_patch("crates/horizon-client/src/net.rs", '''play::Clientbound::ResourcePackPop(p) => {
                let _ = send.send(Event::ServerResourcePackPop { id: p.id });
            }''', '''play::Clientbound::ResourcePackPop(p) => {
                let _ = send.send(Event::ServerResourcePackPop { id: p.id, phase: ResourcePackPhase::Play });
            }''', "record phase for play resource-pack removal")
pack_patch("crates/horizon-client/src/net.rs", '''cfg::Clientbound::ResourcePackPop(p) => {
                let _ = send.send(Event::ServerResourcePackPop { id: p.id });
            }''', '''cfg::Clientbound::ResourcePackPop(p) => {
                let _ = send.send(Event::ServerResourcePackPop { id: p.id, phase: ResourcePackPhase::Configuration });
            }''', "record phase for configuration resource-pack removal")
pack_patch("crates/horizon-client/src/net.rs", '''let _ = send.send(Event::ServerResourcePackFailed { id, required, reason: e.to_string() });''', '''let _ = send.send(Event::ServerResourcePackFailed { id, required, reason: e.to_string(), phase: ResourcePackPhase::Play });''', "record phase for play worker-start failure")

pack_patch("crates/horizon-client/src/net.rs", '''            cfg::Clientbound::ResourcePackPush(p) => {
                let id = p.id;
                log::info!("Horizon: server resource-pack offer id={id:032x} state=configuration required={} hash_present={} origin={}", p.required, !p.hash.is_empty(), safe_resource_pack_origin(&p.url));
                reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action: 3 }))?;
                match download_server_pack(id, &p.url, &p.hash, send) {
                    Ok(path) => {
                        reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action: 4 }))?;
                        let _ = send.send(Event::ServerResourcePackReady { id, path, required: p.required });
                    }
                    Err(reason) => {
                        let action = if supported_pack_url(&p.url) { 2 } else { 5 };
                        reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action }))?;
                        log::warn!("Horizon: server resource-pack download failed id={id:032x} required={}: {reason}", p.required);
                        let _ = send.send(Event::ServerResourcePackFailed { id, required: p.required, reason: reason.clone() });
                        if p.required { return Err(format!("required resource pack {id:032x} failed: {reason}")); }
                    }
                }
            }''', '''            cfg::Clientbound::ResourcePackPush(p) => {
                let id = p.id;
                let url = p.url;
                let hash = p.hash;
                let required = p.required;
                let supported_url = supported_pack_url(&url);
                log::info!("Horizon: server resource-pack offer id={id:032x} state=configuration required={required} hash_present={} origin={}", !hash.is_empty(), safe_resource_pack_origin(&url));
                reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action: 3 }))?;
                let worker_writer = Arc::clone(&writer);
                let events = send.clone();
                let started = std::thread::Builder::new().name("horizon-pack-download-config".into()).spawn(move || {
                    match download_server_pack(id, &url, &hash, &events) {
                        Ok(path) => {
                            send_pack_status_in_phase(&worker_writer, id, 4, ResourcePackPhase::Configuration);
                            let _ = events.send(Event::ServerResourcePackReady { id, path, required, phase: ResourcePackPhase::Configuration });
                        }
                        Err(reason) => {
                            send_pack_status_in_phase(&worker_writer, id, if supported_url { 2 } else { 5 }, ResourcePackPhase::Configuration);
                            log::warn!("Horizon: server resource-pack download failed id={id:032x} required={required}: {reason}");
                            let _ = events.send(Event::ServerResourcePackFailed { id, required, reason: reason.clone(), phase: ResourcePackPhase::Configuration });
                            if required {
                                if let Some(w) = worker_writer.lock().unwrap().as_mut() { w.shutdown(); }
                            }
                        }
                    }
                });
                if let Err(error) = started {
                    let action = if supported_url { 2 } else { 5 };
                    reply(cfg::Serverbound::ResourcePack(cfg::ResourcePackResponse { id, action }))?;
                    log::warn!("Horizon: cannot start configuration resource-pack downloader id={id:032x}: {error}");
                    let _ = send.send(Event::ServerResourcePackFailed { id, required, reason: error.to_string(), phase: ResourcePackPhase::Configuration });
                    if required { return Err(format!("required resource pack {id:032x} could not start: {error}")); }
                }
            }''', "download configuration resource packs in background")

pack_patch("crates/horizon-client/src/game/remote.rs", '''                Event::ServerResourcePackReady { id, path, required } => self.activate_server_pack(id, path, required),
                Event::ServerResourcePackFailed { id, required, reason } => {
                    log::warn!("Horizon: resource pack failed id={id:032x} required={required}: {reason}");
                }
                Event::ServerResourcePackPop { id } => self.pop_server_packs(id),''', '''                Event::ServerResourcePackReady { id, path, required, phase } => self.activate_server_pack(id, path, required, phase),
                Event::ServerResourcePackFailed { id, required, reason, phase } => {
                    log::warn!("Horizon: resource pack failed id={id:032x} required={required} phase={phase:?}: {reason}");
                }
                Event::ServerResourcePackPop { id, phase } => self.pop_server_packs(id, phase),''', "consume phase-aware resource-pack events")
pack_patch("crates/horizon-client/src/game/remote.rs", '''    fn activate_server_pack(&mut self, id: u128, path: PathBuf, required: bool) {''', '''    fn activate_server_pack(&mut self, id: u128, path: PathBuf, required: bool, phase: crate::net::ResourcePackPhase) {''', "track resource-pack response phase during reload")
pack_patch("crates/horizon-client/src/game/remote.rs", '''        self.pending_server_pack_responses.push(id);''', '''        self.pending_server_pack_responses.push((id, phase));''', "store per-pack protocol phase")
pack_patch("crates/horizon-client/src/game/remote.rs", '''    fn pop_server_packs(&mut self, id: Option<u128>) {''', '''    fn pop_server_packs(&mut self, id: Option<u128>, phase: crate::net::ResourcePackPhase) {''', "track phase for resource-pack removal")
pack_patch("crates/horizon-client/src/game/remote.rs", '''                remote.session.send_resource_pack_response(pack_id, 7);''', '''                remote.session.send_resource_pack_response_in_phase(pack_id, 7, phase);''', "send discarded status in the request phase")
pack_patch("crates/horizon-client/src/game/remote.rs", '''                    if ids.contains(id) { paths.push(path.clone()); false } else { true }''', '''                    if ids.iter().any(|(pending_id, _)| pending_id == id) { paths.push(path.clone()); false } else { true }''', "remove failed packs by pending response UUID")
pack_patch("crates/horizon-client/src/game/remote.rs", '''            for id in ids { remote.session.send_resource_pack_response(id, if success { 0 } else { 6 }); }''', '''            for (id, phase) in ids { remote.session.send_resource_pack_response_in_phase(id, if success { 0 } else { 6 }, phase); }''', "send final pack status in the original protocol phase")
pack_patch("crates/horizon-client/src/game.rs", '''    pending_server_pack_responses: Vec<u128>,''', '''    pending_server_pack_responses: Vec<(u128, crate::net::ResourcePackPhase)>,''', "preserve phase for deferred pack reload")

pack_patch("crates/horizon-client/src/item_data.rs", '''fn read_text(t: &Tag) -> Option<String> {
    match t {
        Tag::String(s) => Some(s.to_string_lossy()),
        Tag::Compound(_) => t.get("text").and_then(string),
        _ => None,
    }
}''', '''fn read_text(t: &Tag) -> Option<String> {
    match t {
        Tag::String(s) => Some(s.to_string_lossy()),
        Tag::Compound(_) => t.get("text").and_then(string),
        _ => None,
    }
}

/// Resolve a Java text component with the active language loaded from the resource stack.
/// This is used for server-provided item_name/custom_name data components.
pub fn text_component_plain(tag: &Tag) -> String {
    fn collect(tag: &Tag, language: &mut std::collections::HashMap<String, String>) {
        match tag {
            Tag::Compound(fields) => {
                for field in ["translate", "keybind"] {
                    if let Some(Tag::String(raw)) = tag.get(field) {
                        let key = raw.to_string_lossy();
                        if crate::lang::has(&key) {
                            language.entry(key.clone()).or_insert_with(|| crate::lang::get(&key));
                        }
                    }
                }
                for value in fields.values() {
                    collect(value, language);
                }
            }
            Tag::List(values) => {
                for value in values {
                    collect(value, language);
                }
            }
            _ => {}
        }
    }

    let mut language = std::collections::HashMap::new();
    collect(tag, &mut language);
    horizon_protocol::text::plain(tag, &language)
}''', "resolve server item text components through active resource-pack language")

pack_patch("crates/horizon-client/src/game/remote.rs", '''    /// The names in the server's player list, sorted.
    pub(super) fn remote_online_names(&self) -> Option<Vec<String>> {''', '''    /// Whether a remote world entity was spawned as a player profile.
    pub(super) fn remote_has_player_entity(&self, id: u32) -> bool {
        self.remote.as_ref().is_some_and(|r| r.level_players.contains_key(&(id as i32)))
    }
    /// The names in the server's player list, sorted.
    pub(super) fn remote_online_names(&self) -> Option<Vec<String>> {''', "expose read-only player entity lookup")

pack_patch("crates/horizon-client/src/game/names.rs", '''        let named = self
            .mobs
            .iter()
            .filter_map(|m| Some((m, m.custom_name.clone()?, false)));''', '''        let named = self
            .mobs
            .iter()
            // Player NPCs may send both a GameProfile name and custom-name
            // metadata. Vanilla treats them as player entities and draws one tag.
            // Do not also render them through the mob custom-name path.
            .filter(|m| !self.remote_has_player_entity(m.id))
            .filter_map(|m| Some((m, m.custom_name.clone()?, false)));''', "avoid duplicate mob/player name tags for player-like NPCs")

pack_patch("crates/horizon-client/src/game/names.rs", '''            let shown = match team {
                Some(t) => t.formatted_name(Text::literal(&name)),
                None => Text::literal(&name),
            };''', '''            let shown = match team {
                // Fake NPC profiles may contain legacy § colour/decorations.
                Some(t) => t.formatted_name(Text::legacy(&name)),
                None => Text::legacy(&name),
            };''', "render legacy formatting in player-like NPC names")

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
            });
        }
        tags
    }''', '''            tags.push(NameTag {
                at: p,
                lines,
                light: l,
                discrete,
                background_color: 0x3F000000,
                text_opacity: 255,
                see_through: false,
                display_transform: None,
            });
        }
        // TextDisplay is a first-class server entity, not a mob nametag.
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
        tags
    }''', "render server text display entities")

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

pack_patch("crates/horizon-client/src/game/remote.rs", '''        if let Some(Component::Int(id)) = s.component(component::MAP_ID) {
            out.data_mut().map_id = Some(*id);
        }
        Some(out)
    }''', '''        if let Some(Component::Int(id)) = s.component(component::MAP_ID) {
            out.data_mut().map_id = Some(*id);
        }

        // New Java item data components control custom item names and item models.
        if let Some(Component::Text(name)) = s.component(component::CUSTOM_NAME) {
            out.data_mut().custom_name = Some(crate::item_data::text_component_plain(name));
        }
        if let Some(Component::Text(name)) = s.component(component::ITEM_NAME) {
            out.data_mut().set_other("minecraft:item_name", Some(name.clone()));
        }
        if let Some(Component::Ident(model)) = s.component(component::ITEM_MODEL) {
            out.data_mut().set_other(
                "minecraft:item_model",
                Some(horizon_world::nbt::Tag::String(model.as_str().into())),
            );
        }
        if let Some(Component::Lore(lines)) = s.component(component::LORE) {
            out.data_mut().set_other("minecraft:lore", Some(horizon_world::nbt::Tag::List(lines.clone())));
        }
        if let Some(Component::Nbt(data)) = s.component(component::CUSTOM_DATA) {
            out.data_mut().set_other("minecraft:custom_data", Some(data.clone()));
        }
        if let Some(Component::CustomModelData { floats, flags, strings, colors }) =
            s.component(component::CUSTOM_MODEL_DATA)
        {
            use horizon_world::nbt::Tag;
            let data = Tag::compound([
                ("floats", Tag::List(floats.iter().map(|value| Tag::Float(value.to_bits())).collect())),
                ("flags", Tag::List(flags.iter().map(|value| Tag::Byte(if *value { 1 } else { 0 })).collect())),
                ("strings", Tag::List(strings.iter().map(|value| Tag::String(value.as_str().into())).collect())),
                ("colors", Tag::List(colors.iter().map(|value| Tag::Int(*value)).collect())),
            ]);
            out.data_mut().set_other("minecraft:custom_model_data", Some(data));
        }

        // An absent component in the network patch can suppress its default component
        // in a modern item definition; StackView interprets the leading ! convention.
        for kind in &s.removed {
            let key = match *kind {
                component::CUSTOM_NAME => Some("minecraft:custom_name"),
                component::ITEM_NAME => Some("minecraft:item_name"),
                component::ITEM_MODEL => Some("minecraft:item_model"),
                component::LORE => Some("minecraft:lore"),
                component::CUSTOM_DATA => Some("minecraft:custom_data"),
                component::CUSTOM_MODEL_DATA => Some("minecraft:custom_model_data"),
                _ => None,
            };
            if let Some(key) = key {
                out.data_mut().set_other(&format!("!{key}"), Some(horizon_world::nbt::Tag::Byte(0)));
            }
        }

        Some(out)
    }''', "preserve server item names and modern item-model components")

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

pack_patch("crates/horizon-client/src/game/remote.rs", '''        for (location, rgba) in self.skin_downloads.take_ready() {
            self.store_skin(location, rgba);
        }
        for (entity_id, location) in skins {''', '''        // Player-head item displays have their own profile components and are not
        // necessarily represented in the world-player profile table. Queue these
        // skin URLs through the same bounded downloader and atlas slots.
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
                    let downloader = &mut self.skin_downloads;
                    let _ = horizon_client::skins::lookup(&textures, false, |kind, url| {
                        downloader.state(kind, url)
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
                let downloader = &mut self.skin_downloads;
                let _ = horizon_client::skins::lookup(&textures, false, |kind, url| {
                    downloader.state(kind, url)
                });
            }
        }

        for (location, rgba) in self.skin_downloads.take_ready() {
            self.store_skin(location, rgba);
        }
        for (entity_id, location) in skins {''', "download textures for custom player heads displayed in the world")

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
                            "HorizonDiag: remote_item_frame_updated entity_id={} item_present={} item_id={} rotation={}",
                            frame.id,
                            frame.item.is_some(),
                            frame.item.as_ref().map_or("-", |stack| stack.item.as_ref()),
                            frame.rotation
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

pack_patch("crates/horizon-client/src/game/remote.rs", '''                let item_frame_rotation_update = p.values.iter().find_map(|(index, _, value)| {
                    if *index == 9 {
                        if let Value::Int(rotation) = value { Some(*rotation) } else { None }
                    } else { None }
                });''', '''                let item_frame_rotation_update = p.values.iter().find_map(|(index, _, value)| {
                    if *index == 9 {
                        match value {
                            Value::Int(rotation) => Some(*rotation),
                            Value::Byte(rotation) => Some(i32::from(*rotation)),
                            _ => None,
                        }
                    } else { None }
                });''', "accept byte or int item-frame rotation metadata")

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

pack_patch("crates/horizon-client/src/game/names.rs", '''                see_through: false,
                display_transform: None,
            });''', '''                see_through: false,
                display_transform: None,
                source_entity_id: None,
            });''', "mark normal name tags as non-display text")

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



# Minecraft Java 26.3 component ID 74 is banner_patterns. Its exact wire codec
# is a VarInt list of Holder<BannerPattern> and DyeColor enum values. Preserve
# the original bytes after safely consuming the schema so metadata packets do
# not get discarded just because the renderer doesn't yet interpret the patterns.
pack_patch("crates/horizon-protocol/src/item.rs", '''    pub const PROFILE: i32 = 72;
}''', '''    pub const PROFILE: i32 = 72;
    pub const BANNER_PATTERNS: i32 = 74;
}''', "register banner_patterns component ID")

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
    fn unknown_components_are_errors() {''', '''    #[test]
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
    }

    #[test]
    fn unknown_components_are_errors() {''', "test banner-pattern stack round-trip")

# MapItemData was not in the clientbound packet enum. This decoder follows the
# Java 26.3 layout: map id, scale, locked, optional decorations, optional color patch.
pack_patch("crates/horizon-protocol/src/packets/play.rs", '''packet_enum!(Clientbound, crate::ids::play::clientbound {''', '''#[derive(Clone, Debug, PartialEq)]
pub struct MapDecorationData {
    pub kind: i32,
    pub x: i8,
    pub y: i8,
    pub rotation: i8,
    pub name: Option<Tag>,
}
#[derive(Clone, Debug, PartialEq)]
pub struct MapColorPatch {
    pub width: u8,
    pub height: u8,
    pub start_x: u8,
    pub start_y: u8,
    pub colors: Vec<u8>,
}
#[derive(Clone, Debug, PartialEq)]
pub struct MapItemData {
    pub map_id: i32,
    pub scale: i8,
    pub locked: bool,
    pub decorations: Option<Vec<MapDecorationData>>,
    pub patch: Option<MapColorPatch>,
}
impl Codec<MapItemData> for MapItemData {
    fn read(r: &mut Reader) -> Result<Self> {
        let map_id = r.varint()?;
        let scale = r.i8()?;
        let locked = r.bool()?;
        let decorations = if r.bool()? {
            let count = r.count(4096)?;
            let mut values = Vec::with_capacity(count);
            for _ in 0..count {
                let kind = r.varint()?;
                let x = r.i8()?;
                let y = r.i8()?;
                let rotation = r.i8()?;
                let name = if r.bool()? { Some(r.nbt()?) } else { None };
                values.push(MapDecorationData { kind, x, y, rotation, name });
            }
            Some(values)
        } else { None };
        let width = r.u8()?;
        let patch = if width == 0 {
            None
        } else {
            if width > 128 {
                return Err(Error::new(format!("map color patch width {width} exceeds 128")));
            }
            let height = r.u8()?;
            let start_x = r.u8()?;
            let start_y = r.u8()?;
            if height == 0 || height > 128
                || u16::from(start_x) + u16::from(width) > 128
                || u16::from(start_y) + u16::from(height) > 128
            {
                return Err(Error::new("map color patch rectangle is outside the 128x128 map"));
            }
            let colors = r.byte_array(128 * 128)?;
            let expected = usize::from(width) * usize::from(height);
            if colors.len() != expected {
                return Err(Error::new(format!(
                    "map color patch has {} colors, expected {expected}", colors.len()
                )));
            }
            Some(MapColorPatch { width, height, start_x, start_y, colors })
        };
        Ok(Self { map_id, scale, locked, decorations, patch })
    }
    fn write(v: &Self, w: &mut Writer) {
        w.varint(v.map_id);
        w.i8(v.scale);
        w.bool(v.locked);
        w.bool(v.decorations.is_some());
        if let Some(decorations) = &v.decorations {
            w.varint(decorations.len() as i32);
            for d in decorations {
                w.varint(d.kind);
                w.i8(d.x);
                w.i8(d.y);
                w.i8(d.rotation);
                w.bool(d.name.is_some());
                if let Some(name) = &d.name { w.nbt(name); }
            }
        }
        if let Some(patch) = &v.patch {
            w.u8(patch.width);
            w.u8(patch.height);
            w.u8(patch.start_x);
            w.u8(patch.start_y);
            w.byte_array(&patch.colors);
        } else {
            w.u8(0);
        }
    }
}
#[cfg(test)]
mod map_item_data_tests {
    use super::*;
    #[test]
    fn map_item_data_partial_color_patch_round_trips() {
        let packet = MapItemData {
            map_id: 517, scale: 2, locked: true,
            decorations: Some(vec![MapDecorationData {
                kind: 3, x: -15, y: 21, rotation: 7, name: None,
            }]),
            patch: Some(MapColorPatch {
                width: 2, height: 2, start_x: 10, start_y: 20,
                colors: vec![1, 5, 9, 13],
            }),
        };
        let mut writer = Writer::new();
        <MapItemData as Codec<MapItemData>>::write(&packet, &mut writer);
        let mut reader = Reader::new(&writer.data);
        let decoded = <MapItemData as Codec<MapItemData>>::read(&mut reader).unwrap();
        assert_eq!(decoded, packet);
        assert_eq!(reader.remaining(), 0);
    }
}

packet_enum!(Clientbound, crate::ids::play::clientbound {''', "decode and test Minecraft map_item_data packets")

pack_patch("crates/horizon-protocol/src/packets/play.rs", '''    MerchantOffers(MerchantOffers) = MERCHANT_OFFERS,''', '''    MapItemData(MapItemData) = MAP_ITEM_DATA,
    MerchantOffers(MerchantOffers) = MERCHANT_OFFERS,''', "register map_item_data in the clientbound packet enum")



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

pack_patch("crates/horizon-client/src/hud.rs", '''pub const MAP_SLOTS: usize = 16;''', '''pub const MAP_SLOTS: usize = 640;''', "reserve map textures for large server map walls")

pack_patch("crates/horizon-client/src/game/maps.rs", '''    pub(super) fn map_texture(&mut self, id: i32) -> Option<Rect> {''', '''    /// Apply the server's MapItemData packet. Keep remote map pixels separate
    /// from files in the local saves folder and retain many IDs for large map walls.
    pub(super) fn apply_remote_map_data(
        &mut self,
        id: i32,
        scale: i8,
        locked: bool,
        decorations: Option<Vec<(String, i8, i8, i8)>>,
        patch: Option<horizon_protocol::packets::play::MapColorPatch>,
    ) {
        let scale = scale.clamp(0, 4) as u8;
        let dimension = self.dimension_id().to_owned();
        if !self.maps.data.contains_key(&id) {
            let mut data = MapData::fresh(0.0, 0.0, scale, &dimension);
            data.dirty = false;
            data.texture_dirty = true;
            self.maps.data.insert(id, data);
        }
        self.maps.missing.remove(&id);
        let mut applied_pixels = 0usize;
        let mut patch_size = (0u8, 0u8);
        let mut decorations_updated = false;
        let mut decoration_count = -1i32;
        if let Some(map) = self.maps.data.get_mut(&id) {
            map.scale = scale;
            map.locked = locked;
            if let Some(decorations) = decorations {
                decorations_updated = true;
                map.decorations = decorations.into_iter().filter_map(|(name, x, y, rotation)| {
                    decoration_sprite(&name).map(|sprite| Decoration {
                        sprite, x, y, rot: (rotation as u8) & 15,
                    })
                }).collect();
                decoration_count = map.decorations.len() as i32;
            }
            if let Some(patch) = patch {
                patch_size = (patch.width, patch.height);
                for dy in 0..usize::from(patch.height) {
                    for dx in 0..usize::from(patch.width) {
                        let x = usize::from(patch.start_x) + dx;
                        let y = usize::from(patch.start_y) + dy;
                        let source = dy * usize::from(patch.width) + dx;
                        let target = y * 128 + x;
                        if target < map.colors.len() && source < patch.colors.len() {
                            map.colors[target] = patch.colors[source];
                            applied_pixels += 1;
                        }
                    }
                }
                if applied_pixels > 0 { map.texture_dirty = true; }
            }
            map.dirty = false;
        }
        log::info!(
            "HorizonDiag: map_item_data_applied map_id={} scale={} locked={} patch={}x{} pixels_applied={} decorations_updated={} decoration_count={} cached_server_maps={} atlas_slots={}",
            id, scale, locked, patch_size.0, patch_size.1, applied_pixels,
            decorations_updated, decoration_count, self.maps.data.len(), MAP_SLOTS
        );
    }

    pub(super) fn clear_remote_map_cache(&mut self) {
        self.maps.data.clear();
        self.maps.missing.clear();
        self.maps.slots.clear();
        self.map_patches.clear();
    }

    pub(super) fn map_texture(&mut self, id: i32) -> Option<Rect> {''', "apply server map pixels and isolate remote map cache")

pack_patch("crates/horizon-client/src/game/remote.rs", '''        self.world = World::new(self.res.facts().air, 0);
        self.save = Arc::new(SaveDir::new(
            std::env::temp_dir().join("horizon-remote-unused"),
        ));''', '''        self.world = World::new(self.res.facts().air, 0);
        self.clear_remote_map_cache();
        self.save = Arc::new(SaveDir::new(
            std::env::temp_dir().join("horizon-remote-unused"),
        ));''', "clear stale local map data before joining a remote world")

pack_patch("crates/horizon-client/src/game/remote.rs", '''            C::BlockEntityData(p) => self.remote_block_entity_data(p.pos, p.kind, &p.tag),''', '''            C::MapItemData(p) => {
                let decorations = p.decorations.map(|values| {
                    values.into_iter().map(|decoration| {
                        let name = self.remote.as_ref()
                            .and_then(|remote| remote.builtin.get("minecraft:map_decoration_type")?.name(decoration.kind))
                            .map(str::to_owned)
                            .unwrap_or_else(|| format!("unknown:{}", decoration.kind));
                        (name, decoration.x, decoration.y, decoration.rotation)
                    }).collect()
                });
                self.apply_remote_map_data(p.map_id, p.scale, p.locked, decorations, p.patch);
            }
            C::BlockEntityData(p) => self.remote_block_entity_data(p.pos, p.kind, &p.tag),''', "consume remote map textures before rendering item frames")


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
        let Some(entity) = self.world.block_entities.get(&key) else {
            if is_sign {
                log::warn!("HorizonDiag: sign_text_not_applied pos={:?} type={} reason=world_has_no_sign_block_entity", pos, name);
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
                if let Some(Tag::String(value)) = score.get("value") {
                    out.push_str(&value.to_string_lossy());
                } else if let Some(Tag::String(name)) = score.get("name") {
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
