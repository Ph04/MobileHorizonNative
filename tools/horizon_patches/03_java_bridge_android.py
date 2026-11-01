# The upstream bridge previously logged a Fabric preparation error and then started a
# bare JVM (fabric=None). That made the game open while all Fabric mods were absent.
# Make Fabric startup fail-closed and persist a stage-by-stage diagnostic trail.
java_mods_source_path = root / "crates/horizon-client/src/game/java_mods.rs"
jvm_source_path = root / "crates/horizon-jvm/src/lib.rs"
fabric_boot_path = root / "crates/horizon-jvm/java/dev/horizonlab/bridge/fabric/FabricBoot.java"
fabric_test_path = root / "crates/horizon-jvm/tests/fabric.rs"

for required_bridge_source in (java_mods_source_path, jvm_source_path, fabric_boot_path, fabric_test_path):
    if not required_bridge_source.is_file():
        raise SystemExit(f"Missing Java Bridge initialization source: {required_bridge_source}")

java_mods_source = java_mods_source_path.read_text(encoding="utf-8")
java_mods_import = "use std::path::{Path, PathBuf};"
java_mods_import_new = """use std::{
    fs::OpenOptions,
    io::Write,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};"""
if java_mods_source.count(java_mods_import) != 1:
    raise SystemExit(f"{java_mods_source_path}: expected one standard path import")
java_mods_source = java_mods_source.replace(java_mods_import, java_mods_import_new, 1)

probe_anchor = """const PROBE_CLASSES: &[&str] = &[
    "net/minecraft/world/level/block/Blocks",
    "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents",
];"""
probe_with_logger = probe_anchor + """

/// Logs to Android Logcat and, when configured by NativeGameActivity, to a persistent file.
fn bridge_log(level: &str, message: &str) {
    match level {
        "ERROR" => log::error!("[Java Bridge] {message}"),
        "WARN" => log::warn!("[Java Bridge] {message}"),
        _ => log::info!("[Java Bridge] {message}"),
    }
    let Some(path) = std::env::var_os("HORIZON_JAVA_BRIDGE_LOG_FILE") else {
        return;
    };
    let path = PathBuf::from(path);
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default();
    if let Ok(mut output) = OpenOptions::new().create(true).append(true).open(&path) {
        let _ = writeln!(output, "[{timestamp}][{level}][JavaBridge] {message}");
    }
}"""
if java_mods_source.count(probe_anchor) != 1:
    raise SystemExit(f"{java_mods_source_path}: PROBE_CLASSES anchor changed")
java_mods_source = java_mods_source.replace(probe_anchor, probe_with_logger, 1)

empty_jars_block = """        if jars.is_empty() {
            return;
        }"""
logged_empty_jars_block = """        if jars.is_empty() {
            bridge_log("INFO", "Nenhum JAR ativo em mods-java; o JVM/Fabric não será iniciado para esta instância.");
            return;
        }
        bridge_log(
            "INFO",
            &format!("Descobertos {} JAR(s) em mods-java: {}", jars.len(), dir.display()),
        );"""
if java_mods_source.count(empty_jars_block) != 1:
    raise SystemExit(f"{java_mods_source_path}: active-JAR guard changed")
java_mods_source = java_mods_source.replace(empty_jars_block, logged_empty_jars_block, 1)

start_marker = "fn start_once(root: &Path, dir: &Path, jars: Vec<PathBuf>, side: horizon_jvm::Side) -> bool {"
end_marker = "/// Fabric's part of the config: the prepared game jar and libraries, and Fabric API unless"
start_at = java_mods_source.find(start_marker)
end_at = java_mods_source.find(end_marker, start_at + len(start_marker))
if start_at < 0 or end_at < 0 or java_mods_source.find(start_marker, start_at + 1) >= 0:
    raise SystemExit(f"{java_mods_source_path}: unable to isolate start_once() for fail-closed patch")
new_start_once = """fn start_once(root: &Path, dir: &Path, jars: Vec<PathBuf>, side: horizon_jvm::Side) -> bool {
    if let Some(bridge) = horizon_jvm::get() {
        if !bridge.fabric() {
            bridge_log("ERROR", "Já existe uma JVM sem Fabric neste processo; não é seguro reiniciá-la.");
            return false;
        }
        bridge_log("INFO", "JVM/Fabric já inicializados neste processo; reutilizando a instância ativa.");
        return true;
    }

    // JNI permits one JVM per process: a failed initialization cannot be safely retried.
    static TRIED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
    if TRIED.swap(true, std::sync::atomic::Ordering::SeqCst) {
        bridge_log("ERROR", "A inicialização Java já foi tentada e falhou; uma segunda JVM não é permitida.");
        return false;
    }

    let started = std::time::Instant::now();
    let work = root.join(".local/bridge");
    bridge_log(
        "INFO",
        &format!(
            "Etapa 1/7: preparando Fabric Loader; lado={side:?}, mods={}, diretório={}",
            jars.len(),
            dir.display()
        ),
    );

    // Never turn a Fabric preparation error into a successful bare-JVM startup.
    let fabric_config = match fabric(root, dir, &work, &jars, side) {
        Ok(fabric) => fabric,
        Err(error) => {
            bridge_log("ERROR", &format!("Etapa 1/7 falhou ao preparar Fabric: {error}"));
            return false;
        }
    };
    if !horizon_jvm::platform_embedded() {
        bridge_log(
            "ERROR",
            "O runtime não contém horizon-platform.jar válido; os eventos e recursos de mods não podem ser integrados.",
        );
        return false;
    }
    bridge_log(
        "INFO",
        &format!(
            "Etapa 2/7: dados do Minecraft prontos; versão={}, cliente={}, bibliotecas={}, mods adicionais={}, plataforma embarcada=sim",
            fabric_config.version,
            fabric_config.game_jar.display(),
            fabric_config.libraries.len(),
            fabric_config.add_mods.len()
        ),
    );

    let home = match horizon_jvm::find_java_home() {
        Ok(home) => home,
        Err(error) => {
            bridge_log("ERROR", &format!("Etapa 3/7 falhou ao localizar Java 25: {error}"));
            return false;
        }
    };
    let libjvm = match horizon_jvm::libjvm(&home) {
        Ok(library) => library,
        Err(error) => {
            bridge_log("ERROR", &format!("Etapa 3/7 falhou ao localizar libjvm: {error}"));
            return false;
        }
    };
    bridge_log(
        "INFO",
        &format!(
            "Etapa 3/7: Java home={}, libjvm={}, carregando dependências do Fabric em {}",
            home.display(),
            libjvm.display(),
            work.display()
        ),
    );

    let config = horizon_jvm::Config {
        home,
        work: work.clone(),
        jars: jars.clone(),
        class_path: Vec::new(),
        fabric: Some(fabric_config),
    };
    bridge_log("INFO", "Etapa 4/7: criando a JVM por JNI e inicializando Knot/Fabric Loader.");
    let bridge = match horizon_jvm::start(&config) {
        Ok(bridge) => bridge,
        Err(error) => {
            bridge_log("ERROR", &format!("Etapa 4/7 falhou ao criar JVM/inicializar Fabric: {error}"));
            return false;
        }
    };
    if !bridge.fabric() {
        bridge_log(
            "ERROR",
            "A chamada de inicialização retornou uma JVM sem Fabric; abortando a integração de mods.",
        );
        return false;
    }

    let mods: Vec<&str> = bridge
        .mods()
        .iter()
        .map(String::as_str)
        .filter(|mod_id| !mod_id.starts_with("fabric-") || mod_id.starts_with("fabric-api "))
        .collect();
    bridge_log(
        "INFO",
        &format!(
            "Etapa 5/7: Fabric Loader ativo; JVM={}, Java={}, {} mod(s): {}",
            bridge.fabric(),
            bridge.java_version(),
            bridge.mods().len(),
            mods.join(", ")
        ),
    );
    if !bridge.mods().iter().any(|mod_id| mod_id.starts_with("fabric-api ")) {
        bridge_log(
            "ERROR",
            "Fabric API não aparece na lista de mods carregados; a inicialização será considerada incompleta.",
        );
        return false;
    }

    bridge_log("INFO", "Etapa 6/7: verificando classes essenciais do Minecraft e Fabric API.");
    for class in PROBE_CLASSES {
        match bridge.check_class(class) {
            Ok(()) => bridge_log("INFO", &format!("Classe essencial carregada: {class}")),
            Err(error) => {
                bridge_log(
                    "ERROR",
                    &format!("Classe essencial ausente ({class}); Fabric não será considerado pronto: {error}"),
                );
                return false;
            }
        }
    }

    bridge_log(
        "INFO",
        &format!(
            "Etapa 7/7: Java Bridge inicializado com sucesso em {} ms; {} JAR(s) ativos em {}.",
            started.elapsed().as_millis(),
            jars.len(),
            dir.display()
        ),
    );
    true
}

"""
java_mods_source = (
    java_mods_source[:start_at]
    + new_start_once
    + java_mods_source[end_at:]
)

# Log the pinned Fabric API dependency and the exact game classpath before JVM startup.
fabric_body_marker = """    let add_mods = if own_fabric_api {
        Vec::new()
    } else {
        vec![horizon_jvm::libs::ensure_fabric_api(
            &work.join("fabric-api"),
        )?]
    };
    Ok(horizon_jvm::Fabric {
        game_jar: horizon_jvm::facade::game_jar(root)?,
        libraries: horizon_jvm::facade::game_libraries(root)?,"""
fabric_body_new = """    let add_mods = if own_fabric_api {
        bridge_log("WARN", "Um Fabric API fornecido pelo usuário foi detectado em mods-java; usando essa cópia.");
        Vec::new()
    } else {
        let api_dir = work.join("fabric-api");
        bridge_log("INFO", &format!("Baixando/verificando Fabric API fixado em {}", api_dir.display()));
        let api = horizon_jvm::libs::ensure_fabric_api(&api_dir)?;
        bridge_log("INFO", &format!("Fabric API verificado: {}", api.display()));
        vec![api]
    };
    let game_jar = horizon_jvm::facade::game_jar(root)?;
    let libraries = horizon_jvm::facade::game_libraries(root)?;
    let version = game_version(root)?;
    bridge_log(
        "INFO",
        &format!(
            "Classpath do jogo validado: versão={}, client.jar={}, {} biblioteca(s), side={side:?}",
            version,
            game_jar.display(),
            libraries.len()
        ),
    );
    Ok(horizon_jvm::Fabric {
        game_jar,
        libraries,"""
if java_mods_source.count(fabric_body_marker) != 1:
    raise SystemExit(f"{java_mods_source_path}: Fabric configuration anchor changed")
java_mods_source = java_mods_source.replace(fabric_body_marker, fabric_body_new, 1)
java_mods_source = java_mods_source.replace(
    "        version: game_version(root)?,",
    "        version,",
    1,
)
java_mods_source_path.write_text(java_mods_source, encoding="utf-8")

# Persist JVM/Fabric's Java-side messages too (Knot, Mixin, game bootstrap, entrypoints).
jvm_source = jvm_source_path.read_text(encoding="utf-8")
emit_old = """fn emit(line: &str) {
    match *SINK.lock().unwrap_or_else(|e| e.into_inner()) {
        Some(sink) => sink(line),
        None => log::info!("[java] {line}"),
    }
}"""
emit_new = """fn emit(line: &str) {
    use std::io::Write as _;
    if let Some(path) = std::env::var_os("HORIZON_JAVA_BRIDGE_LOG_FILE") {
        let path = PathBuf::from(path);
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(mut output) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
            let _ = writeln!(output, "[JVM/Fabric] {line}");
        }
    }
    match *SINK.lock().unwrap_or_else(|e| e.into_inner()) {
        Some(sink) => sink(line),
        None => log::info!("[java] {line}"),
    }
}"""
if jvm_source.count(emit_old) != 1:
    raise SystemExit(f"{jvm_source_path}: Java log emitter anchor changed")
jvm_source = jvm_source.replace(emit_old, emit_new, 1)

platform_anchor = """const PLATFORM_JAR: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/horizon-platform.jar"));"""
platform_export = platform_anchor + """

/// Whether this runtime embeds the game-specific platform mod required for game events/content.
pub fn platform_embedded() -> bool {
    PLATFORM_JAR.len() > 4
}"""
if jvm_source.count(platform_anchor) != 1:
    raise SystemExit(f"{jvm_source_path}: embedded platform JAR anchor changed")
jvm_source = jvm_source.replace(platform_anchor, platform_export, 1)

# JNI_CreateJavaVM receives LWJGL's library path as a real VM option. This avoids
# relying on JAVA_TOOL_OPTIONS when the JVM is embedded inside the native engine.
vm_options_anchor = """    let options = [
        // Leave SIGINT, SIGTERM and SIGHUP to the game: Ctrl+C and shutdown stay Horizon's.
        "-Xrs".to_string(),
        "-Dfile.encoding=UTF-8".into(),
        "-Dhorizon.bridge=1".into(),
        format!("-Djava.class.path={}", joined.to_string_lossy()),
    ];"""
vm_options_replacement = """    // Android's guard sets conservative values from physical RAM. Keep safe
    // defaults when the bridge is launched outside the normal Android activity.
    let heap_initial = std::env::var("HORIZON_JAVA_HEAP_INITIAL_MIB")
        .ok()
        .and_then(|value| value.parse::<u32>().ok())
        .filter(|value| (64..=2048).contains(value))
        .unwrap_or(128);
    let heap_max = std::env::var("HORIZON_JAVA_HEAP_MAX_MIB")
        .ok()
        .and_then(|value| value.parse::<u32>().ok())
        .filter(|value| (512..=4096).contains(value))
        .filter(|value| *value > heap_initial)
        .unwrap_or(896);
    let processors = std::env::var("HORIZON_JAVA_PROCESSORS")
        .ok()
        .and_then(|value| value.parse::<u32>().ok())
        .filter(|value| (1..=8).contains(value))
        .unwrap_or(2);
    let gc_threads = std::env::var("HORIZON_JAVA_GC_THREADS")
        .ok()
        .and_then(|value| value.parse::<u32>().ok())
        .filter(|value| (1..=4).contains(value))
        .unwrap_or(1)
        .min(processors);
    let mut options = vec![
        // Leave SIGINT, SIGTERM and SIGHUP to the game: Ctrl+C and shutdown stay Horizon's.
        "-Xrs".to_string(),
        format!("-Xms{heap_initial}m"),
        format!("-Xmx{heap_max}m"),
        "-XX:+UseG1GC".into(),
        format!("-XX:ActiveProcessorCount={processors}"),
        format!("-XX:ParallelGCThreads={gc_threads}"),
        "-XX:ConcGCThreads=1".into(),
        "-XX:MaxGCPauseMillis=180".into(),
        format!(
            "-Djava.util.concurrent.ForkJoinPool.common.parallelism={}",
            processors.saturating_sub(1).max(1)
        ),
        "-Dfile.encoding=UTF-8".into(),
        "-Dhorizon.bridge=1".into(),
        format!("-Djava.class.path={}", joined.to_string_lossy()),
    ];
    log::info!(
        "Java Bridge JVM limits: heap={}..{} MiB, processors={}, GC threads={}",
        heap_initial, heap_max, processors, gc_threads
    );
    if let Some(raw_directory) = std::env::var_os("HORIZON_LWJGL_LIBRARY_PATH") {
        let directory = PathBuf::from(raw_directory);
        let library = directory.join("liblwjgl.so");
        if !directory.is_absolute() || !library.is_file() {
            return Err(format!(
                "LWJGL native library directory is invalid or has no liblwjgl.so: {}",
                directory.display()
            ));
        }
        options.push(format!(
            "-Dorg.lwjgl.librarypath={}",
            directory.to_string_lossy()
        ));
        log::info!(
            "Java Bridge: passing -Dorg.lwjgl.librarypath={} directly to JNI_CreateJavaVM",
            directory.display()
        );
    }"""
if jvm_source.count(vm_options_anchor) != 1:
    raise SystemExit(f"{jvm_source_path}: JNI VM options anchor changed")
jvm_source = jvm_source.replace(vm_options_anchor, vm_options_replacement, 1)
jvm_source_path.write_text(jvm_source, encoding="utf-8")

# Make the actual FabricBoot lifecycle visible in the same persistent log file.
fabric_boot_source = fabric_boot_path.read_text(encoding="utf-8")
fabric_boot_replacements = [
    (
        """        Knot knot = new Knot(env);
        gameLoader = knot.init(new String[0]);
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        bootstrapGame();""",
        """        Natives.log("Java Bridge: initializing Knot, Fabric Loader and the Mixin transformer");
        Knot knot = new Knot(env);
        gameLoader = knot.init(new String[0]);
        Natives.log("Java Bridge: Knot initialized; Fabric Loader/Mixin class transformation is active");
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        Natives.log("Java Bridge: bootstrapping Minecraft classes and registries");
        bootstrapGame();""",
    ),
    (
        """        try {
            loader.invokeEntrypoints("main", ModInitializer.class, ModInitializer::onInitialize);""",
        """        Natives.log("Java Bridge: running Fabric main entrypoints");
        try {
            loader.invokeEntrypoints("main", ModInitializer.class, ModInitializer::onInitialize);""",
    ),
    (
        """        if (env == EnvType.CLIENT) {
            // Every client entrypoint runs, then Fabric throws what failed:""",
        """        if (env == EnvType.CLIENT) {
            Natives.log("Java Bridge: running Fabric client entrypoints");
            // Every client entrypoint runs, then Fabric throws what failed:""",
    ),
    (
        """        freezeRegistries();
        List<String> mods = new ArrayList<>();""",
        """        Natives.log("Java Bridge: finalizing registries after mod entrypoints");
        freezeRegistries();
        List<String> mods = new ArrayList<>();""",
    ),
]
for old, new in fabric_boot_replacements:
    if fabric_boot_source.count(old) != 1:
        raise SystemExit(f"{fabric_boot_path}: FabricBoot lifecycle anchor changed: {old[:75]!r}")
    fabric_boot_source = fabric_boot_source.replace(old, new, 1)
fabric_boot_path.write_text(fabric_boot_source, encoding="utf-8")

# A host-side CI test must reject a runtime compiled without horizon-platform.jar.
fabric_test_source = fabric_test_path.read_text(encoding="utf-8")
test_anchor = """fn fabric_loads_a_mod_runs_its_entrypoints_and_applies_its_mixin() {
    let Some(home) = java_home() else {"""
test_replacement = """fn fabric_loads_a_mod_runs_its_entrypoints_and_applies_its_mixin() {
    assert!(
        horizon_jvm::platform_embedded(),
        "horizon-platform.jar is empty or missing; Fabric content/events would be unavailable"
    );
    let Some(home) = java_home() else {"""
if fabric_test_source.count(test_anchor) != 1:
    raise SystemExit(f"{fabric_test_path}: Fabric integration test anchor changed")
fabric_test_source = fabric_test_source.replace(test_anchor, test_replacement, 1)
fabric_test_path.write_text(fabric_test_source, encoding="utf-8")

# Protect Minecraft 26.3's one-time structure-position maps from simultaneous worldgen workers.
platform_dir = root / "crates/horizon-jvm/java/platform"
worldgen_mixin_path = platform_dir / "dev/horizonlab/platform/mixin/ChunkGeneratorStructureStateMixin.java"
worldgen_mixins_json = platform_dir / "horizon-platform.mixins.json"
worldgen_mixin_source = """package dev.horizonlab.platform.mixin;

import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Serializes the FastUtil placement/ring maps and their completion flag per world state. */
@Mixin(ChunkGeneratorStructureState.class)
public abstract class ChunkGeneratorStructureStateMixin {
    @Shadow
    private boolean hasGeneratedPositions;

    @Invoker("generatePositions")
    protected abstract void horizon$generatePositions();

    @Inject(method = "ensureStructuresGenerated", at = @At("HEAD"), cancellable = true)
    private void horizon$synchronizeStructurePositions(CallbackInfo ci) {
        synchronized (this) {
            if (!hasGeneratedPositions) {
                horizon$generatePositions();
                hasGeneratedPositions = true;
            }
        }
        ci.cancel();
    }
}
"""
worldgen_mixin_path.parent.mkdir(parents=True, exist_ok=True)
worldgen_mixin_path.write_text(worldgen_mixin_source, encoding="utf-8")
mixins = __import__("json").loads(worldgen_mixins_json.read_text(encoding="utf-8"))
mixin_name = "ChunkGeneratorStructureStateMixin"
if mixin_name not in mixins.get("mixins", []):
    mixins.setdefault("mixins", []).append(mixin_name)
worldgen_mixins_json.write_text(__import__("json").dumps(mixins, indent=2) + "\n", encoding="utf-8")
print("Applied per-state synchronization for Minecraft structure-position initialization.")


# Adaptive Android memory protection, focused on the Java Bridge rather than vanilla chunks.
# NativeGameActivity publishes a 0..=3 pressure level. The JVM receives bounded heap/CPU
# options at creation; Fabric's server-tick bridge applies gentle backpressure at high pressure.
threads_source_path = root / "crates/horizon-threads/src/lib.rs"
game_source_path = root / "crates/horizon-client/src/game.rs"
events_source_path = root / "crates/horizon-jvm/src/events.rs"
jvm_cargo_path = root / "crates/horizon-jvm/Cargo.toml"
for required_memory_source in (threads_source_path, game_source_path, events_source_path, jvm_cargo_path):
    if not required_memory_source.is_file():
        raise SystemExit(f"Missing Java Bridge memory-guard source: {required_memory_source}")

# Make the JVM memory-pressure signal available to the bridge without Android dependencies.
threads_source = threads_source_path.read_text(encoding="utf-8")
threads_import = "use std::sync::atomic::{AtomicBool, Ordering};"
if threads_source.count(threads_import) != 1:
    raise SystemExit(f"{threads_source_path}: atomic import anchor changed")
threads_source = threads_source.replace(
    threads_import,
    "use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};",
    1,
)
watcher_code = r"""

/// The Android launcher publishes 0..=3; higher levels mean less memory headroom.
static MOBILE_MEMORY_PRESSURE: AtomicU8 = AtomicU8::new(0);
static MOBILE_MEMORY_WATCHER_STARTED: AtomicBool = AtomicBool::new(false);

pub fn memory_pressure_level() -> u8 {
    MOBILE_MEMORY_PRESSURE.load(Ordering::Relaxed).min(3)
}

/// Polls the launcher's tiny state file without tying the game to Android APIs.
pub fn watch_memory_pressure_file(path: std::path::PathBuf) {
    if MOBILE_MEMORY_WATCHER_STARTED.swap(true, Ordering::SeqCst) {
        return;
    }
    let result = std::thread::Builder::new()
        .name("Horizon memory pressure".into())
        .spawn(move || loop {
            let level = std::fs::read_to_string(&path)
                .ok()
                .and_then(|value| value.trim().parse::<u8>().ok())
                .unwrap_or(0)
                .min(3);
            MOBILE_MEMORY_PRESSURE.store(level, Ordering::Relaxed);
            std::thread::sleep(std::time::Duration::from_millis(750));
        });
    if result.is_err() {
        MOBILE_MEMORY_WATCHER_STARTED.store(false, Ordering::SeqCst);
    }
}
"""
if "static MOBILE_MEMORY_PRESSURE: AtomicU8" in threads_source:
    raise SystemExit(f"{threads_source_path}: duplicate memory watcher")
threads_source = threads_source.rstrip() + "\n" + watcher_code
threads_source_path.write_text(threads_source, encoding="utf-8")

# Start the file watcher early, before any Java-mod startup or Fabric callbacks.
game_source = game_source_path.read_text(encoding="utf-8")
run_anchor = "pub fn run() -> Result<()> {\n    let Some(mut options) = Options::read()? else {"
run_replacement = """pub fn run() -> Result<()> {
    if let Some(path) = std::env::var_os("HORIZON_MEMORY_PRESSURE_FILE") {
        horizon_threads::watch_memory_pressure_file(std::path::PathBuf::from(path));
    }
    let Some(mut options) = Options::read()? else {"""
if game_source.count(run_anchor) != 1:
    raise SystemExit(f"{game_source_path}: run() memory watcher anchor changed")
game_source = game_source.replace(run_anchor, run_replacement, 1)
game_source_path.write_text(game_source, encoding="utf-8")

# Let horizon-jvm read the same lightweight pressure level as the native engine.
jvm_cargo = jvm_cargo_path.read_text(encoding="utf-8")
dependency_anchor = 'horizon-port-pack = { path = "../horizon-port-pack" }'
dependency_new = dependency_anchor + '\nhorizon-threads = { path = "../horizon-threads" }'
if jvm_cargo.count(dependency_anchor) != 1:
    raise SystemExit(f"{jvm_cargo_path}: dependency anchor changed")
if 'horizon-threads = { path = "../horizon-threads" }' not in jvm_cargo:
    jvm_cargo = jvm_cargo.replace(dependency_anchor, dependency_new, 1)
    jvm_cargo_path.write_text(jvm_cargo, encoding="utf-8")

# Keep the actual tick payload batched (existing parallel JNI arrays), but slow only
# Fabric event dispatch when memory pressure is high. Vanilla rendering/input/chunk radius
# are deliberately untouched.
events_source = events_source_path.read_text(encoding="utf-8")
events_import = "use std::sync::{Mutex, OnceLock};"
events_import_new = """use std::{
    fs::OpenOptions,
    io::Write,
    sync::{
        atomic::{AtomicU64, Ordering},
        Mutex, OnceLock,
    },
    time::{SystemTime, UNIX_EPOCH},
};"""
if events_source.count(events_import) != 1:
    raise SystemExit(f"{events_source_path}: import anchor changed")
events_source = events_source.replace(events_import, events_import_new, 1)

diagnostics_code = r"""

static LAST_JVM_MEMORY_LOG_MS: AtomicU64 = AtomicU64::new(0);

fn persist_jvm_memory_diagnostic(message: &str) {
    log::info!("{message}");
    let Some(path) = std::env::var_os("HORIZON_JAVA_BRIDGE_LOG_FILE") else {
        return;
    };
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default();
    if let Ok(mut output) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(output, "[{timestamp}][JVM-Memory] {message}");
    }
}

/// Samples the embedded HotSpot heap from its own JNI environment, not Android's ART heap.
fn log_jvm_memory_if_due(
    env: &mut JNIEnv,
    tick: i64,
    event_count: usize,
    pressure: u8,
) {
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default();
    let previous = LAST_JVM_MEMORY_LOG_MS.load(Ordering::Relaxed);
    if now_ms.saturating_sub(previous) < 30_000
        || LAST_JVM_MEMORY_LOG_MS
            .compare_exchange(previous, now_ms, Ordering::Relaxed, Ordering::Relaxed)
            .is_err()
    {
        return;
    }

    let stats = (|| -> jni::errors::Result<(i64, i64, i64, i32)> {
        let runtime = env
            .call_static_method(
                "java/lang/Runtime",
                "getRuntime",
                "()Ljava/lang/Runtime;",
                &[],
            )?
            .l()?;
        let total = env.call_method(&runtime, "totalMemory", "()J", &[])?.j()?;
        let free = env.call_method(&runtime, "freeMemory", "()J", &[])?.j()?;
        let max = env.call_method(&runtime, "maxMemory", "()J", &[])?.j()?;
        let threads = env
            .call_static_method("java/lang/Thread", "activeCount", "()I", &[])?
            .i()?;
        Ok((total, free, max, threads))
    })();

    match stats {
        Ok((total, free, max, threads)) => {
            let used = total.saturating_sub(free).max(0);
            persist_jvm_memory_diagnostic(&format!(
                "tick={tick}, pressure={pressure}, heapUsedMiB={}, heapCommittedMiB={}, heapMaxMiB={}, activeJavaThreads={}, fabricEvents={event_count}",
                used / (1024 * 1024),
                total / (1024 * 1024),
                max / (1024 * 1024),
                threads
            ));
        }
        Err(error) => {
            let _ = env.exception_clear();
            persist_jvm_memory_diagnostic(&format!(
                "JVM heap sampling failed: {error}"
            ));
        }
    }
}
"""
if "static LAST_JVM_MEMORY_LOG_MS" in events_source:
    raise SystemExit(f"{events_source_path}: duplicate JVM memory diagnostics")
events_source = events_source.replace(
    "/// A player on the server, as the game sees it this tick.",
    diagnostics_code + "\n/// A player on the server, as the game sees it this tick.",
    1
)

server_tick_anchor = """    let Some(class) = server_class() else {
        return Ok(());
    };
    let bridge = crate::get().ok_or("the JVM is not running")?;"""
server_tick_new = """    let Some(class) = server_class() else {
        return Ok(());
    };
    // Backpressure only the Java/Fabric server-event path. It does not sleep the
    // render/input thread and never drops or reorders gameplay events.
    let pressure = horizon_threads::memory_pressure_level();
    let delay_ms = match pressure {
        0 | 1 => 0,
        2 => 4,
        _ => 16,
    };
    if delay_ms > 0 {
        std::thread::sleep(std::time::Duration::from_millis(delay_ms));
    }
    let bridge = crate::get().ok_or("the JVM is not running")?;"""
if events_source.count(server_tick_anchor) != 1:
    raise SystemExit(f"{events_source_path}: server_tick backpressure anchor changed")
events_source = events_source.replace(server_tick_anchor, server_tick_new, 1)

local_frame_anchor = """    let result = env.with_local_frame(64, |env| -> jni::errors::Result<()> {
        call_tick(env, class, tick, dedicated, world, players, events)
    });"""
local_frame_new = """    let result = env.with_local_frame(64, |env| -> jni::errors::Result<()> {
        log_jvm_memory_if_due(env, tick, events.len(), pressure);
        call_tick(env, class, tick, dedicated, world, players, events)
    });"""
if events_source.count(local_frame_anchor) != 1:
    raise SystemExit(f"{events_source_path}: server_tick local-frame anchor changed")
events_source = events_source.replace(local_frame_anchor, local_frame_new, 1)
events_source_path.write_text(events_source, encoding="utf-8")

print("Applied Android JVM heap/CPU limits, Java Bridge tick backpressure and embedded-JVM memory diagnostics; vanilla chunk distance/workers unchanged.")
print("Applied fail-closed Java Bridge initialization, persistent startup logs, platform-JAR validation and adaptive Java Bridge memory protection.")
