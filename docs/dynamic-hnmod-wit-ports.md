# Dynamic `.hnmod` modules and WIT ports

## Two archive layouts, one extension

The Android importer distinguishes packages by their **internal manifest**, not
only the filename:

| Package contents | App destination | Loading model |
|---|---|---|
| `horizon-native.json` + `mixins/<id>/...` | `horizon-native-mods/` | Legacy Rust mixin source; compiled into a matching Horizon engine |
| `horizon-mod.toml` + `mod.wasm` | `horizon-mods/` | Dynamic WebAssembly component; loaded by the Horizon runtime already in the app |

The official desktop container remains `.hmod`. MobileHorizonNative permits
a dynamic WIT component to use the `.hnmod` suffix so it can be distributed
through the existing native-mod import surface. Only the suffix changes; the
internal WIT package is still the official `horizon-mod.toml` and `mod.wasm`
layout. A legacy source bundle is never converted by changing its extension.

## What the WIT host lets a module do

The WIT component model is the boundary between module and game. A module does
not receive raw Rust pointers or unrestricted access to the engine. It imports
versioned host interfaces and must declare the corresponding capability in its
manifest. Horizon checks actual component imports against the declared list.

The pinned Horizon 0.2.81 runtime already exposes game state, chat, HUD,
storage, commands, UI widgets, render shapes, input, and recipes. Android
enables that existing Wasmtime runtime only when the full upstream runtime
and SDK are present in the pinned source.

The MobileHorizonNative overlay adds an **experimental, capability-gated
`mod-manager` interface** (API level 8). It can list readable installed
WIT/content modules and request enable/disable changes. The host applies
changes after the mod's current hook returns, persists the selection and
reloads the module set. It refuses to toggle development modules. This is a
controlled service API, not arbitrary code execution.

## Porting the original packages

### `mod_menu`

The original UI and layout code stays intact. A derived WIT port can move its
filtering/list presentation to the WIT SDK's `ui` widgets and use
`mod-manager.list()` / `mod-manager.set-enabled()` instead of reaching into
Horizon internals. Its initial port can list, inspect and toggle modules; text
search, sorting and parity with every desktop-only action can be added through
the supported widgets and future API additions.

### `crossversion`

Its existing code includes ViaProxy download/management, Java runtime lookup,
process creation, TCP listeners, OAuth and session routing. Those operations
cannot be copied into a WIT sandbox: the component has no WASI filesystem,
network or process imports.

A safe port therefore needs a **host-managed CrossVersion service**. The WIT
module should request narrow operations (for example, prepare a selected
ViaProxy profile or route a server connection); the Android host validates
the selected profile and runs the pre-approved Java/ViaProxy runtime outside
the sandbox. Server connection hooks and lifecycle callbacks also need
first-class host events. The raw Java/process/socket implementation must not
be exposed to arbitrary WIT modules.

## Preservation and validation

The original `crossversion-0.6.0.hnmod` and `mod_menu-0.3.0.hnmod` remain
unchanged in the source-mod format. Their ports are separate derived packages.
A successful WIT package build proves the component can be packaged; a
successful Android engine build proves Wasmtime and the host compile for
ARM64. Neither alone proves device behavior: the module still needs to be
imported, enabled and exercised in a world on the target Android device.
