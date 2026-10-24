use serde_json::json;

pub fn report() -> serde_json::Value {
    json!({
        "schema_version": 1,
        "target_minecraft": "26.3",
        "fabric_loader": "0.19.5",
        "fabric_api": "0.165.0+26.3",
        "bridge_protocol": "implemented",
        "readonly_snapshots": "implemented",
        "world_biome_backend": "native-backend-only",
        "native_biome_operations": [
            "fabric.biome.catalog",
            "fabric.biome.sample",
            "fabric.biome.search"
        ],
        "loader_game_entrypoints": "preflight-only",
        "minecraft_java_classes": "blocked",
        "fabric_event_callbacks": "blocked",
        "item_registry_and_components": "contract-only",
        "custom_play_payloads": "contract-only",
        "mixin_application": "blocked",
        "access_widener_application": "blocked",
        "client_gui_and_rendering": "contract-only",
        "resource_reload": "contract-only",
        "target_markers": [
            {
                "id": "HZN-FABRIC-BRIDGE-001",
                "module": "bridge-protocol",
                "path": "crates/horizon-mods/src/runtime/fabric_compat/mod.rs",
                "status": "implemented"
            },
            {
                "id": "HZN-FABRIC-EVENTS-001",
                "module": "api-events",
                "path": "crates/horizon-mods/src/runtime/fabric_compat/events.rs",
                "status": "contract-only"
            },
            {
                "id": "HZN-FABRIC-BIOME-001",
                "module": "world-biomes",
                "path": "crates/horizon-mods/src/runtime.rs::BiomeSearch dispatch",
                "status": "native-backend-only"
            },
            {
                "id": "HZN-FABRIC-REGISTRY-001",
                "module": "registries-components",
                "path": "crates/horizon-mods/src/runtime/fabric_compat/registry.rs",
                "status": "contract-only"
            },
            {
                "id": "HZN-FABRIC-NETWORK-001",
                "module": "play-networking",
                "path": "crates/horizon-mods/src/runtime/fabric_compat/networking.rs",
                "status": "contract-only"
            },
            {
                "id": "HZN-FABRIC-MIXIN-001",
                "module": "mixin-adapters",
                "path": "crates/horizon-mods/src/runtime/fabric_compat/mixin.rs",
                "status": "blocked"
            },
            {
                "id": "HZN-FABRIC-RENDER-001",
                "module": "client-ui-render",
                "path": "crates/horizon-mods/src/runtime/fabric_compat/rendering.rs",
                "status": "contract-only"
            },
            {
                "id": "HZN-FABRIC-RESOURCES-001",
                "module": "resources-reload",
                "path": "crates/horizon-mods/src/runtime/fabric_compat/resources.rs",
                "status": "contract-only"
            },
            {
                "id": "HZN-FABRIC-DIAG-001",
                "module": "diagnostics",
                "path": "crates/horizon-mods/src/runtime/fabric_compat/capabilities.rs",
                "status": "implemented"
            }
        ],
        "notes": "Target markers identify registered source-level integration points for the static map. They are not live per-call execution traces. Native biome operations are game-thread backend calls, not Java Minecraft/Fabric facade implementations."
    })
}
