# Horizon source overlay

These files are ordered patch steps, not reusable gameplay mods. They patch Horizon Rust sources before the Android ARM64 runtime is compiled.

## Default Android runtime patch set

Three patch steps are active in the default Android engine:

1. `01_offline_server_login.py` — preserves offline-nickname joining on servers configured for offline mode and lets hybrid servers decide whether a missing Microsoft session is acceptable. Servers that truly require official online authentication still require a valid Microsoft session.
2. `02_android_native.py` — Android NativeActivity integration, touch/keyboard/clipboard support, audio fallback, GUI scale and native build configuration. It also copies the Android `cdylib` entrypoint into the pinned Horizon source tree for compilation.
3. `08_local_skin.py` — loads the skin selected in MobileHorizon into this client's local-player model using Horizon's existing skin decoder/atlas. It does not upload skins to Microsoft or impersonate a skin for other players.

The server compatibility experiments remain available in source, but are **not applied to the default runtime** while this work is paused:

- `01_multiplayer_base.py` — server UI/profile diagnostics and custom item-component traces.
- `03_server_resource_packs.py` — server resource-pack diagnostics and NPC/profile behavior beyond the offline-login exception above.
- `04_remote_entities_and_text.py` — remote display entities and hologram text.
- `05_resource_rendering_and_frames.py` — custom item models and item frames.
- `06_resource_diagnostics.py` — extra server packet/resource logging.
- `07_protocol_maps_and_signs.py` — server map/sign/component rendering compatibility.

These paused files are not deleted, so they can be reactivated selectively after the basic launcher and runtime updater are stable. Editing a paused file does not change the default engine's overlay hash or require a new runtime build.

## Updating Horizon

Inspect release notes and the upstream source diff before changing the pin. Adapt the Android and compatibility overlay to the newer source layout, then perform a static anchor/preflight check. **Do not dispatch the APK build until the project owner authorizes it.** Keep upgrade work on a separate branch rather than pushing directly to `main`, because the latter triggers the Android workflow. A successful static preflight is not a substitute for compiling, installing, or testing on a device.
