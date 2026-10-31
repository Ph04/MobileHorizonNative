# Compatibility Plugin Loader and isolated game instances

## Goal

Add compatibility modules without producing a new APK for every loader or plugin. The launcher owns module installation and instance metadata; the Horizon engine remains the game runtime. A compatibility module is installed once and can be referenced by more than one game instance.

This architecture is separate from the existing vanilla installation and preserves the legacy filesDir/horizon game home.

## Storage model

- The legacy default instance continues to use filesDir/horizon.
- New instances use filesDir/instances/<instance-id>/game.
- Each game home owns its worlds, options, logs, screenshots, resource packs, mods/, config/, and .mobilehorizon/profile.json.
- Shared verified Minecraft files stay under filesDir/.local/horizon-client/<minecraft-version>.
- The verified Horizon runtime stays in the runtime manager's shared version store.
- Compatibility packages are installed once under filesDir/compatibility-modules/<module-id>/<module-version>/.

A profile records the Minecraft version, loader ID/version, and exact compatibility module ID/version. Selecting or installing another module does not overwrite another instance. The Padrão profile remains Vanilla and is not offered for loader changes in the instance UI.

## Installing a compatibility module

The user imports a .mhplugin file through Android's document picker. The package is a ZIP archive with this structure:

~~~text
example-forge-bridge.mhplugin
├── mobilehorizon-plugin.json
└── payload/
    ├── bridge/
    │   └── forge-bootstrap.jar
    └── libraries/
        └── bridge-support.jar
~~~

Example descriptor:

~~~json
{
  "format": 1,
  "kind": "compatibility",
  "apiVersion": 1,
  "id": "org.example.horizon-forge-bridge",
  "name": "Horizon Forge Bridge",
  "version": "0.1.0",
  "loader": "forge",
  "loaderVersion": "specified-by-the-module",
  "minecraftVersions": ["26.3"],
  "entrypointJar": "bridge/forge-bootstrap.jar",
  "entrypointClass": "org.example.horizon.forge.ForgeBootstrap",
  "description": "Forge compatibility bootstrap"
}
~~~

entrypointJar is relative to the package's payload/ directory. Loader IDs currently recognized by the package contract are fabric, forge, neoforge, and quilt. vanilla is a built-in profile and does not require a module.

The installer:
- requires the descriptor and API version to be recognized;
- validates IDs, versions, game-version declarations, entrypoint class and paths;
- rejects absolute paths, traversal, duplicate ZIP paths and files outside payload/;
- enforces entry count and extraction-size limits;
- installs into a staging directory and only then renames it into the module store;
- refuses to remove a module that is still referenced by an instance.

A package imported from local storage is not cryptographically authenticated by this API. The UI therefore warns that third-party modules can contain executable code. The user should install packages only from sources they trust. A future online catalog should add signed manifests and digest verification before it distributes modules.

## Instance creation

The instance manager starts with Vanilla • Minecraft 26.3. Non-vanilla loader choices only appear when an installed module declares support for the current Minecraft version.

Creating a Forge or Fabric instance creates a new game home, then binds that instance to the selected module ID and version. Its mods/, config/, saves and options are independent from Vanilla and from other loader instances. Shared official game resources and the Horizon engine are not duplicated.

A profile can be changed later from the instance card. The original default instance is deliberately kept as the untouched Vanilla profile.

## Native runtime handoff contract

Before NativeGameActivity calls super.onCreate, it resolves and validates the selected profile, then exports the per-instance identity and module entrypoint. For a selected compatibility module it also stages the shared Java API from the APK and resolves the Java 25 runtime that the launcher has already installed. A loader profile does not trigger a JDK download during game startup.

The handoff includes:

- HORIZON_INSTANCE_ID and HORIZON_GAME_VERSION
- HORIZON_LOADER_ID and HORIZON_LOADER_VERSION
- HORIZON_INSTANCE_PROFILE and HORIZON_MODS_DIR
- HORIZON_COMPATIBILITY_MODULE_DIR, MODULE_ID and MODULE_VERSION
- HORIZON_COMPATIBILITY_ENTRYPOINT_JAR and ENTRYPOINT_CLASS
- HORIZON_COMPATIBILITY_API_VERSION
- HORIZON_APP_FILES_DIR
- HORIZON_JAVA_EXECUTABLE, JAVA_HOME, JAVA_LIBRARY_PATH and JAVA_TMP
- HORIZON_COMPATIBILITY_API_JAR

If profile validation or Java/API preparation fails, the Android bootstrap receives a startup-blocked status and refuses to silently launch the instance as Vanilla.

## Rust–Java bridge

The ordered Horizon overlay now applies `08_compatibility_bridge.py` after the existing Android and offline-login patches. It adds `horizon_client::compatibility_bridge` to the native engine and connects it to the Android client entrypoint.

For a Vanilla instance, the bridge returns immediately: it does not create a socket or start another process. For a selected compatibility module, the bridge:

1. validates the module descriptor, module/version path, entrypoint JAR, Java 25 paths and API version again on the Rust side;
2. opens a private Unix-domain socket in app-private storage, with restricted file permissions;
3. starts the existing Java 25 runtime through Android's system linker;
4. checks the module and instance identity in a versioned handshake before allowing the plugin entrypoint to run;
5. forwards bounded frame, game tick, world lifecycle, server tick and key events;
6. receives bounded module logs/actions and closes the Java process during normal shutdown.

The Java API lives in the standalone `:compatibility-api` Gradle module. The app packages only that shared API JAR as an asset; individual compatibility modules remain separate `.mhplugin` files and do not require their own APK.

The current bridge action allowlist is intentionally conservative. The `log` action is handled; other action types are reported as unsupported rather than changing game state from an ambiguous client/server thread. The protocol is an extension point, not a claim that Forge's full event bus or Minecraft Java API has been mapped.

## First Forge adapter

The independent `:forge-adapter` module produces `forge-compatibility-26.3-0.1.0.mhplugin`, pinned to Minecraft 26.3 and Forge 66.1.2. Version 0.1.0 is explicitly a diagnostic probe. It checks which ModLauncher/FML/Minecraft client classes are visible to its Java class loader and receives Horizon events. It does not invoke ModLauncher and does not run ordinary Forge mods.

The validation target is recorded in `tools/compatibility-horizon-revision.env`: Horizon Nightly 2026-10-30, commit `587f81f9c69629e999584f30aae0c9edacfc1b1d`. This compatibility validation pin is separate from the app's production Horizon runtime pin.

## Important implementation boundary

This branch now contains the common Java API, a separate Java bridge process, the Rust event transport, module/instance handoff and the first Forge diagnostic package. It is an operational plugin bridge for modules written against the MobileHorizon compatibility API, not a general Forge runtime.

Horizon is a Rust implementation and does not provide the full Java Minecraft class universe expected by Forge. Arbitrary Forge JARs therefore remain unsupported. The next substantial work is to map the classes and behaviors expected by real Forge mods—registries, resources, lifecycle/events, entities, blocks, rendering, networking and game-thread actions—to Horizon's native APIs, then verify that mapping with progressively more demanding test mods.

The normal Vanilla profile continues to launch without requiring a compatibility module. No APK is generated for an individual module.

## Important implementation boundary

This branch adds the launcher-side module package manager, per-instance loader profiles, module selection UI and native launch handoff metadata. It does not claim that Forge or Fabric can already start inside Horizon. The current Rust runtime does not yet consume this contract. A subsequent engine-integration stage must implement the Rust/Java bridge, loader bootstrap, lifecycle/events, registries, resources, rendering, networking and shutdown handling. Until then, installing a package only makes it available to the instance manager; it does not make unsupported mods playable.

The normal Vanilla profile continues to launch without requiring any compatibility module. No APK is built for individual compatibility modules.
