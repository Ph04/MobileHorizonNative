#!/usr/bin/env python3
"""Add the Android Java compatibility subprocess bridge to a pinned Horizon checkout.

This is an additive overlay: Vanilla returns without starting another process.
The Java bridge is only started when the selected instance has a validated loader module.
"""
from pathlib import Path
import textwrap

root = Path(__import__("sys").argv[1])
client = root / "crates/horizon-client/src"
lib = client / "lib.rs"
hooks = client / "game/native_hooks.rs"
bridge = client / "compatibility_bridge.rs"

if not lib.is_file() or not hooks.is_file():
    raise SystemExit("Horizon compatibility bridge anchors are missing")

rust_source = r'''//! Android-only bridge to an optional Java compatibility module.
//!
//! The selected module runs in a separate Java 25 process. Communication uses a
//! private Unix-domain socket and a versioned, line-oriented UTF-8/hex protocol.
//! Vanilla instances do not create a listener or launch Java.

use std::{
    collections::VecDeque,
    env,
    fs,
    io::{BufRead, BufReader, BufWriter, Read, Write},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicU64, Ordering},
        mpsc::{self, SyncSender, TryRecvError},
        Arc, Mutex, OnceLock,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

#[cfg(target_os = "android")]
use std::os::unix::{
    fs::PermissionsExt,
    net::{UnixListener, UnixStream},
};

const API_VERSION: &str = "1";
const MAX_FRAME_BYTES: usize = 512 * 1024;
const MAX_ACTIONS: usize = 64;
const ACCEPT_TIMEOUT: Duration = Duration::from_secs(60);

static BRIDGE: OnceLock<Mutex<Option<BridgeState>>> = OnceLock::new();
static NEXT_EVENT: AtomicU64 = AtomicU64::new(1);
static NEXT_SOCKET: AtomicU64 = AtomicU64::new(1);

pub struct BridgeAction {
    pub kind: String,
    pub payload_json: String,
}

struct BridgeState {
    sender: SyncSender<String>,
    actions: Arc<Mutex<VecDeque<BridgeAction>>>,
    worker: Option<JoinHandle<()>>,
}

fn bridge_state() -> &'static Mutex<Option<BridgeState>> {
    BRIDGE.get_or_init(|| Mutex::new(None))
}

#[cfg(not(target_os = "android"))]
pub fn start_from_environment() -> Result<(), String> {
    Ok(())
}

#[cfg(target_os = "android")]
pub fn start_from_environment() -> Result<(), String> {
    let loader = env::var("HORIZON_LOADER_ID").unwrap_or_else(|_| "vanilla".to_owned());
    if loader == "vanilla" {
        return Ok(());
    }

    let mut state = bridge_state()
        .lock()
        .map_err(|_| "compatibility bridge state lock is poisoned".to_owned())?;
    if state.is_some() {
        return Ok(());
    }

    let config = BridgeConfig::from_environment(&loader)?;
    let (listener, socket_path) = create_private_listener(&config)?;
    let mut child = match spawn_java_bridge(&config, &socket_path) {
        Ok(child) => child,
        Err(error) => {
            let _ = fs::remove_file(&socket_path);
            return Err(error);
        }
    };
    let stream = match accept_child(&listener, &mut child) {
        Ok(stream) => stream,
        Err(error) => {
            let _ = child.kill();
            let _ = child.wait();
            let _ = fs::remove_file(&socket_path);
            return Err(error);
        }
    };
    drop(listener);

    let (reader, writer) = match handshake(stream, &config) {
        Ok(pair) => pair,
        Err(error) => {
            let _ = child.kill();
            let _ = child.wait();
            let _ = fs::remove_file(&socket_path);
            return Err(error);
        }
    };

    let (sender, receiver) = mpsc::sync_channel::<String>(256);
    let actions = Arc::new(Mutex::new(VecDeque::new()));
    let worker_actions = Arc::clone(&actions);
    let worker_socket = socket_path.clone();
    let worker = thread::Builder::new()
        .name("horizon-compatibility-bridge".to_owned())
        .spawn(move || {
            bridge_io_loop(reader, writer, receiver, child, worker_actions, worker_socket);
        })
        .map_err(|error| {
            let _ = fs::remove_file(&socket_path);
            format!("could not start compatibility bridge worker: {error}")
        })?;

    *state = Some(BridgeState {
        sender,
        actions,
        worker: Some(worker),
    });
    log::info!(
        "Compatibility module connected: loader={} version={} instance={}",
        config.loader_id,
        config.loader_version,
        config.instance_id
    );
    Ok(())
}

#[cfg(target_os = "android")]
struct BridgeConfig {
    app_files: PathBuf,
    module_id: String,
    module_version: String,
    instance_id: String,
    minecraft_version: String,
    loader_id: String,
    loader_version: String,
    module_dir: PathBuf,
    entrypoint_jar: PathBuf,
    entrypoint_class: String,
    java: PathBuf,
    java_argv0: PathBuf,
    java_home: PathBuf,
    java_tmp: PathBuf,
    java_library_path: String,
    api_jar: PathBuf,
}

#[cfg(target_os = "android")]
impl BridgeConfig {
    fn from_environment(requested_loader: &str) -> Result<Self, String> {
        let app_files = canonical_env_path("HORIZON_APP_FILES_DIR")?;
        let instance_id = required_env("HORIZON_INSTANCE_ID")?;
        let minecraft_version = required_env("HORIZON_GAME_VERSION")?;
        let loader_id = required_env("HORIZON_LOADER_ID")?;
        let loader_version = required_env("HORIZON_LOADER_VERSION")?;
        let module_id = required_env("HORIZON_COMPATIBILITY_MODULE_ID")?;
        let module_version = required_env("HORIZON_COMPATIBILITY_MODULE_VERSION")?;
        let module_dir = canonical_env_path("HORIZON_COMPATIBILITY_MODULE_DIR")?;
        let entrypoint_jar = canonical_env_path("HORIZON_COMPATIBILITY_ENTRYPOINT_JAR")?;
        let entrypoint_class = required_env("HORIZON_COMPATIBILITY_ENTRYPOINT_CLASS")?;
        let api_version = required_env("HORIZON_COMPATIBILITY_API_VERSION")?;
        let java_argv0 = PathBuf::from(required_env("HORIZON_JAVA_EXECUTABLE")?);
        let java = java_argv0.canonicalize()
            .map_err(|error| format!("could not resolve HORIZON_JAVA_EXECUTABLE: {error}"))?;
        let java_home = canonical_env_path("HORIZON_JAVA_HOME")?;
        let java_tmp = canonical_env_path("HORIZON_JAVA_TMP")?;
        let java_library_path = required_env("HORIZON_JAVA_LIBRARY_PATH")?;
        let api_jar = canonical_env_path("HORIZON_COMPATIBILITY_API_JAR")?;

        if api_version != API_VERSION {
            return Err(format!(
                "compatibility API version mismatch: launcher={api_version}, runtime={API_VERSION}"
            ));
        }
        if loader_id != requested_loader || !matches!(loader_id.as_str(),
            "forge" | "fabric" | "neoforge" | "quilt") {
            return Err("selected loader ID is invalid or changed during startup".to_owned());
        }
        validate_segment(&instance_id, "instance ID", 80)?;
        validate_segment(&module_id, "module ID", 96)?;
        validate_segment(&module_version, "module version", 64)?;
        validate_version(&minecraft_version)?;
        validate_loader_version(&loader_version)?;

        let local_root = app_files.join(".local").canonicalize()
            .map_err(|error| format!("private .local directory is unavailable: {error}"))?;
        if local_root.parent() != Some(app_files.as_path()) {
            return Err("private .local directory escaped the application files directory".to_owned());
        }
        let modules_root = app_files.join("compatibility-modules").canonicalize()
            .map_err(|error| format!("compatibility module store is unavailable: {error}"))?;
        if !modules_root.starts_with(&app_files) {
            return Err("compatibility module store escaped app storage".to_owned());
        }
        let expected_module = modules_root.join(&module_id).join(&module_version)
            .canonicalize()
            .map_err(|error| format!("selected compatibility module is not installed: {error}"))?;
        if module_dir != expected_module || !module_dir.starts_with(&modules_root) {
            return Err("selected module directory does not match the trusted module store".to_owned());
        }
        let payload = module_dir.join("payload").canonicalize()
            .map_err(|error| format!("module payload folder is missing: {error}"))?;
        if !entrypoint_jar.is_file() || !entrypoint_jar.starts_with(&payload) {
            return Err("compatibility entrypoint JAR is outside the module payload".to_owned());
        }
        validate_class_name(&entrypoint_class)?;

        let descriptor_path = module_dir.join("mobilehorizon-plugin.json");
        let descriptor_bytes = fs::read(&descriptor_path)
            .map_err(|error| format!("could not read installed module descriptor: {error}"))?;
        if descriptor_bytes.len() > 64 * 1024 {
            return Err("installed module descriptor exceeds 64 KiB".to_owned());
        }
        let descriptor: serde_json::Value = serde_json::from_slice(&descriptor_bytes)
            .map_err(|error| format!("installed module descriptor is invalid JSON: {error}"))?;
        if descriptor["format"].as_i64() != Some(1)
            || descriptor["apiVersion"].as_i64() != Some(1)
            || descriptor["kind"].as_str() != Some("compatibility")
            || descriptor["id"].as_str() != Some(module_id.as_str())
            || descriptor["version"].as_str() != Some(module_version.as_str())
            || descriptor["loader"].as_str() != Some(loader_id.as_str())
            || descriptor["loaderVersion"].as_str() != Some(loader_version.as_str())
            || descriptor["entrypointJar"].as_str().is_none()
            || descriptor["entrypointJar"].as_str() != Some(
                entrypoint_jar.strip_prefix(&payload).ok()
                    .and_then(Path::to_str).unwrap_or("")
            )
            || descriptor["entrypointClass"].as_str() != Some(entrypoint_class.as_str())
        {
            return Err("installed module descriptor does not match the selected game profile".to_owned());
        }
        let supported_versions = descriptor["minecraftVersions"].as_array()
            .ok_or_else(|| "module does not declare supported Minecraft versions".to_owned())?;
        if !supported_versions.iter().any(|version| version.as_str() == Some(&minecraft_version)) {
            return Err("module does not support this Minecraft version".to_owned());
        }

        let expected_api = local_root.join("compatibility-runtime/compatibility-api.jar")
            .canonicalize()
            .map_err(|error| format!("Java compatibility API is not staged: {error}"))?;
        if api_jar != expected_api || !api_jar.is_file() {
            return Err("Java compatibility API JAR is outside the expected private directory".to_owned());
        }
        let runtime_root = local_root.join("horizon-java25").canonicalize()
            .map_err(|error| format!("Java 25 runtime is not installed: {error}"))?;
        let expected_home = runtime_root.join("usr/lib/jvm/java-25-openjdk")
            .canonicalize()
            .map_err(|error| format!("Java 25 home is missing: {error}"))?;
        if java_home != expected_home || !java.starts_with(&runtime_root)
            || !java_argv0.starts_with(&runtime_root) || !java.is_file()
            || !java_argv0.is_file() || !runtime_root.join(".ready").is_file()
            || !java_tmp.starts_with(&runtime_root) || !java_tmp.is_dir()
        {
            return Err("Java 25 executable or runtime paths failed private-store validation".to_owned());
        }

        Ok(Self {
            app_files,
            module_id,
            module_version,
            instance_id,
            minecraft_version,
            loader_id,
            loader_version,
            module_dir,
            entrypoint_jar,
            entrypoint_class,
            java,
            java_argv0,
            java_home,
            java_tmp,
            java_library_path,
            api_jar,
        })
    }
}

#[cfg(target_os = "android")]
fn create_private_listener(config: &BridgeConfig) -> Result<(UnixListener, PathBuf), String> {
    let local_root = config.app_files.join(".local").canonicalize()
        .map_err(|error| format!("private .local directory is unavailable: {error}"))?;
    let local = local_root.join("compatibility-runtime");
    fs::create_dir_all(&local)
        .map_err(|error| format!("could not create private compatibility runtime folder: {error}"))?;
    let local = local.canonicalize()
        .map_err(|error| format!("could not resolve private compatibility runtime folder: {error}"))?;
    if !local.starts_with(&local_root) {
        return Err("compatibility runtime folder escaped private .local storage".to_owned());
    }
    let nonce = NEXT_SOCKET.fetch_add(1, Ordering::Relaxed);
    let path = local.join(format!("b-{}-{nonce:x}.sock", std::process::id()));
    if path.to_string_lossy().len() > 100 {
        return Err("private bridge socket path exceeds the Android Unix-socket limit".to_owned());
    }
    let listener = UnixListener::bind(&path)
        .map_err(|error| format!("could not create private compatibility socket: {error}"))?;
    fs::set_permissions(&path, fs::Permissions::from_mode(0o600))
        .map_err(|error| format!("could not restrict compatibility socket permissions: {error}"))?;
    listener.set_nonblocking(true)
        .map_err(|error| format!("could not configure compatibility socket: {error}"))?;
    Ok((listener, path))
}

#[cfg(target_os = "android")]
fn spawn_java_bridge(config: &BridgeConfig, socket: &Path) -> Result<Child, String> {
    let java_elf = config.java.to_str().ok_or("Java ELF path is not UTF-8")?;
    let java_argv0 = config.java_argv0.to_str().ok_or("Java argv[0] path is not UTF-8")?;
    let socket = socket.to_str().ok_or("bridge socket path is not UTF-8")?;
    let java_tmp_option = format!("-Djava.io.tmpdir={}", config.java_tmp.display());

    let mut command = Command::new("/system/bin/sh");
    command.args([
        "-c",
        "exec /system/bin/linker64 \"$@\"",
        "linker64",
        java_elf,
        java_argv0,
        "-XX:-UsePerfData",
        "-XX:ActiveProcessorCount=4",
        "-XX:ParallelGCThreads=2",
        "-XX:ConcGCThreads=1",
        java_tmp_option.as_str(),
        "-cp",
        config.api_jar.to_str().ok_or("compatibility API path is not UTF-8")?,
        "dev.mobilehorizon.compat.api.BridgeMain",
        socket,
        config.module_id.as_str(),
        config.module_version.as_str(),
        config.instance_id.as_str(),
        config.minecraft_version.as_str(),
        config.loader_id.as_str(),
        config.loader_version.as_str(),
        config.module_dir.to_str().ok_or("module path is not UTF-8")?,
        config.entrypoint_jar.to_str().ok_or("entrypoint path is not UTF-8")?,
        &config.entrypoint_class,
    ]);

    let path = format!("{}:{}", config.java_home.join("bin").display(),
        env::var("PATH").unwrap_or_default());
    command
        .env("JAVA_HOME", &config.java_home)
        .env("PATH", path)
        .env("LD_LIBRARY_PATH", &config.java_library_path)
        .env("HOME", config.app_files.join(".local/horizon-java25/home"))
        .env("TMPDIR", &config.java_tmp)
        .env("PREFIX", config.app_files.join(".local/horizon-java25/usr"))
        .env("TERMUX_PREFIX", config.app_files.join(".local/horizon-java25/usr"))
        .env("JAVA_TOOL_OPTIONS", format!(
            "-XX:-UsePerfData -XX:ActiveProcessorCount=4 -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -Djava.io.tmpdir={}",
            config.java_tmp.display()
        ))
        .env("LANG", "C.UTF-8")
        .stdin(Stdio::null())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit());

    command.spawn().map_err(|error| {
        format!("could not start Java 25 compatibility bridge through Android linker64: {error}")
    })
}

#[cfg(target_os = "android")]
fn accept_child(listener: &UnixListener, child: &mut Child) -> Result<UnixStream, String> {
    let start = Instant::now();
    loop {
        match listener.accept() {
            Ok((stream, _)) => {
                stream.set_read_timeout(Some(Duration::from_secs(60)))
                    .map_err(|error| format!("could not set bridge read timeout: {error}"))?;
                stream.set_write_timeout(Some(Duration::from_secs(10)))
                    .map_err(|error| format!("could not set bridge write timeout: {error}"))?;
                return Ok(stream);
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if let Some(status) = child.try_wait()
                    .map_err(|wait| format!("could not inspect Java bridge process: {wait}"))?
                {
                    return Err(format!("Java compatibility bridge exited before connecting: {status}"));
                }
                if start.elapsed() > ACCEPT_TIMEOUT {
                    return Err("Java compatibility bridge did not connect within 60 seconds".to_owned());
                }
                thread::sleep(Duration::from_millis(25));
            }
            Err(error) => return Err(format!("could not accept Java compatibility bridge: {error}")),
        }
    }
}

#[cfg(target_os = "android")]
fn handshake(stream: UnixStream, config: &BridgeConfig)
    -> Result<(BufReader<UnixStream>, BufWriter<UnixStream>), String>
{
    let reader_stream = stream.try_clone()
        .map_err(|error| format!("could not clone compatibility socket: {error}"))?;
    let mut reader = BufReader::new(reader_stream);
    let mut writer = BufWriter::new(stream);
    let hello = read_frame(&mut reader)?;
    let fields: Vec<&str> = hello.split('|').collect();
    if fields.len() != 8 || fields[0] != "HELLO" || fields[1] != API_VERSION {
        return Err("Java compatibility bridge sent an invalid HELLO frame".to_owned());
    }
    let expected = [
        config.module_id.as_str(),
        config.module_version.as_str(),
        config.instance_id.as_str(),
        config.minecraft_version.as_str(),
        config.loader_id.as_str(),
        config.loader_version.as_str(),
    ];
    for (field, expected) in fields[2..].iter().zip(expected.iter()) {
        if decode_hex(field)? != *expected {
            return Err("Java compatibility bridge identity does not match the selected profile".to_owned());
        }
    }
    writer.write_all(format!("ACCEPT|{API_VERSION}\n").as_bytes())
        .and_then(|_| writer.flush())
        .map_err(|error| format!("could not accept Java compatibility bridge: {error}"))?;
    let loaded = read_frame(&mut reader)?;
    if loaded != format!("LOADED|{API_VERSION}") {
        return Err(format!("Java compatibility module did not load: {}", decode_error_frame(&loaded)));
    }
    Ok((reader, writer))
}

#[cfg(target_os = "android")]
fn read_limited_frame<R: BufRead>(reader: &mut R) -> std::io::Result<Option<String>> {
    let mut bytes = Vec::new();
    let count = (&mut *reader)
        .take((MAX_FRAME_BYTES + 1) as u64)
        .read_until(b'\n', &mut bytes)?;
    if count == 0 {
        return Ok(None);
    }
    if bytes.len() > MAX_FRAME_BYTES {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "compatibility bridge frame exceeds 512 KiB",
        ));
    }
    let line = String::from_utf8(bytes).map_err(|error| {
        std::io::Error::new(std::io::ErrorKind::InvalidData, error)
    })?;
    Ok(Some(line.trim_end_matches(&['\r', '\n'][..]).to_owned()))
}

#[cfg(target_os = "android")]
fn read_frame(reader: &mut BufReader<UnixStream>) -> Result<String, String> {
    read_limited_frame(reader)
        .map_err(|error| format!("could not read compatibility bridge frame: {error}"))?
        .ok_or_else(|| "Java compatibility bridge disconnected during handshake".to_owned())
}

fn decode_error_frame(frame: &str) -> String {
    if let Some(encoded) = frame.strip_prefix("ERROR|") {
        decode_hex(encoded).unwrap_or_else(|_| "Java bootstrap returned an invalid error frame".to_owned())
    } else {
        frame.to_owned()
    }
}

fn encode_hex(value: &str) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(value.len() * 2);
    for byte in value.as_bytes() {
        out.push(DIGITS[(byte >> 4) as usize] as char);
        out.push(DIGITS[(byte & 0x0f) as usize] as char);
    }
    out
}

fn decode_hex(value: &str) -> Result<String, String> {
    if value.len() % 2 != 0 {
        return Err("invalid hexadecimal compatibility frame".to_owned());
    }
    let mut bytes = Vec::with_capacity(value.len() / 2);
    for pair in value.as_bytes().chunks_exact(2) {
        let hi = (pair[0] as char).to_digit(16)
            .ok_or_else(|| "invalid hexadecimal compatibility frame".to_owned())?;
        let lo = (pair[1] as char).to_digit(16)
            .ok_or_else(|| "invalid hexadecimal compatibility frame".to_owned())?;
        bytes.push(((hi << 4) | lo) as u8);
    }
    String::from_utf8(bytes)
        .map_err(|_| "compatibility bridge frame is not UTF-8".to_owned())
}

#[cfg(target_os = "android")]
fn bridge_io_loop(
    mut reader: BufReader<UnixStream>,
    mut writer: BufWriter<UnixStream>,
    receiver: mpsc::Receiver<String>,
    mut child: Child,
    actions: Arc<Mutex<VecDeque<BridgeAction>>>,
    socket_path: PathBuf,
) {
    let _ = writer.get_ref().set_write_timeout(Some(Duration::from_secs(2)));
    let mut stopping = false;
    loop {
        loop {
            match receiver.try_recv() {
                Ok(frame) => {
                    if frame == "STOP" {
                        let _ = writer.write_all(b"STOP\n");
                        let _ = writer.flush();
                        let deadline = Instant::now() + Duration::from_secs(2);
                        while Instant::now() < deadline {
                            match read_limited_frame(&mut reader) {
                                Ok(None) => break,
                                Ok(Some(reply)) if reply == "STOPPED" => break,
                                Ok(Some(_)) => {}
                                Err(error) if matches!(
                                    error.kind(),
                                    std::io::ErrorKind::WouldBlock
                                        | std::io::ErrorKind::TimedOut
                                        | std::io::ErrorKind::Interrupted
                                ) => {}
                                Err(_) => break,
                            }
                        }
                        stopping = true;
                        break;
                    }
                    if writer.write_all(frame.as_bytes()).is_err()
                        || writer.write_all(b"\n").is_err()
                        || writer.flush().is_err()
                    {
                        stopping = true;
                        break;
                    }
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => {
                    stopping = true;
                    break;
                }
            }
        }
        if stopping {
            break;
        }

        match read_limited_frame(&mut reader) {
            Ok(None) => break,
            Ok(Some(frame)) => {
                if let Some(encoded) = frame.strip_prefix("LOG|") {
                    match decode_hex(encoded) {
                        Ok(message) => log::info!("Compatibility module: {message}"),
                        Err(error) => log::warn!("{error}"),
                    }
                } else if let Some(rest) = frame.strip_prefix("ACTION|") {
                    let fields: Vec<&str> = rest.splitn(2, '|').collect();
                    if fields.len() == 2 {
                        match (decode_hex(fields[0]), decode_hex(fields[1])) {
                            (Ok(kind), Ok(payload_json)) => {
                                if kind.len() <= 64 && payload_json.len() <= 64 * 1024 {
                                    if let Ok(mut queue) = actions.lock() {
                                        if queue.len() >= MAX_ACTIONS {
                                            queue.pop_front();
                                        }
                                        queue.push_back(BridgeAction { kind, payload_json });
                                    }
                                }
                            }
                            _ => log::warn!("Rejected malformed compatibility action frame"),
                        }
                    }
                } else if let Some(error) = frame.strip_prefix("ERROR|") {
                    log::error!(
                        "Compatibility module reported failure: {}",
                        decode_hex(error).unwrap_or_else(|_| "invalid error frame".to_owned())
                    );
                } else if frame != "PONG" && !frame.starts_with("RESULT|") && frame != "STOPPED" {
                    log::warn!("Unknown compatibility bridge frame: {}", frame);
                }
                if frame == "STOPPED" {
                    break;
                }
            }
            Err(error) if matches!(
                error.kind(),
                std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                    | std::io::ErrorKind::Interrupted
            ) => {
                if child.try_wait().ok().flatten().is_some() {
                    break;
                }
            }
            Err(error) => {
                log::warn!("Compatibility bridge socket error: {error}");
                break;
            }
        }
    }

    if child.try_wait().ok().flatten().is_none() {
        let _ = child.kill();
        let _ = child.wait();
    }
    let _ = fs::remove_file(socket_path);
}

pub fn emit_event(event_type: &str, payload_json: &str) {
    if event_type.is_empty() || event_type.len() > 96
        || event_type.contains('|') || event_type.contains('\n') || event_type.contains('\r')
        || payload_json.len() > 256 * 1024
    {
        return;
    }
    let id = NEXT_EVENT.fetch_add(1, Ordering::Relaxed);
    let frame = format!(
        "EVENT|{id}|{}|{}",
        encode_hex(event_type),
        encode_hex(payload_json)
    );
    let guard = match bridge_state().lock() {
        Ok(value) => value,
        Err(_) => return,
    };
    if let Some(state) = guard.as_ref() {
        if state.sender.try_send(frame).is_err() {
            log::debug!("Compatibility event queue is full; dropping event {event_type}");
        }
    }
}

pub fn drain_actions() -> Vec<BridgeAction> {
    let guard = match bridge_state().lock() {
        Ok(value) => value,
        Err(_) => return Vec::new(),
    };
    let Some(state) = guard.as_ref() else {
        return Vec::new();
    };
    let Ok(mut queue) = state.actions.lock() else {
        return Vec::new();
    };
    queue.drain(..).collect()
}

pub fn shutdown() {
    let state = bridge_state().lock().ok().and_then(|mut guard| guard.take());
    if let Some(mut state) = state {
        let _ = state.sender.send("STOP".to_owned());
        if let Some(worker) = state.worker.take() {
            let _ = worker.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compatibility_wire_codec_round_trips_unicode_and_delimiters() {
        let value = "Olá | Forge 雪";
        assert_eq!(decode_hex(&encode_hex(value)).unwrap(), value);
    }

    #[test]
    fn compatibility_wire_codec_rejects_invalid_hex() {
        assert!(decode_hex("abc").is_err());
        assert!(decode_hex("gg").is_err());
    }
}

#[cfg(target_os = "android")]
fn required_env(key: &str) -> Result<String, String> {
    let value = env::var(key).map_err(|_| format!("required compatibility environment variable {key} is missing"))?;
    if value.is_empty() || value.len() > 4096 || value.contains('\0') || value.contains('\n') || value.contains('\r') || value.contains('|') {
        return Err(format!("compatibility environment variable {key} has an invalid value"));
    }
    Ok(value)
}

#[cfg(target_os = "android")]
fn canonical_env_path(key: &str) -> Result<PathBuf, String> {
    let value = required_env(key)?;
    PathBuf::from(value).canonicalize()
        .map_err(|error| format!("could not resolve {key}: {error}"))
}

#[cfg(target_os = "android")]
fn validate_segment(value: &str, label: &str, maximum: usize) -> Result<(), String> {
    if value.is_empty() || value.len() > maximum
        || !value.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c))
        || value == "." || value == ".."
    {
        return Err(format!("{label} is unsafe"));
    }
    Ok(())
}

#[cfg(target_os = "android")]
fn validate_version(value: &str) -> Result<(), String> {
    if value.is_empty() || value.len() > 32 || !value.chars().all(|c| c.is_ascii_digit() || c == '.') {
        return Err("Minecraft version is invalid".to_owned());
    }
    Ok(())
}

#[cfg(target_os = "android")]
fn validate_loader_version(value: &str) -> Result<(), String> {
    if value.is_empty() || value.len() > 48
        || !value.chars().all(|c| c.is_ascii_alphanumeric() || "._+-".contains(c))
    {
        return Err("loader version is invalid".to_owned());
    }
    Ok(())
}

#[cfg(target_os = "android")]
fn validate_class_name(value: &str) -> Result<(), String> {
    let valid = value.split('.').all(|part| {
        !part.is_empty()
            && part.chars().next().is_some_and(|c| c.is_ascii_alphabetic() || c == '_' || c == '$')
            && part.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '$')
    });
    if !valid || value.len() > 240 {
        return Err("compatibility entrypoint class name is invalid".to_owned());
    }
    Ok(())
}
'''
bridge.write_text(textwrap.dedent(rust_source), encoding="utf-8")
if "pub mod compatibility_bridge;" not in lib.read_text(encoding="utf-8"):
    content = lib.read_text(encoding="utf-8")
    marker = "pub mod control;\n"
    if content.count(marker) != 1:
        raise SystemExit(f"{lib}: expected one public control module declaration")
    lib.write_text(content.replace(marker, "pub mod compatibility_bridge;\n" + marker, 1), encoding="utf-8")

hook_steps = [
    (
        '''    pub(super) fn native_frame(&mut self, elapsed: Duration) {
        let _ = elapsed;
    }''',
        '''    pub(super) fn native_frame(&mut self, elapsed: Duration) {
        let payload = serde_json::json!({ "elapsed_millis": elapsed.as_millis().min(u64::MAX as u128) as u64 }).to_string();
        crate::compatibility_bridge::emit_event("frame", &payload);
    }''',
        "frame hook",
    ),
    (
        '''    pub(super) fn native_tick(&mut self) {}''',
        '''    pub(super) fn native_tick(&mut self) {
        for action in crate::compatibility_bridge::drain_actions() {
            match action.kind.as_str() {
                "log" => log::info!("Compatibility action: {}", action.payload_json),
                other => log::warn!("Compatibility action not handled by this Horizon build: {other}"),
            }
        }
        crate::compatibility_bridge::emit_event("tick", "{}");
    }''',
        "game tick hook",
    ),
    (
        '''    pub(super) fn native_world_lifecycle(
        &mut self,
        context: &NativeLifecycleContext,
        phase: WorldPhase,
        error_text: &str,
    ) {
        let _ = (context, phase, error_text);
    }''',
        '''    pub(super) fn native_world_lifecycle(
        &mut self,
        context: &NativeLifecycleContext,
        phase: WorldPhase,
        error_text: &str,
    ) {
        let payload = serde_json::json!({
            "phase": format!("{phase:?}"),
            "authority": format!("{:?}", context.authority),
            "lifecycle_context": format!("{context:?}"),
            "error": error_text
        }).to_string();
        crate::compatibility_bridge::emit_event("world_lifecycle", &payload);
    }''',
        "world lifecycle hook",
    ),
    (
        '''    pub(super) fn native_server_tick(
        &mut self,
        context: &NativeLifecycleContext,
        phase: ServerTickPhase,
    ) {
        let _ = (context, phase);
    }''',
        '''    pub(super) fn native_server_tick(
        &mut self,
        context: &NativeLifecycleContext,
        phase: ServerTickPhase,
    ) {
        let payload = serde_json::json!({
            "phase": format!("{phase:?}"),
            "authority": format!("{:?}", context.authority),
            "lifecycle_context": format!("{context:?}")
        }).to_string();
        crate::compatibility_bridge::emit_event("server_tick", &payload);
    }''',
        "server tick hook",
    ),
    (
        '''    pub(super) fn native_key(&mut self, key: &str, pressed: bool) {
        let _ = (key, pressed);
    }''',
        '''    pub(super) fn native_key(&mut self, key: &str, pressed: bool) {
        let payload = serde_json::json!({ "key": key, "pressed": pressed }).to_string();
        crate::compatibility_bridge::emit_event("key", &payload);
    }''',
        "key hook",
    ),
]

hooks_text = hooks.read_text(encoding="utf-8")
for old, new, label in hook_steps:
    count = hooks_text.count(old)
    if count == 0 and new in hooks_text:
        continue
    if count != 1:
        raise SystemExit(f"{hooks}: expected one {label} anchor, found {count}")
    hooks_text = hooks_text.replace(old, new, 1)
hooks.write_text(hooks_text, encoding="utf-8")
print("Added the Android compatibility subprocess bridge and tick/frame/lifecycle/key events.")
