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
