# Horizon WIT modules on Android

This folder contains two derived WIT modules. Neither replaces, edits or
converts the original source-based `.hnmod` packages.

## Player HUD probe

`wit-player-hud/` reads the local player's position and dimension via
`game-read` and asks Horizon to draw one HUD line via `hud`. It is a
small read-only proof of the component-to-host path.

## Mod Menu port seed

`mod-menu/` is the first derived WIT port slice of the original
`mod_menu-0.3.0.hnmod`. It uses Horizon's own settings-page list widget and
the experimental `mod-manager` capability to list and toggle installed
WIT/content modules. It deliberately does not claim full parity yet: text
search, sorting, artwork/badges, legacy native-mixin inventory and all
desktop-specific operations still need additional widgets or host APIs.

The manager capability is explicit in the module manifest:
`capabilities = ["ui", "mod-manager"]` and
`experimental = ["ui", "mod-manager"]`. Horizon checks imported interfaces
against these declarations. The host refuses to toggle development modules
and applies a requested change only after the current hook returns.

## Build workflow

The workflow checks out the exact pinned Horizon revision, applies the Android
WIT overlay (including the mod-manager API), builds the patched
`cargo-horizon-mod` packager, then builds and validates both components.
It uploads a single artifact named `mobilehorizon-wit-hnmod-modules`
containing:

- `horizon_api_probe.hnmod`
- `mobilehorizon_mod_menu.hnmod`

Each output is produced by the official Horizon component packager as
`.hmod`, checked, then copied with the MobileHorizonNative dynamic
`.hnmod` suffix. The internal ZIP format remains
`horizon-mod.toml` + `mod.wasm`.

This workflow builds component packages only. It does not compile or publish
an Android engine, APK or runtime release.

## Testing on Android

The game runtime must be built from the patched Horizon source with WIT
enabled. Import the two dynamic `.hnmod` files through the Resource Manager,
enable them in an instance and restart the game. The HUD probe displays
`WIT API OK` in a world. The Mod Menu seed appears as a settings page in
Options > Mods; selecting a row requests the host to toggle that WIT/content
module, after which Horizon reloads the module set.

The original `crossversion-0.6.0.hnmod` and
`mod_menu-0.3.0.hnmod` source archives remain unchanged. These are
separate derived ports. CrossVersion still needs a host-managed Java/ViaProxy
service because the WIT sandbox intentionally has no unrestricted process,
filesystem or network access.
