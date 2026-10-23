# Horizon Fabric Bridge — Phase 1

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

## Automatic resource/data extraction from Fabric JARs

The Resource Manager and `tools/fabric_jar_converter.py` can convert the portable portion of a Fabric JAR into ordinary Minecraft 26.3 packs without executing the JAR:

- `assets/**` becomes an inactive resource-pack ZIP in the managed resource-pack folder. The user can review and activate it from the Resource Manager.
- `data/**` becomes a separate data-pack ZIP. The manager can install it into the `datapacks/` folder of a selected save; data packs are world-specific.
- Generated metadata targets resource-pack format `97.1` and data-pack format `121.0` for Minecraft Java 26.3.
- Imports are size-limited and resource paths are checked before writing. The original JAR remains available in the Bridge inbox.

This is **automatic content extraction**, not automatic execution or semantic conversion of arbitrary Java code. JARs with classes, entrypoints, Mixins, or access wideners are reported as partial conversions: the extracted packs can still reference registries, items, blocks, or entities that only the Java code would register. Such code must still be ported to Horizon's native mod API; the tool never pretends to generate a functioning `.hmod` from bytecode it cannot translate.

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
