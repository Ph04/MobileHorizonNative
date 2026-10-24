# Horizon Fabric Bridge — Phase 1

> Matriz detalhada dos módulos Fabric API 26.3, estado atual do Horizon e plano de adaptação: [fabric-api-compatibility-map.md](fabric-api-compatibility-map.md).

Status: implementation groundwork; not a Fabric runtime or automatic bytecode converter yet.

## Goals of this phase

1. Keep Fabric compatibility off by default.
2. Use an independent mod directory and enable-list only when the user selects the experimental Fabric Bridge profile.
3. Make the selected profile explicit at every Android NativeActivity launch.
4. Add a static JAR triage report to guide which native Horizon APIs should be implemented next.
5. Run focused analyzer tests in CI before the full Android build proceeds.
6. Use the official Fabric API 26.3 class catalog and inspect declared nested JARs only within explicit resource limits.
7. Show the audit summary in the Android import flow and save a JSON report; never describe resource carry-over as Java behavior conversion.

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

The Android Resource Manager now audits public Fabric API 26.3 class references against `app/src/main/assets/fabric-api-26.3-catalog.json`, reports detected modules and Java class-file versions, and inspects only nested JARs declared in `fabric.mod.json` with bounded size/entry budgets. It saves an audit JSON report in the app-private `filesDir/horizon/fabric-bridge-audits/` directory. The CLI `tools/fabric_bridge_audit.py` adds detailed static module evidence and a Markdown report. These are triage reports, not proof of compatibility.

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

**Updated runtime boundary:** the overlay now loads the separate P1 WASM component world, checks imported interfaces against manifest capabilities, calls the guest init export, and dispatches tick, received-chat, typed-command and HUD/config operations. It also exposes the current player and clock through the opt-in `game-read` import, and a read-only inventory snapshot (slots, selected stack, offhand) through `inventory`. The input adapter now dispatches non-repeating keyboard press/release events after Horizon's rebinding layer using stable USB HID usage IDs. World join/leave, outgoing-chat cancellation, mouse and named-touch callback dispatch still need their game hooks; registration in WIT alone does not make those callbacks active. The callback wall-time meter is accounting, not preemption; Wasmtime fuel remains the actual guard against an infinite loop.

## v0.4 coverage audit — contracts versus working runtime

The attached v0.4 bridge source is useful as a typed API design, but the presence of a Rust module or WIT declaration is **not** proof that the game invokes it. The distinction below is intentional so we do not advertise unfinished APIs as working:

| Area | Current truthful status | Missing host work |
|---|---|---|
| Biome modifiers / world generation | Declarative contract only | Worldgen hook and a server-authoritative generator that consumes modifiers; a client-only renderer cannot generate server chunks |
| Recipes / crafting | Recipe declarations and lookup contract | Wire recipe matching into the crafting-result path and keep server validation authoritative |
| Loot | Table/roll contract | Call it from the authoritative loot/drop path, not only from a client-side helper |
| Attachments | Typed in-memory data model | Stable serialization, save/load lifecycle, ownership and client/server synchronization |
| Particles / sounds | Declarative effect values | Dispatch to particle and sound engines, validate resource IDs and enforce per-tick limits |
| HUD | P1 text/bar/item-label widgets are adapted to Horizon's existing text-panel renderer | True item icon rendering, layout offsets, input focus and interactive UI still need hooks |
| Networking | Limited channel/message contracts | Register channels against real packet dispatch, validate direction/size/rate, and enforce permissions on both sides |
| Transfers | Item-storage move contract | Transaction/rollback integration with inventories; fluid storage and complex transactions remain unsupported |
| Dimensions | Limited dimension metadata/registration | World/session integration and a server-side custom-generator API |
| Mixins / access wideners | Not converted | Manual source adaptation; they are Java bytecode transformation features |
| Low-level renderer / Indigo / Java model callbacks | Not converted | Manual renderer/model port to Horizon's Rust rendering interfaces |
| Direct Minecraft internals | Not converted | Replace internal-class assumptions with explicit Horizon snapshots, events and capabilities |

### Required integration order

1. **End-to-end client fixture:** create and load a tiny P1 .hmod that registers init, tick, received-chat, keybinding and command callbacks; verify callback order and visible output in a real game session.
2. **Input/lifecycle completion:** wire world join/leave, outgoing-chat cancellation, mouse and named-touch callbacks to actual game entry points; registration alone is not enough.
3. **Visible client features:** verify config persistence and HUD updates in-game, then add real item-icon rendering/layout and structured UI interactions.
4. **Content/gameplay hooks:** connect recipes and loot only where Horizon owns authoritative execution. Biome modifiers, custom dimensions, gameplay mutations, networking authority and synced attachments need server-side support; do not simulate these client-side.

The Rust kernels and WIT worlds remain experimental groundwork until the corresponding host hook and a real .hmod integration test pass. In particular, the v0.4 API map does not claim automatic conversion of JAR bytecode, Mixins, Access Wideners, Java model callbacks or arbitrary Minecraft internals.

## Native equivalents for Fabric entrypoints, Mixins and Access Wideners

These features need native equivalents, not a pretend Java bytecode loader. Horizon runs its game logic in Rust and loads Horizon mods as WebAssembly components. A Fabric JAR's Java classes therefore cannot be invoked just by reading fabric.mod.json.

**Entrypoints.** A converted native mod's init export is the Horizon-side lifecycle entrypoint. A port must move ModInitializer or ClientModInitializer setup logic into that export and register P1 callbacks. Original Java entrypoint class names remain triage metadata; the converter never calls those classes.

**Mixin hooks.** Common, well-understood patterns can be manually mapped to explicit native hook IDs (client tick, keyboard, chat, world lifecycle, rendering/UI) with defined order and cancellation rules. An @Inject may be representable by an event if its target and semantics are supported. @Redirect, @Overwrite, arbitrary @Modify* operations, and injections into private implementation details cannot be generically translated; they need a native Horizon API/patch or an explicit source port. The audit tool identifies configurations and references; it does not apply injections.

**Access Wideners.** Inventory accessible, extendable and mutable declarations, then map each to an existing snapshot/getter/setter or a new bounded capability where semantics are clear. Do not expose Rust internals just because a Java member was widened. Unsupported declarations must be reported as requiring a manual port.

The practical goal is a Fabric-to-Horizon adapter profile: inspect each JAR, produce an actionable entrypoint/Mixin/access-widener inventory, and implement common behavior through stable native hooks. This improves support for mods whose behavior can be ported; it is not binary compatibility with arbitrary Java Fabric mods. Server-only gameplay changes also require server-authoritative hooks.