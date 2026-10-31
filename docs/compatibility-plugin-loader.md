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

Before NativeGameActivity calls super.onCreate, it resolves and validates the selected profile, then exports:

- HORIZON_INSTANCE_ID
- HORIZON_GAME_VERSION
- HORIZON_LOADER_ID
- HORIZON_LOADER_VERSION
- HORIZON_INSTANCE_PROFILE — absolute path to .mobilehorizon/launch-profile.json
- HORIZON_MODS_DIR
- HORIZON_COMPATIBILITY_MODULE_DIR
- HORIZON_COMPATIBILITY_ENTRYPOINT_JAR
- HORIZON_COMPATIBILITY_ENTRYPOINT_CLASS
- HORIZON_COMPATIBILITY_API_VERSION

The JSON launch profile contains the same identifiers and absolute paths. The Rust-side bridge must validate these values again; it must not trust environment values or paths blindly. It should load the Java bootstrap only for the selected instance and only after the user has selected that loader profile.

## Important implementation boundary

This branch adds the launcher-side module package manager, per-instance loader profiles, module selection UI and native launch handoff metadata. It does not claim that Forge or Fabric can already start inside Horizon. The current Rust runtime does not yet consume this contract. A subsequent engine-integration stage must implement the Rust/Java bridge, loader bootstrap, lifecycle/events, registries, resources, rendering, networking and shutdown handling. Until then, installing a package only makes it available to the instance manager; it does not make unsupported mods playable.

The normal Vanilla profile continues to launch without requiring any compatibility module. No APK is built for individual compatibility modules.
