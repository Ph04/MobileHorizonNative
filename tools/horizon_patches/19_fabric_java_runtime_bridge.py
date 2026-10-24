"""Connect the opt-in Java bridge control plane to Horizon's real Rust mod lifecycle."""
from pathlib import Path

runtime = root / "crates/horizon-mods/src/runtime.rs"
if not runtime.is_file():
    raise SystemExit(f"Missing runtime source required for Java bridge integration: {runtime}")

def rr(old, new, label):
    replace_once(runtime, old, new)

rr(
'''use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, Instant, SystemTime},
};''',
'''use std::{
    collections::{BTreeMap, VecDeque},
    io::{BufReader, Read, Write},
    net::{SocketAddr, TcpStream},
    path::{Path, PathBuf},
    sync::{mpsc::{self, Receiver, SyncSender, TrySendError}, Arc, Mutex},
    thread,
    time::{Duration, Instant, SystemTime},
};''',
"Java bridge networking imports",
)

bridge_types = r"""
// Java/Fabric IPC is opt-in, isolated from the normal Horizon WASM API and
// bounded by a queue and a 64 KiB JSONL frame limit. This is a transport
// adapter, not an implementation of Minecraft's Java classes.
enum JavaBridgeMessage {
    Event { event: String, payload: serde_json::Value },
    Shutdown,
}

struct JavaBridge {
    sender: SyncSender<JavaBridgeMessage>,
    world_snapshot: Arc<Mutex<serde_json::Value>>,
}

impl JavaBridge {
    fn from_environment() -> Option<Self> {
        if std::env::var("HORIZON_MOD_PROFILE").ok().as_deref() != Some("fabric-bridge") {
            return None;
        }
        let port = std::env::var("HORIZON_FABRIC_BRIDGE_PORT")
            .ok().and_then(|raw| raw.parse::<u16>().ok()).filter(|port| *port != 0)?;
        let token = std::env::var("HORIZON_FABRIC_BRIDGE_TOKEN").ok()
            .filter(|token| !token.is_empty() && token.len() <= 128)?;
        let snapshot = Arc::new(Mutex::new(serde_json::json!({
            "world_loaded": false, "dimension": "", "singleplayer": false
        })));
        let (sender, receiver) = mpsc::sync_channel(128);
        let worker_snapshot = Arc::clone(&snapshot);
        thread::Builder::new()
            .name("horizon-fabric-java-ipc".into())
            .spawn(move || java_bridge_worker(port, token, receiver, worker_snapshot))
            .ok()?;
        log::info!("Horizon: opt-in Fabric Java bridge worker created (protocol v1); Minecraft API shims are not active");
        Some(Self { sender, world_snapshot: snapshot })
    }

    fn send_event(&self, event: &str, payload: serde_json::Value) {
        match self.sender.try_send(JavaBridgeMessage::Event {
            event: event.to_owned(), payload,
        }) {
            Ok(()) => {}
            Err(TrySendError::Full(_)) => {
                // Never make the renderer/game loop wait for IPC or Java startup.
                log::debug!("Horizon: Java bridge queue full; dropped event {event}");
            }
            Err(TrySendError::Disconnected(_)) => {
                log::debug!("Horizon: Java bridge worker is no longer connected");
            }
        }
    }

    fn update_world_snapshot(&self, payload: serde_json::Value) {
        let changed = {
            let Ok(mut latest) = self.world_snapshot.lock() else { return; };
            if *latest == payload {
                false
            } else {
                *latest = payload.clone();
                true
            }
        };
        if changed {
            self.send_event("world_snapshot", payload);
        }
    }
}

impl Drop for JavaBridge {
    fn drop(&mut self) {
        let _ = self.sender.try_send(JavaBridgeMessage::Shutdown);
    }
}

fn java_bridge_worker(
    port: u16,
    token: String,
    receiver: Receiver<JavaBridgeMessage>,
    world_snapshot: Arc<Mutex<serde_json::Value>>,
) {
    let address: SocketAddr = match format!("127.0.0.1:{port}").parse() {
        Ok(address) => address,
        Err(error) => {
            log::warn!("Horizon: invalid Java bridge endpoint: {error}");
            return;
        }
    };

    // NativeActivity prepares the listening socket before android_main starts.
    // Retry connection briefly rather than doing socket work on the game thread.
    let mut early_events = VecDeque::new();
    let mut stream = loop {
        match TcpStream::connect_timeout(&address, Duration::from_millis(250)) {
            Ok(stream) => break stream,
            Err(_error) => {
                match receiver.try_recv() {
                    Ok(JavaBridgeMessage::Shutdown) | Err(mpsc::TryRecvError::Disconnected) => return,
                    Ok(message) => {
                        if early_events.len() < 128 {
                            early_events.push_back(message);
                        } else {
                            log::debug!("Horizon: Java bridge startup queue full; dropping early event");
                        }
                    }
                    Err(mpsc::TryRecvError::Empty) => {}
                }
                thread::sleep(Duration::from_millis(150));
            }
        }
    };

    if let Err(error) = stream.set_nodelay(true) {
        log::warn!("Horizon: cannot enable TCP_NODELAY for Java bridge: {error}");
    }
    // Java 25 may still be downloading/installing on the worker thread before it
    // starts the host. A long read timeout is deliberate; Activity teardown closes
    // the socket and interrupts the wait without stalling the game loop.
    let _ = stream.set_read_timeout(Some(Duration::from_secs(600)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(10)));
    let mut reader = match stream.try_clone() {
        Ok(clone) => BufReader::new(clone),
        Err(error) => {
            log::warn!("Horizon: failed to clone Java bridge socket: {error}");
            return;
        }
    };

    let hello = serde_json::json!({
        "protocol_version": 1, "kind": "hello", "request_id": "native-hello",
        "session_token": token
    });
    if let Err(error) = java_bridge_write(&mut stream, &hello) {
        log::warn!("Horizon: Java bridge hello write failed: {error}");
        return;
    }
    let hello_result = match java_bridge_wait_response(
        &mut reader, &mut stream, "native-hello", &world_snapshot,
    ) {
        Ok(result) => result,
        Err(error) => {
            log::warn!("Horizon: Java bridge handshake failed: {error}");
            return;
        }
    };
    if !hello_result.get("ok").and_then(serde_json::Value::as_bool).unwrap_or(false) {
        log::warn!("Horizon: Java bridge rejected protocol v1 handshake");
        return;
    }
    log::info!("Horizon: Java bridge transport connected (protocol v1, world snapshot/query only)");

    // The endpoint may have been prepared before Minecraft finished booting;
    // always publish the latest snapshot once the host is ready.
    let initial_snapshot = world_snapshot.lock().map(|v| v.clone()).unwrap_or_else(|_| {
        serde_json::json!({"world_loaded": false, "dimension": "", "singleplayer": false})
    });
    let mut next_id: u64 = 0;
    let initial = serde_json::json!({
        "protocol_version": 1, "kind": "event", "request_id": "native-world-initial",
        "event": "world_snapshot", "payload": initial_snapshot
    });
    if java_bridge_write(&mut stream, &initial).is_err()
        || java_bridge_wait_response(&mut reader, &mut stream, "native-world-initial", &world_snapshot)
            .is_err()
    {
        log::debug!("Horizon: Java bridge ended during initial world synchronization");
        return;
    }

    loop {
        let message = if let Some(message) = early_events.pop_front() {
            message
        } else {
            match receiver.recv() {
                Ok(message) => message,
                Err(_) => break,
            }
        };
        match message {
            JavaBridgeMessage::Shutdown => {
                let frame = serde_json::json!({
                    "protocol_version": 1, "kind": "shutdown",
                    "request_id": format!("native-shutdown-{}", next_id)
                });
                let _ = java_bridge_write(&mut stream, &frame);
                break;
            }
            JavaBridgeMessage::Event { event, payload } => {
                next_id = next_id.saturating_add(1);
                let id = format!("native-{next_id}");
                let frame = serde_json::json!({
                    "protocol_version": 1, "kind": "event", "request_id": id,
                    "event": event, "payload": payload
                });
                if let Err(error) = java_bridge_write(&mut stream, &frame) {
                    log::warn!("Horizon: Java bridge event write failed: {error}");
                    break;
                }
                match java_bridge_wait_response(&mut reader, &mut stream, &id, &world_snapshot) {
                    Ok(response) => {
                        if !response.get("ok").and_then(serde_json::Value::as_bool).unwrap_or(false) {
                            log::debug!("Horizon: Java bridge declined event {event}: {}",
                                response.get("error").and_then(serde_json::Value::as_str).unwrap_or("unknown error"));
                        }
                    }
                    Err(error) => {
                        log::debug!("Horizon: Java bridge event channel closed: {error}");
                        break;
                    }
                }
            }
        }
    }
    log::info!("Horizon: Java bridge transport stopped");
}

fn java_bridge_write(stream: &mut TcpStream, frame: &serde_json::Value) -> std::io::Result<()> {
    let mut bytes = serde_json::to_vec(frame)
        .map_err(|error| std::io::Error::new(std::io::ErrorKind::InvalidData, error))?;
    if bytes.len() > 64 * 1024 {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "Java bridge frame exceeds 64 KiB"));
    }
    bytes.push(b'\n');
    stream.write_all(&bytes)?;
    stream.flush()
}

fn java_bridge_read(reader: &mut BufReader<TcpStream>) -> std::io::Result<serde_json::Value> {
    let mut bytes = Vec::with_capacity(256);
    loop {
        let mut byte = [0u8; 1];
        reader.read_exact(&mut byte)?;
        if byte[0] == b'\n' {
            break;
        }
        if byte[0] != b'\r' {
            if bytes.len() >= 64 * 1024 {
                return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "Java bridge frame exceeds 64 KiB"));
            }
            bytes.push(byte[0]);
        }
    }
    serde_json::from_slice(&bytes)
        .map_err(|error| std::io::Error::new(std::io::ErrorKind::InvalidData, error))
}

fn java_bridge_wait_response(
    reader: &mut BufReader<TcpStream>,
    writer: &mut TcpStream,
    request_id: &str,
    world_snapshot: &Arc<Mutex<serde_json::Value>>,
) -> Result<serde_json::Value, String> {
    loop {
        let frame = java_bridge_read(reader).map_err(|error| error.to_string())?;
        let kind = frame.get("kind").and_then(serde_json::Value::as_str).unwrap_or("");
        if kind == "query" {
            let query_id = frame.get("request_id").and_then(serde_json::Value::as_str).unwrap_or("");
            let path = frame.get("path").and_then(serde_json::Value::as_str).unwrap_or("");
            let response = match path {
                "world.snapshot" => {
                    let value = world_snapshot.lock().map(|snapshot| snapshot.clone())
                        .unwrap_or_else(|_| serde_json::json!({"world_loaded": false}));
                    serde_json::json!({"protocol_version":1,"kind":"response","request_id":query_id,
                        "ok":true,"result":value})
                }
                "bridge.capabilities" => serde_json::json!({
                    "protocol_version":1, "kind":"response", "request_id":query_id, "ok":true,
                    "result":{"read_only":["world.snapshot"],"events":["world_snapshot","world_join",
                        "world_leave","client_tick","chat_received"],
                        "unavailable":["minecraft_java_classes","fabric_api_entrypoints","mixins",
                            "registries","rendering","resource_reload","gameplay_mutation"]}
                }),
                _ => serde_json::json!({"protocol_version":1,"kind":"response","request_id":query_id,
                    "ok":false,"error":"unsupported_query"}),
            };
            java_bridge_write(writer, &response).map_err(|error| error.to_string())?;
            continue;
        }
        if kind != "response" {
            return Err(format!("unexpected frame kind: {kind}"));
        }
        let response_id = frame.get("request_id").and_then(serde_json::Value::as_str).unwrap_or("");
        if response_id != request_id {
            return Err(format!("response request_id mismatch: expected {request_id}, got {response_id}"));
        }
        return Ok(frame);
    }
}

"""
rr(
"#[derive(Default)]\npub struct Mods {",
bridge_types + "\n#[derive(Default)]\npub struct Mods {",
"Java bridge transport type",
)
rr(
'''pub struct Mods {
    engine: Option<(Engine, Linker<State>)>,
    p1_engine: Option<(Engine, Linker<P1State>)>,
    entries: Vec<Entry>,
    snapshot: Arc<Snapshot>,
    out: Vec<Output>,
}''',
'''pub struct Mods {
    engine: Option<(Engine, Linker<State>)>,
    p1_engine: Option<(Engine, Linker<P1State>)>,
    bridge: Option<JavaBridge>,
    entries: Vec<Entry>,
    snapshot: Arc<Snapshot>,
    out: Vec<Output>,
}''',
"Java bridge member in Mods",
)
rr(
'''        let mut mods = Mods::default();
        for path in paths {''',
'''        let mut mods = Mods::default();
        // No Java thread or socket is created for Vanilla. The explicit Android
        // profile and the per-session token/port are both required to activate it.
        mods.bridge = JavaBridge::from_environment();
        for path in paths {''',
"opt-in bridge startup",
)

rr(
'''    pub fn set_snapshot(&mut self, snapshot: Snapshot) {
        let previous = self.snapshot.player.as_ref().map(|player| P1WorldInfo {
            dimension: player.dimension.clone(),
            singleplayer: self.snapshot.singleplayer,
        });
        let current = snapshot.player.as_ref().map(|player| P1WorldInfo {
            dimension: player.dimension.clone(),
            singleplayer: snapshot.singleplayer,
        });
        self.snapshot = Arc::new(snapshot);

        match (previous, current) {
            (None, Some(info)) => self.dispatch_p1_world_event("world_join", &info),
            (Some(info), None) => self.dispatch_p1_world_event("world_leave", &info),
            (Some(previous), Some(current))
                if previous.dimension != current.dimension
                    || previous.singleplayer != current.singleplayer =>
            {
                self.dispatch_p1_world_event("world_leave", &previous);
                self.dispatch_p1_world_event("world_join", &current);
            }
            _ => {}
        }
    }''',
'''    pub fn set_snapshot(&mut self, snapshot: Snapshot) {
        let previous = self.snapshot.player.as_ref().map(|player| P1WorldInfo {
            dimension: player.dimension.clone(),
            singleplayer: self.snapshot.singleplayer,
        });
        let current = snapshot.player.as_ref().map(|player| P1WorldInfo {
            dimension: player.dimension.clone(),
            singleplayer: snapshot.singleplayer,
        });
        self.snapshot = Arc::new(snapshot);

        let bridge_snapshot = if let Some(player) = self.snapshot.player.as_ref() {
            serde_json::json!({
                "world_loaded": true,
                "dimension": player.dimension,
                "singleplayer": self.snapshot.singleplayer
            })
        } else {
            serde_json::json!({
                "world_loaded": false,
                "dimension": "",
                "singleplayer": self.snapshot.singleplayer
            })
        };
        if let Some(bridge) = &self.bridge {
            bridge.update_world_snapshot(bridge_snapshot);
            match (previous.as_ref(), current.as_ref()) {
                (None, Some(info)) => bridge.send_event("world_join", serde_json::json!({
                    "dimension": info.dimension, "singleplayer": info.singleplayer
                })),
                (Some(info), None) => bridge.send_event("world_leave", serde_json::json!({
                    "dimension": info.dimension, "singleplayer": info.singleplayer
                })),
                (Some(old), Some(new))
                    if old.dimension != new.dimension || old.singleplayer != new.singleplayer =>
                {
                    bridge.send_event("world_leave", serde_json::json!({
                        "dimension": old.dimension, "singleplayer": old.singleplayer
                    }));
                    bridge.send_event("world_join", serde_json::json!({
                        "dimension": new.dimension, "singleplayer": new.singleplayer
                    }));
                }
                _ => {}
            }
        }

        match (previous, current) {
            (None, Some(info)) => self.dispatch_p1_world_event("world_join", &info),
            (Some(info), None) => self.dispatch_p1_world_event("world_leave", &info),
            (Some(previous), Some(current))
                if previous.dimension != current.dimension
                    || previous.singleplayer != current.singleplayer =>
            {
                self.dispatch_p1_world_event("world_leave", &previous);
                self.dispatch_p1_world_event("world_join", &current);
            }
            _ => {}
        }
    }''',
"Java bridge snapshot/world lifecycle dispatch",
)

rr(
'''        self.each_p1("client_tick", FUEL_TICK, |m, s| {
            m.horizon_fabric_bridge_guest().call_on_tick(s, tick).map(|_| ())
        });
    }''',
'''        self.each_p1("client_tick", FUEL_TICK, |m, s| {
            m.horizon_fabric_bridge_guest().call_on_tick(s, tick).map(|_| ())
        });
        if tick % 5 == 0 {
            if let Some(bridge) = &self.bridge {
                bridge.send_event("client_tick", serde_json::json!({"tick": tick}));
            }
        }
    }''',
"throttled Java bridge client tick",
)

rr(
'''    pub fn chat_received(&mut self, message: P1ChatMessage) -> bool {
        let mut order: Vec<(u8, usize)> = self.entries.iter().enumerate()''',
'''    pub fn chat_received(&mut self, message: P1ChatMessage) -> bool {
        if let Some(bridge) = &self.bridge {
            bridge.send_event("chat_received", serde_json::json!({
                "text": message.text.clone(), "sender": message.sender.clone(), "system": message.system
            }));
        }
        let mut order: Vec<(u8, usize)> = self.entries.iter().enumerate()''',
"Java bridge received chat observation",
)

print("Connected opt-in Java IPC to Horizon's Rust mod lifecycle: world snapshots/transitions, throttled client ticks and received-chat observations.")
