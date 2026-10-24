# Fabric Bridge v0.6 integration status

This change adds the next native API layer under `tools/fabric_bridge_p1/src/api_v06.rs` and includes it in the ordered Horizon overlay through `tools/horizon_patches/10_fabric_bridge_api_p1.py`. The normal Vanilla profile remains the default; this code does not change the login/handshake or enable the bridge unless the existing opt-in profile is selected.

## Included native API models

- Item and fluid transfer limits, destination-capacity checks, transaction rollback, and nested savepoints.
- Permission nodes with default operator levels, explicit allow/deny, and revisioned snapshots.
- Deterministic registry negotiation which rejects incompatible required entries on either side.
- Typed attachment snapshots with stale-revision checks and bounds on arbitrary text/byte payloads.
- Resource-pack priority resolution with deterministic tie breaking and traversal-safe paths.
- Shaped and shapeless recipe matching, item tags, and a duplicate-safe RecipeBook.
- Seed-deterministic declarative loot pools with bounded conditions, counts, and output size.
- Biome modifiers which produce a data patch for a worldgen host hook, typed content registries/data maps, object-builder schema validation, and dimension metadata.
- Message modification/block pipeline which discards a signature when the text changes.
- Allowlisted network channel declarations, direction/payload checks, and per-player/per-window rate limiting.
- Backend-neutral mesh validation/draw budgets, declarative fluid-surface geometry, model hierarchy validation, screen/widget schemas, bounded particle/sound definitions, and debug line quotas.

## Validation

The GitHub Actions workflow runs `cargo test --manifest-path tools/fabric_bridge_p1/Cargo.toml`, then applies the overlay to the pinned Horizon commit and runs the Horizon Rust build. The unit tests cover global transfer limits/rollback, permission defaults, required registry mismatches, attachment snapshots, resource precedence/path confinement, shaped/shapeless recipes, deterministic loot, schema validation, dimensions, message signature invalidation, networking bounds/rate limits, screens, model chains, fluids, sounds, and debug/render budgets.

## Integration boundary

These are native Rust API/data contracts compiled into the Horizon mod crate. The WIT guest world still exposes the current P1 surface; this change does not by itself make every v0.6 operation callable from a WASM mod. The host must connect the appropriate contracts to authoritative gameplay/worldgen, recipe/loot execution, packet dispatch, disk persistence and client/server synchronization, model loading, GPU rendering, particle and audio engines. Java entrypoints, Mixins and arbitrary Java serializers still require a real native port; no imported Java bytecode is executed.

A successful unit test or Rust compile is not a statement of full Fabric compatibility. The remaining host/WIT integrations must be added and tested against actual converted mods before those APIs are advertised as functional in-game.
