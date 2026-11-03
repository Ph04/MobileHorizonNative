# Horizon WIT modules on Android

This folder contains independent WebAssembly components using Horizon's official `.hmod` package format.

## Player HUD probe

`wit-player-hud/` reads the local player's position and dimension via `game-read` and asks Horizon to draw one HUD line via `hud`. It is a small read-only proof of the component-to-host path.

## Mod Manager sample

`mod-menu/` is an independent WIT sample using Horizon's settings-page widgets and the experimental `mod-manager` capability. It lists installed WIT/content modules and can request enable/disable changes for eligible modules. It is not a conversion of a native mod package and does not reproduce every PC launcher feature.

The manager capability is explicit in the manifest: `capabilities = ["ui", "mod-manager"]` and `experimental = ["ui", "mod-manager"]`. Horizon checks imported interfaces against these declarations. The host refuses to toggle development modules and applies a requested change only after the current hook returns.

## Build workflow

The workflow checks out the exact pinned Horizon revision, applies the Android WIT overlay (including the mod-manager API), builds the patched `cargo-horizon-mod` packager, then builds and validates both components. It uploads `mobilehorizon-wit-hmod-modules` containing:

- `horizon_api_probe.hmod`
- `mobilehorizon_mod_menu.hmod`

Each output is produced and checked by Horizon's official component packager using the standard `horizon-mod.toml` + `mod.wasm` format.

This workflow builds component packages only. It does not compile or publish an Android engine, APK or runtime release.

## Testing on Android

The game runtime must be built from the patched Horizon source with WIT enabled. Import the two official `.hmod` files through the Resource Manager, enable them in an instance and restart the game. The HUD probe displays `WIT API OK` in a world. The Mod Manager sample appears as a settings page in Options > Mods; selecting a row requests the host to toggle that WIT/content module, after which Horizon reloads the module set.

Legacy native source modules are outside this stage and are not modified or converted.
