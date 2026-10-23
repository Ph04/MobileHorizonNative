# Horizon Fabric Bridge — Phase 1

> Matriz detalhada dos módulos Fabric API 26.3, estado atual do Horizon e plano de adaptação: [fabric-api-compatibility-map.md](fabric-api-compatibility-map.md).

Status: implementation groundwork; not a Fabric runtime or automatic bytecode converter yet.

## Goals of this phase

1. Keep Fabric compatibility off by default.
2. Use an independent mod directory and enable-list only when the user selects the experimental Fabric Bridge profile.
3. Make the selected profile explicit at every Android NativeActivity launch.
4. Add a static JAR triage report to guide which native Horizon APIs should be implemented next.
5. Run focused analyzer tests in CI before the full Android build proceeds.

## Profile isolation

The launcher stores the choice in Android private preferences under the key horizon_fabric_bridge/enabled. The default is false. Every game launch passes the profile to NativeGameActivity, which sets HORIZON_MOD_PROFILE before calling super.onCreate and before the native runtime is loaded.

With the default or any unrecognized profile value, Horizon retains its existing locations:

- Mods directory: horizon-mods/
- Enable-list: horizon-mods.txt

With the experimental profile explicitly selected:

- Mods directory: horizon-fabric-bridge-mods/
- Enable-list: horizon-fabric-bridge-mods.txt

Both profile directories are resolved beside the current options.txt location. Worlds, the ordinary Minecraft options file, resource roots and network protocol are not switched by this patch. The Mods screen's folder action follows the active profile.

The profile switch applies on the next game launch. It does not attempt to unload already initialized code during a running world. The normal Horizon mods list and the Bridge list are intentionally separate; users who choose the Bridge profile manage the mods for that profile in its separate folder.

### Isolation boundary and non-goals

This phase separates which native .hmod packages and enable-list Horizon reads. It does not yet implement Fabric API shims or convert Java code. The existing client-brand and network-handshake code are untouched, and no Fabric-specific payload or negotiation was introduced. A future Bridge adapter must use this same profile gate and must not register hooks, callbacks or networking adapters in the Vanilla profile.

This phase does not claim that every content .hmod is safe to use on a Vanilla server. The analyser flags evidence that a mod references registries, world simulation or networking, but a static reference scan is only a triage aid. Per-session server compatibility still requires explicit metadata and tested policy.

## Resource Manager integration (Phase 2 groundwork)

The Android Resource Manager uses the same `horizon_fabric_bridge/enabled` preference as the launcher screen. Its profile toggle selects which `.hmod` directory and enable-list are shown and edited:

- Vanilla: `horizon-mods/` and `horizon-mods.txt`.
- Fabric Bridge: `horizon-fabric-bridge-mods/` and `horizon-fabric-bridge-mods.txt`.

The standard import action accepts Horizon `.hmod` mods and recognized resource/shader ZIPs. A dedicated Fabric JAR action accepts a user-selected `.jar`, reads `fabric.mod.json` without extraction or execution, and stores it in `horizon-fabric-bridge-inbox/`. The manager displays basic triage metadata (mod ID/name/version, class and resource counts, plus mixin/access-widener markers), and permits deleting queued JARs. Duplicate Fabric mod IDs are rejected.

**Important limitation:** the inbox is an intake/triage queue, not a Fabric runtime. Imported JARs are not enabled or loaded by Horizon. Conversion from a Java mod into a compatible `.hmod`, Fabric API emulation, and missing native SDK capabilities remain future work. Selecting Fabric Bridge does not alter vanilla network handshakes or make unsupported mods compatible with servers.

## Automatic JAR carry-over

The Android Resource Manager now calls a bundled ARM64 entry point around Horizon's existing `horizon-port-pack` carry-over implementation. It reads the JAR, upgrades supported 1.21.1-era data to Minecraft 26.3 formats, carries assets/data, generates vanilla-template declarations for compatible blocks/items, and validates the result using Horizon's own `Package` and `Content` readers.

A successful carry-over is installed as a **content-only `.hmod`** in `horizon-fabric-bridge-mods/`. It may include textures, models, languages, recipes, loot tables, tags, item definitions, and declarations based on the data that the converter understands. The original Fabric JAR remains in `horizon-fabric-bridge-inbox/`; details from the carry report identify files or block/item declarations that require review.

The standalone `tools/fabric_jar_converter.py` remains a small utility for extracting portable `assets/` and `data/` into ordinary Minecraft 26.3 resource/data packs. The app uses the richer Horizon carry-over converter instead of relying only on that simple extraction.

**This is not full Fabric Java runtime compatibility.** Java classes, entrypoints, Mixin injections, access wideners, custom registries, and behavior implemented in code are not transformed into Rust/WASM by this step. Such JARs become partial content mods: their converted assets/data may work, but their code-driven behavior will not. The converter reports partial results and never executes the imported JAR.

## JAR audit tool

Run it with Python 3.10 or newer:

    python3 tools/fabric_bridge_audit.py path/to/mod.jar --output report.json --markdown report.md

A directory input is scanned recursively for JAR files:

    python3 tools/fabric_bridge_audit.py path/to/mods --output report.json

The JSON report records:

- SHA-256 and Fabric metadata (ID, version, license, environment, dependencies and entrypoints).
- Counts for assets and data files such as textures, models, recipes, tags, loot tables and worldgen.
- Class-file version, class counts and selected references from Java constant pools.
- Evidence of direct Minecraft classes, Fabric API classes, Fabric Loader classes, Mixin classes and feature-family markers.
- Mixin config and access-widener paths.
- Warnings and known limitations.

The tool only reads JAR contents; it never extracts or executes code. Class files are scanned with size limits. The tool does not decompile Java, resolve inherited calls, evaluate reflection, interpret Mixins or transform bytecode. Its “resource-only candidate” label is not a guarantee that all resources are valid for Horizon. A non-zero class count means the logic needs an actual port/adaptation assessment, not that its features were converted.

## Validation

The Android workflow runs:

    python3 -m unittest discover -s tools/tests -p 'test_*.py' -v

It then compiles the ordered Horizon overlay against the pinned runtime commit. The key acceptance checks for the profile patch are:

- No profile extra or missing preference resolves to Vanilla.
- The profile changes only the mod directory and enable-list.
- All code/content load paths and the Mods folder action use the active profile.
- Vanilla handshake, brand and payload paths remain unchanged.
- Tests and CI must pass before publishing an APK.

## Next API audit targets

Use reports from representative JARs, along with the pinned Horizon source, to prioritize:

1. Client keys, target inspection, inventory reads and structured UI.
2. Typed commands, lifecycle/world/block/item/entity events and recipe lookup.
3. World reads, rendering and data persistence.
4. Server callbacks, registries, block entities, menus, transfers and controlled networking.

Do not create a new API just because Fabric has a module with a similar name. First check whether Horizon already has the capability internally or can expose it through its existing Rust/WIT mod SDK. Features that change server state require a server-side implementation and explicit multiplayer compatibility rules.


## P1 native API kernel

The overlay now carries a compile-tested kernel for deterministic typed events (Early/Default/Late), cancelable chat events, tick/world lifecycle events, keybindings (keyboard/mouse/touch), a bounded typed command parser, declarative text/bar/item HUD widgets, per-mod schema-validated config, and callback-quota accounting. Unit tests run both against the standalone source under `tools/fabric_bridge_p1/` and the pinned Horizon `horizon-mods` crate after the overlay is applied.

The new `horizon:fabric-bridge@0.1.0` WIT world is kept in a separate WIT directory so existing `horizon:mods` 0.1 components remain unchanged. CI compiles the generated P1 bindings to validate the WIT contract.

**Integration boundary:** the kernel and WIT bindings are groundwork, not yet a loadable P1 `.hmod` API. The runtime still needs the P1 component loader/instance, capability manifest checks for the new imports, guest callback registration and dispatch, and game/UI input hooks. Until those pieces are connected and tested, do not mark any Fabric JAR code as working merely because this kernel compiles. The callback wall-time meter is accounting, not preemption; Wasmtime fuel remains the actual guard against an infinite loop.
