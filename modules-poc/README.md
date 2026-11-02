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
