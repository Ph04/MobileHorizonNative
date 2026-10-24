# Ordered patch step. See README.md in this directory for scope and order.
# ---------------------------------------------------------------------------
# MobileHorizon: server resource packs and multiplayer profile texture lifetime
# Apply this overlay to the pinned upstream source before the Android build.
from pathlib import Path
import sys

pack_root = root
# repo_root is supplied by the patch orchestrator.
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

pack_patch("crates/horizon-client/src/net.rs",
    '''receive_play(&mut reader, &thread_writer, &Language::new(), &send)''',
    '''receive_play(
                &mut reader,
                Arc::clone(&thread_writer),
                Arc::new(AtomicU8::new(PACK_STATE_PLAY)),
                &Language::new(),
                &send,
            )''',
    "update connection test call for protocol-state parameter")

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
        if self.rest_loader.is_some() || self.world_loader.is_some() {
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

pack_patch("crates/horizon-client/src/game/remote.rs", '''use std::{
    collections::{HashMap, VecDeque},
    sync::Arc,
    time::{Duration, Instant},
};''', '''use std::{
    collections::{HashMap, VecDeque},
    fs,
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant},
};''', "resource-pack path imports")

pack_patch("crates/horizon-client/src/game/remote.rs", '''    skin_textures: HashMap<u128, horizon_client::skins::Textures>,
    /// The skin texture each profile's players show, once its download is ready.
    skins: HashMap<u128, String>,
    listed: std::collections::HashSet<u128>,''', '''    skin_textures: HashMap<u128, horizon_client::skins::Textures>,
    /// The skin texture each profile's players show, once its download is ready.
    skins: HashMap<u128, String>,
    pub(super) server_packs: Vec<(u128, PathBuf)>,
    listed: std::collections::HashSet<u128>,''', "active server-pack state")

pack_patch("crates/horizon-client/src/game/remote.rs", '''            skin_textures: HashMap::new(),
            skins: HashMap::new(),
            listed: Default::default(),''', '''            skin_textures: HashMap::new(),
            skins: HashMap::new(),
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
        // A component's root `text` may be empty with the visible words in
        // `extra` (every legacy display name ViaVersion produces looks like
        // this), so flatten the whole component instead of reading one key.
        Tag::Compound(_) => Some(crate::entity_nbt::name_text(t).plain()),
        _ => None,
    }
}''', '''fn read_text(t: &Tag) -> Option<String> {
    match t {
        Tag::String(s) => Some(s.to_string_lossy()),
        // A component's root text may be empty with the visible words in
        // extra; use Horizon's normal component flattener for this version.
        Tag::Compound(_) => Some(crate::entity_nbt::name_text(t).plain()),
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

pack_patch("crates/horizon-client/src/game/names.rs", '''            let member = if player {
                Text::literal(&name)
            } else {''', '''            let member = if player {
                Text::legacy(&name)
            } else {''', "render legacy formatting in player-like NPC names")

