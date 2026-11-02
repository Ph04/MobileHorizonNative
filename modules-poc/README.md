# Horizon dynamic .hnmod WIT module proof of concept

This is a separate sample component. It does not edit, unpack, convert or replace
any existing `.hnmod` archive.

`wit-player-hud/` implements the pinned Horizon `horizon-mod-sdk` contract.
It requests only two capabilities:

- `game-read`: read the local player's position and dimension.
- `hud`: ask Horizon to draw one text line in its own HUD renderer.

The sample is deliberately small and read-only. It receives player data from the
Horizon host on the tick hook and sends a HUD request back through WIT. It does
not hold pointers into Horizon, load native libraries, access files or sockets,
or launch processes.

Build output is first generated and validated as the official Horizon `.hmod`
component, then packaged under the MobileHorizonNative dynamic `.hnmod` suffix.
The archive still contains `horizon-mod.toml` and `mod.wasm`; only its suffix
changes. The Android importer stores it in `horizon-mods/`, and the patched
Horizon package discovery loads it through the official WIT runtime. The POC
workflow produces this `.hnmod` artifact only; it does not build an APK or a
custom Horizon runtime.

This proves the host/module communication path and the new dynamic `.hnmod`
package route. It does **not** automatically convert existing mixin-based
`.hnmod` packages: their original `horizon-native.json` archives remain
untouched and still require their current native runtime build path. A port of
each original must be a separate WIT implementation with the required host APIs.

## How the POC is intended to be tested

1. Run the `Build Horizon WIT module proof of concept` workflow on this branch.
2. Download its `horizon-api-probe-hnmod` artifact and import the dynamic `.hnmod`
   with MobileHorizonNative's Resource Manager.
3. Enable the module for an instance and restart the game.
4. In a world, look for the `WIT API OK` line at the top-left of the HUD. It
   should show the player's position and dimension.

The Android runtime must itself have been built with the WIT feature enabled.
The source preflight in the standard/runtime-release pipelines verifies that
feature selection; it does not rebuild or replace an already published runtime.
No APK or Android engine has been rebuilt as part of this source-only POC.
