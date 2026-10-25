# Horizon source overlay

These files are ordered patch steps, not reusable gameplay mods. They patch Horizon Rust sources before the Android ARM64 runtime is compiled.

## Apply order

1. `01_multiplayer_base.py` — baseline multiplayer support and diagnostics.
2. `02_android_native.py` — Android NativeActivity, touch-to-mouse input, keyboard/text entry, clipboard and native build settings.
3. `03_server_resource_packs.py` — keep Horizon's native server-pack manager (introduced upstream in v0.2.48+) and preserve MobileHorizon-specific offline/hybrid login, NPC profile/name fixes, and offer/download/reload/status diagnostics. **Do not replace upstream `crates/horizon-client/src/server_packs.rs` with the former custom implementation.**
4. `04_remote_entities_and_text.py` — remote entity state, text displays and name rendering.
5. `05_resource_rendering_and_frames.py` — custom item models, item frames and resource reload/render hooks.
6. `06_resource_diagnostics.py` — bounded logging for received packets, server-pack application and rendered output. Configuration traces must follow the pinned upstream packet decoder.
7. `07_protocol_maps_and_signs.py` — 26.3 packet/component compatibility, map images and sign text components.

The upstream runtime and preparation revisions are centralized in `tools/horizon-revisions.env`; the workflow reads that file for both source checkouts. The order is explicit in `tools/patch_horizon_android_touch.py`. These steps share an execution namespace because later edits depend on helpers or paths created by earlier steps. Keep each change in the appropriate step and preserve fail-fast exact-anchor checks.

## Updating Horizon

Inspect release notes and the upstream source diff before changing the pin. Adapt the Android and compatibility overlay to the newer source layout, then perform a static anchor/preflight check. **Do not dispatch the APK build until the project owner authorizes it.** Keep upgrade work on a separate branch rather than pushing directly to `main`, because the latter triggers the Android workflow. A successful static preflight is not a substitute for compiling, installing, or testing on a device.
