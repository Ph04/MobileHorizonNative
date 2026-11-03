# Horizon source overlay

These files are ordered patch steps, not reusable gameplay mods. They patch Horizon Rust sources before the Android ARM64 runtime is compiled.

## Default Android runtime patch set

Only two patch steps are active in the default Android engine:

1. `01_offline_server_login.py` — preserves offline-nickname joining on servers configured for offline mode and lets hybrid servers decide whether a missing Microsoft session is acceptable. Servers that truly require official online authentication still require a valid Microsoft session.
2. `02_android_native.py` — Android NativeActivity integration, touch/keyboard/clipboard support, audio fallback, GUI scale and native build configuration. It also copies the Android `cdylib` entrypoint into the pinned Horizon source tree for compilation. When the exact Horizon revision includes the official `horizon-mods/runtime` and WIT SDK, it additionally enables the existing sandboxed WebAssembly component runtime for `.hmod` code modules. Older Nightlies without that complete runtime keep the original minimal feature set.

The server compatibility experiments remain available in source, but are **not applied to the default runtime** while this work is paused:

- `01_multiplayer_base.py` — server UI/profile diagnostics and custom item-component traces.
- `03_server_resource_packs.py` — server resource-pack diagnostics and NPC/profile behavior beyond the offline-login exception above.
- `04_remote_entities_and_text.py` — remote display entities and hologram text.
- `05_resource_rendering_and_frames.py` — custom item models and item frames.
- `06_resource_diagnostics.py` — extra server packet/resource logging.
- `07_protocol_maps_and_signs.py` — server map/sign/component rendering compatibility.

These paused files are not deleted, so they can be reactivated selectively after the basic launcher and runtime updater are stable. Editing a paused file does not change the default engine's overlay hash or require a new runtime build.


## Sandboxed WIT modules versus native `.hnmod`

On revisions that contain Horizon's `crates/horizon-mods/src/runtime.rs` and
`crates/horizon-mod-sdk/wit/horizon.wit`, Android enables the upstream
`horizon-client/mods` feature. These `.hmod` components call Horizon through
versioned WIT host interfaces; the engine enforces declared capabilities, fuel
budgets and a 64 MiB memory cap per module. The Wasmtime engine is created lazily
when code mods are actually loaded.

This does **not** convert the existing Rust mixin `.hnmod` packages into
WebAssembly components. Those remain on the existing native build path, and older
Horizon pins that lack the WIT runtime do not enable the feature. Original mod
archives remain untouched.

## Updating Horizon

Inspect release notes and the upstream source diff before changing the pin. Adapt the Android and compatibility overlay to the newer source layout, then perform a static anchor/preflight check. **Do not dispatch the APK build until the project owner authorizes it.** Keep upgrade work on a separate branch rather than pushing directly to `main`, because the latter triggers the Android workflow. A successful static preflight is not a substitute for compiling, installing, or testing on a device.


## WIT module-manager capability (experimental API level 8)

The Android overlay extends Horizon's versioned WIT contract with an explicit
`mod-manager` capability. A component that declares
`capabilities = ["mod-manager"]` and
`experimental = ["mod-manager"]` may:

- list installed modules with readable manifests, enabled state, runtime status,
  development status and whether they have a settings page;
- request an enable/disable change for an installed non-development module.

The host applies queued changes only after the current WIT hook returns, saves
`horizon-mods.txt`, and reloads the Horizon module set. It does not expose raw
filesystem access, process spawning, sockets, native pointers or arbitrary code
execution. Modules must explicitly request the capability and the user sees it
in the manifest's permission summary.

This host API is the foundation for a WIT port of `mod_menu`. It covers modules
loaded through Horizon's WIT/content manager; it does not change how legacy
`horizon-native.json` mixin bundles are compiled.
