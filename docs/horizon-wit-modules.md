# Horizon WIT modules

The Android integration uses Horizon's official WebAssembly component runtime and standard `.hmod` package format. A component archive contains `horizon-mod.toml` and `mod.wasm`.

## Direct interaction with Horizon

A WIT module imports versioned host interfaces exposed by the running engine. The pinned Horizon runtime provides interfaces for game/player data, chat, HUD, storage, commands, UI, rendering, input and recipes. Each module declares the capabilities it needs, and Horizon verifies its imports against the manifest.

The Android overlay enables Horizon's existing Wasmtime component runtime only when the selected upstream checkout contains the complete runtime and SDK. Per-module fuel and memory limits remain active.

## Additional module-manager capability

MobileHorizonNative adds an experimental, explicitly requested `mod-manager` capability. It can list installed WIT/content modules and request enable/disable changes. The host applies requests after the current hook returns, persists the selection and reloads the Horizon module set. It does not expose arbitrary filesystem paths, process creation, sockets, native pointers or unrestricted code execution.

## Samples

`modules-poc/wit-player-hud/` is a read-only proof of the component-to-host path: it reads local player state and asks Horizon to draw a HUD panel.

`modules-poc/mod-menu/` is an independent WIT sample using the `ui` and `mod-manager` capabilities. It lists installed components and toggles eligible modules. It is not a conversion of any legacy native package.

## Scope

MobileHorizonNative does not import or compile legacy `.hnmod` source bundles. CrossVersion is integrated separately as a built-in engine feature and is not a general-purpose mod archive. The official `.hmod` package path remains reserved for Horizon's WIT components and content modules.
