# Horizon source overlay

These files are ordered patch steps, not reusable .hmod gameplay mods. They patch Horizon Rust sources before the Android ARM64 runtime is compiled.

## Apply order

1. 01_multiplayer_base.py — profile textures, networking diagnostics and core multiplayer compatibility.
2. 02_android_native.py — Android NativeActivity, touch-to-mouse input, keyboard/text entry, clipboard and native build settings.
3. 03_server_resource_packs.py — download/apply server packs, protocol-phase handling and pack lifecycle.
4. 04_remote_entities_and_text.py — remote entity state, text displays and name rendering.
5. 05_resource_rendering_and_frames.py — custom item models, item frames and resource reload/render hooks.
6. 06_resource_diagnostics.py — bounded logging for received packets, pack application and rendered output.
7. 07_protocol_maps_and_signs.py — 26.3 packet/component compatibility, map images and sign text components.
8. 08_fabric_bridge_profiles.py — opt-in separate mod directory and enable-list; does not change the handshake or packet flow.

The upstream runtime and preparation revisions are centralized in tools/horizon-revisions.env; the workflow reads that file for both source checkouts. The order is explicit in tools/patch_horizon_android_touch.py. These steps share an execution namespace because later edits depend on helpers or paths created by earlier steps. Keep each change in the appropriate step and preserve fail-fast exact-anchor checks. Do not change the pinned Horizon revision in the production workflow as part of this refactor.

The Fabric Bridge profile is an experimental opt-in. Without HORIZON_MOD_PROFILE=fabric-bridge, Horizon keeps using horizon-mods/ and horizon-mods.txt. When enabled, the selected profile uses horizon-fabric-bridge-mods/ and horizon-fabric-bridge-mods.txt, beside the shared options.txt. The profile currently separates native Horizon packages intended for the Bridge path; Fabric JAR conversion remains a separate tool and is not runtime execution.

## Updating Horizon

When adopting a newer upstream commit, inspect the release/commit diff first. Then run the Android workflow against the new revision on a separate branch. Each failed anchor indicates a patch that needs to be adapted to upstream source; do not make the build pass by deleting a patch without replacing its functionality or documenting why it is no longer needed.
