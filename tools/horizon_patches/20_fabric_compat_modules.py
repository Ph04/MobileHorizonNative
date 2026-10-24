"""Install modular Fabric compatibility adapters into a pinned Horizon checkout."""
from pathlib import Path
import shutil

runtime = root / "crates/horizon-mods/src/runtime.rs"
game_mods = root / "crates/horizon-client/src/game/mods.rs"
if not runtime.is_file() or not game_mods.is_file():
    raise SystemExit("Missing pinned Horizon runtime/game source for compatibility overlay")

def rr(path, old, new, label):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor for {label}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

source_modules = Path(__file__).resolve().parents[1] / "fabric_java_compat" / "native_overlay" / "fabric_compat"
target_modules = root / "crates/horizon-mods/src/runtime/fabric_compat"
if not source_modules.is_dir():
    raise SystemExit(f"Missing modular compatibility source directory: {source_modules}")
if target_modules.exists():
    shutil.rmtree(target_modules)
shutil.copytree(source_modules, target_modules)

rr(runtime, "use crate::manifest::{Manifest, Package, Side};", """mod fabric_compat;
pub use fabric_compat::NativeQuery as FabricNativeQuery;
pub use fabric_compat::capability_report as fabric_compatibility_report;
pub use fabric_compat::biome::{parse_samples as parse_native_biome_samples, parse_search as parse_native_biome_search, SearchSpec as NativeBiomeSearchSpec};
use crate::manifest::{Manifest, Package, Side};""", "module declarations")

rr(runtime, """struct JavaBridge {
    sender: SyncSender<JavaBridgeMessage>,
    world_snapshot: Arc<Mutex<serde_json::Value>>,
}""", """struct JavaBridge {
    sender: SyncSender<JavaBridgeMessage>,
    world_snapshot: Arc<Mutex<serde_json::Value>>,
    query_receiver: Receiver<fabric_compat::NativeQuery>,
}""", "query receiver")

rr(runtime, """        let (sender, receiver) = mpsc::sync_channel(128);
        let worker_snapshot = Arc::clone(&snapshot);
        thread::Builder::new()
            .name("horizon-fabric-java-ipc".into())
            .spawn(move || java_bridge_worker(port, token, receiver, worker_snapshot))
            .ok()?;
        log::info!("Horizon: opt-in Fabric Java bridge worker created (protocol v1); Minecraft API shims are not active");
        Some(Self { sender, world_snapshot: snapshot })""", """        let (sender, receiver) = mpsc::sync_channel(128);
        let (query_sender, query_receiver) = mpsc::sync_channel(32);
        let worker_snapshot = Arc::clone(&snapshot);
        let worker_query_sender = query_sender.clone();
        thread::Builder::new()
            .name("horizon-fabric-java-ipc".into())
            .spawn(move || java_bridge_worker(port, token, receiver, worker_snapshot, worker_query_sender))
            .ok()?;
        log::info!("Horizon: opt-in Fabric Java bridge worker created (protocol v1); Minecraft Java facades remain incomplete");
        Some(Self { sender, world_snapshot: snapshot, query_receiver })""", "bounded query channel")
rr(runtime, """fn java_bridge_worker(
    port: u16,
    token: String,
    receiver: Receiver<JavaBridgeMessage>,
    world_snapshot: Arc<Mutex<serde_json::Value>>,
) {""", """fn java_bridge_worker(
    port: u16,
    token: String,
    receiver: Receiver<JavaBridgeMessage>,
    world_snapshot: Arc<Mutex<serde_json::Value>>,
    query_sender: SyncSender<fabric_compat::NativeQuery>,
) {""", "worker signature")
rr(runtime, """        &mut reader, &mut stream, "native-hello", &world_snapshot,
    )""", """        &mut reader, &mut stream, "native-hello", &world_snapshot, &query_sender,
    )""", "handshake RPC")
rr(runtime, """        || java_bridge_wait_response(&mut reader, &mut stream, "native-world-initial", &world_snapshot)
            .is_err()""", """        || java_bridge_wait_response(&mut reader, &mut stream, "native-world-initial", &world_snapshot, &query_sender)
            .is_err()""", "initial snapshot RPC")
rr(runtime, """                match java_bridge_wait_response(&mut reader, &mut stream, &id, &world_snapshot) {""", """                match java_bridge_wait_response(&mut reader, &mut stream, &id, &world_snapshot, &query_sender) {""", "event RPC")
rr(runtime, """    world_snapshot: &Arc<Mutex<serde_json::Value>>,
) -> Result<serde_json::Value, String> {
    loop {""", """    world_snapshot: &Arc<Mutex<serde_json::Value>>,
    query_sender: &SyncSender<fabric_compat::NativeQuery>,
) -> Result<serde_json::Value, String> {
    loop {""", "response loop signature")
rr(runtime, """                "bridge.capabilities" => serde_json::json!({
                    "protocol_version":1, "kind":"response", "request_id":query_id, "ok":true,
                    "result":{"read_only":["world.snapshot","player.snapshot","clock.snapshot",
                        "inventory.snapshot","inventory.selected","inventory.offhand"],
                        "events":["world_snapshot","world_join","world_leave","client_tick","chat_received"],
                        "unavailable":["minecraft_java_classes","fabric_api_entrypoints","mixins",
                            "registries","rendering","resource_reload","gameplay_mutation"]}
                }),
                _ => serde_json::json!({"protocol_version":1,"kind":"response","request_id":query_id,
                    "ok":false,"error":"unsupported_query"}),""", """                "bridge.capabilities" => serde_json::json!({
                    "protocol_version":1, "kind":"response", "request_id":query_id, "ok":true,
                    "result":{"read_only":["world.snapshot","player.snapshot","clock.snapshot",
                        "inventory.snapshot","inventory.selected","inventory.offhand"],
                        "events":["world_snapshot","world_join","world_leave","client_tick","chat_received"],
                        "unavailable":["minecraft_java_classes","fabric_api_entrypoints","mixins",
                            "registries","rendering","resource_reload","gameplay_mutation"],
                        "compatibility_modules":fabric_compat::capability_report()}
                }),
                _ => {
                    let timeout_ms = frame.get("timeout_ms").and_then(serde_json::Value::as_u64).unwrap_or(10_000);
                    match fabric_compat::forward_query(
                        path, query_id, frame.get("payload").cloned().unwrap_or(serde_json::Value::Null),
                        timeout_ms, query_sender,
                    ) {
                        Ok(result) => serde_json::json!({"protocol_version":1,"kind":"response","request_id":query_id,"ok":true,"target_marker":fabric_compat::marker_for_query(path),"result":result}),
                        Err(error) => serde_json::json!({"protocol_version":1,"kind":"response","request_id":query_id,"ok":false,"target_marker":fabric_compat::marker_for_query(path),"error":error}),
                    }
                },""", "query allow-list forwarding")

rr(runtime, """    pub fn set_snapshot(&mut self, snapshot: Snapshot) {""", """    pub fn java_bridge_active(&self) -> bool {
        self.bridge.is_some()
    }

    /// Drain a bounded batch; the caller must service queries on the game thread.
    pub fn drain_java_bridge_queries(&self, maximum: usize) -> Vec<FabricNativeQuery> {
        let Some(bridge) = &self.bridge else { return Vec::new(); };
        let mut requests = Vec::new();
        for _ in 0..maximum.min(16) {
            match bridge.query_receiver.try_recv() {
                Ok(request) => requests.push(request),
                Err(mpsc::TryRecvError::Empty) | Err(mpsc::TryRecvError::Disconnected) => break,
            }
        }
        requests
    }

    pub fn set_snapshot(&mut self, snapshot: Snapshot) {""", "Mods query drain")

rr(game_mods, """    /// What mods read this tick.
    fn mod_snapshot(&self) -> Snapshot {""", """    /// Service a bounded batch of Java compatibility queries on the game thread.
    fn service_fabric_native_queries(&mut self) {
        let requests = self.mods.mods.drain_java_bridge_queries(8);
        for request in requests {
            let result = if std::time::Instant::now() >= request.deadline {
                Err("native_query_timeout".to_owned())
            } else {
                self.handle_fabric_native_query(&request.path, &request.payload)
            };
            let _ = request.reply.send(result);
        }
    }

    fn handle_fabric_native_query(&self, path: &str, payload: &serde_json::Value) -> Result<serde_json::Value, String> {
        use serde_json::json;
        match path {
            "fabric.biome.catalog" | "fabric.registry.biomes" => {
                let entries = self.res.biomes().list.iter().enumerate().map(|(index, biome)| json!({
                    "numeric_id": index, "id": biome.name,
                    "temperature": biome.temperature, "downfall": biome.downfall,
                    "precipitation": biome.precipitation
                })).collect::<Vec<_>>();
                Ok(json!({"source":"horizon-native-biome-catalog","biomes":entries}))
            }
            "fabric.biome.search" => {
                let fallback = if payload.get("origin").is_some() { [0,64,0] } else {
                    self.player.as_ref().map(|p| [p.position.x.floor() as i32,p.position.y.floor() as i32,p.position.z.floor() as i32])
                        .ok_or_else(|| "player_position_unavailable".to_owned())?
                };
                let spec = horizon_mods::parse_native_biome_search(payload, fallback)?;
                let allowed = |name: &str| name == spec.biome_id;
                let search = horizon_world::worldgen::generator::BiomeSearch {
                    allowed: &allowed, origin: spec.origin, radius: spec.radius,
                    horizontal: spec.horizontal_step, vertical: spec.vertical_step,
                    min_y: crate::chunk::MIN_Y,
                    max_y: crate::chunk::MIN_Y + crate::chunk::HEIGHT - 1,
                };
                let locator = self.end.locator().ok_or_else(|| "native_biome_search_unavailable_for_this_session".to_owned())?;
                match locator.find_closest_biome(&search) {
                    Some((name, position)) => {
                        let dx = f64::from(position[0]-spec.origin[0]);
                        let dy = f64::from(position[1]-spec.origin[1]);
                        let dz = f64::from(position[2]-spec.origin[2]);
                        let distance = (dx*dx+dy*dy+dz*dz).sqrt().floor() as i64;
                        Ok(json!({"found":true,"biome_id":name,"position":position,"distance_blocks":distance,"backend":"horizon-native-worldgen","note":"Native backend data, not a Java Biome object."}))
                    }
                    None => Ok(json!({"found":false,"biome_id_requested":spec.biome_id,"origin":spec.origin,"backend":"horizon-native-worldgen"})),
                }
            }
            "fabric.biome.sample" => {
                let samples = horizon_mods::parse_native_biome_samples(payload, 256)?;
                let quarts = samples.iter().map(|p| [p[0].div_euclid(4),p[1].div_euclid(4),p[2].div_euclid(4)]).collect::<Vec<_>>();
                let locator = self.end.locator().ok_or_else(|| "native_biome_sample_unavailable_for_this_session".to_owned())?;
                let sampled = locator.noise_biomes(&quarts);
                let results = samples.iter().zip(quarts.iter()).zip(sampled.into_iter()).map(|((block,quart),biome)| json!({"block_position":block,"quart_position":quart,"biome_id":biome})).collect::<Vec<_>>();
                Ok(json!({"source":"horizon-native-worldgen","samples":results,"note":"Native data only; no Java Biome instances."}))
            }
            "fabric.resources.capabilities" | "fabric.mixins.capabilities"
            | "fabric.render.capabilities" | "fabric.networking.capabilities" => Ok(horizon_mods::fabric_compatibility_report()),
            _ => Err("unsupported_query".to_owned()),
        }
    }

    /// What mods read this tick.
    fn mod_snapshot(&self) -> Snapshot {""", "game-thread native query handler")
rr(game_mods, """    pub(super) fn tick_mods(&mut self) {
        if !self.mods.mods.is_running() {
            return;
        }
        let snapshot = self.mod_snapshot();
        self.mods.mods.set_snapshot(snapshot);
        self.mods.mods.tick(self.ticks);
        self.show_mod_output();
    }""", """    pub(super) fn tick_mods(&mut self) {
        let bridge_active = self.mods.mods.java_bridge_active();
        if !self.mods.mods.is_running() && !bridge_active { return; }
        let snapshot = self.mod_snapshot();
        self.mods.mods.set_snapshot(snapshot);
        self.service_fabric_native_queries();
        self.mods.mods.tick(self.ticks);
        self.show_mod_output();
    }""", "service native bridge when no WASM mod is enabled")

print("Installed Fabric compatibility modules and game-thread query servicing.")
