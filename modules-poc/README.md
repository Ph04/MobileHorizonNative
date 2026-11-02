# Horizon WIT module proof of concept

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

Build output is a normal `.hmod` containing `mod.wasm`. Android enables the
official WIT runtime only for Horizon revisions that contain the complete
`horizon-mods/runtime` and SDK. The POC build workflow produces this module
artifact only; it does not build an APK or a custom Horizon runtime.

This proves the host/module communication path. It does **not** automatically
convert the existing mixin-based `.hnmod` packages. Those originals remain
untouched; a later port would be a separate derived component.

## How the POC is intended to be tested

1. Run the `Build Horizon WIT module proof of concept` workflow on this branch.
2. Download its `horizon-api-probe-hmod` artifact and import the resulting `.hmod`
   with MobileHorizonNative's Resource Manager.
3. Enable the module for an instance and restart the game.
4. In a world, look for the `WIT API OK` line at the top-left of the HUD. It
   should show the player's position and dimension.

The Android runtime must itself have been built with the WIT feature enabled.
The source preflight in the standard/runtime-release pipelines verifies that
feature selection; it does not rebuild or replace an already published runtime.
No APK or Android engine has been rebuilt as part of this source-only POC.
