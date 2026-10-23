# Horizon Fabric Bridge P1 native adapter

P1 is the experimental Horizon-side runtime for ported WebAssembly components, not a Java/Fabric Loader implementation. The overlay installs a separate horizon:fabric-bridge@0.1.0 world, validates imported interfaces against manifest capabilities, starts a component and calls its init export.

## Active in the current runtime

- Init as the native lifecycle entrypoint for a ported mod.
- Client tick and received-chat callbacks.
- Typed command registration, validation and dispatch.
- Declarative text/bar/item-label HUD adapted to Horizon's text-panel renderer.
- Typed per-mod config with persisted values.
- Keyboard keybindings: press/release callbacks after Horizon's rebinding layer. Numeric codes are USB HID Keyboard/Keypad Usage IDs, not Rust enum values or Android keycodes.
- Wasmtime fuel and store limits; per-mod quotas and validation.

## Not active yet

The WIT contract declares world join/leave and outgoing-chat callbacks, but those callbacks are not yet dispatched by the game. Mouse and named-touch trigger registration exist, but their runtime input paths still need hooks. Event cancellation is not yet wired to suppress original game behavior. The v0.4 biome, crafting/recipe, loot, attachment, effect, dimension, networking and transfer contracts are not all connected to the corresponding authoritative Horizon game systems.

## Fabric JAR compatibility boundary

The Resource Manager may inventory a Fabric JAR and carry supported assets/ and data/ into a content-only .hmod. It never executes or transforms the JAR's Java classes. Fabric main/client entrypoint class names are metadata only; their behavior must be ported to a native init export and P1 callbacks. Mixin configs and Access Widener directives can guide that port but cannot inject into Horizon's Rust implementation automatically. Known patterns should be adapted to stable native hook IDs; unsupported bytecode-level patterns must be reported as unsupported rather than silently ignored.

Run standalone kernel tests with:

    cargo test --manifest-path tools/fabric_bridge_p1/Cargo.toml

The Android CI applies the ordered overlay to the pinned Horizon source, compiles the host runtime and verifies the APK. A passing compile is not proof that an arbitrary Fabric JAR is compatible.
