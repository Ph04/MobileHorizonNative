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
net = root / "crates/horizon-client/src/net.rs"
client_cargo = root / "crates/horizon-client/Cargo.toml"
clipboard = root / "crates/horizon-client/src/clipboard.rs"
pad_menu = root / "crates/horizon-client/src/game/pad_menu.rs"
startup_menu = root / "crates/horizon-client/src/game/startup_menu.rs"
workspace_cargo = root / "Cargo.toml"

for path in (game, lib, audio, skins, remote, net, client_cargo, clipboard, pad_menu, startup_menu, workspace_cargo):
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
            ))
            .collect();
        let removed_render_components: Vec<i32> = s.removed.iter().copied()
            .filter(|kind| matches!(
                *kind,
                horizon_protocol::item::component::ITEM_MODEL
                    | horizon_protocol::item::component::CUSTOM_MODEL_DATA
            ))
            .collect();
        if !ignored_components.is_empty() || !removed_render_components.is_empty() {
            static COMPONENT_DIAGNOSTICS: std::sync::atomic::AtomicUsize =
                std::sync::atomic::AtomicUsize::new(0);
            if COMPONENT_DIAGNOSTICS.fetch_add(1, std::sync::atomic::Ordering::Relaxed) < 128 {
                let source_menu = self.remote.as_ref()
                    .and_then(|r| r.menu.as_ref())
                    .map(|(_, menu)| menu.as_str())
                    .unwrap_or("inventory");
                log::warn!(
                    "HorizonDiag: item_stack_components_not_preserved source_menu={} item={} added_component_ids={:?} removed_render_component_ids={:?}",
                    source_menu, self.item_name(s.item), ignored_components, removed_render_components
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
                reply(play::Serverbound::ConfigurationAcknowledged(Empty));
                protocol_state.store(PACK_STATE_CONFIG, Ordering::Release);
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
                        r.players.remove(&u);
                        r.tab_players.remove(&u);
                        r.listed.remove(&u);
                        let entity_present = r.level_players.values().any(|profile| *profile == u);
                        if !entity_present && u != r.profile { r.skin_textures.remove(&u); }
                        else { log::info!("Horizon: retained skin profile uuid={u:032x} while entity remains={entity_present}"); }
                    }
                }
            }''', "retain texture data while entity exists")

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
                            if !still_present && !r.tab_players.contains_key(&uuid) && !r.players.contains_key(&uuid) && uuid != r.profile {
                                r.skin_textures.remove(&uuid);
                            }
                        }
                    }
                    self.remove_entity(id);
                }
            }''', "prune textures only after the final associated entity leaves")

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

print("Applied server resource-pack download, verification, application, pop handling and skin lifetime fixes.")
