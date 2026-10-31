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

print("Applied fail-closed Java Bridge initialization, persistent startup logs, and platform-JAR validation.")


print("Applied fail-closed Java Bridge initialization, persistent startup logs, and platform-JAR validation.")
