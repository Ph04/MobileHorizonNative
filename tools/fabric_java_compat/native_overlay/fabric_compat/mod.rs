//! Independent native adapter contracts for original Fabric JARs.
pub mod biome;
pub mod capabilities;
pub mod events;
pub mod mixin;
pub mod networking;
pub mod registry;
pub mod rendering;
pub mod resources;

use serde_json::Value;
use std::{sync::mpsc::{SyncSender, TrySendError}, time::Duration};

#[derive(Debug)]
pub struct NativeQuery {
    pub request_id: String,
    pub path: String,
    pub payload: Value,
    pub timeout_ms: u64,
    pub reply: SyncSender<Result<Value, String>>,
}

/// Forward only allow-listed native calls, with a bounded queue and a caller-selected deadline.
pub fn forward_query(
    path: &str,
    request_id: &str,
    payload: Value,
    timeout_ms: u64,
    sender: &SyncSender<NativeQuery>,
) -> Result<Value, String> {
    if request_id.is_empty() || request_id.len() > 128 {
        return Err("invalid_request_id".into());
    }
    if path.is_empty() || path.len() > 128 || !supported_game_query(path) {
        return Err("unsupported_query".into());
    }
    if !(1..=30_000).contains(&timeout_ms) {
        return Err("invalid_query_timeout".into());
    }
    let (reply, receiver) = std::sync::mpsc::sync_channel(1);
    let request = NativeQuery {
        request_id: request_id.into(),
        path: path.into(),
        payload,
        timeout_ms,
        reply,
    };
    match sender.try_send(request) {
        Ok(()) => {}
        Err(TrySendError::Full(_)) => return Err("native_query_queue_full".into()),
        Err(TrySendError::Disconnected(_)) => return Err("native_query_queue_closed".into()),
    }
    receiver.recv_timeout(Duration::from_millis(timeout_ms)).map_err(|error| match error {
        std::sync::mpsc::RecvTimeoutError::Timeout => "native_query_timeout".to_owned(),
        std::sync::mpsc::RecvTimeoutError::Disconnected => "native_query_reply_closed".to_owned(),
    })?
}

fn supported_game_query(path: &str) -> bool {
    matches!(path,
        "fabric.biome.catalog" | "fabric.biome.sample" | "fabric.biome.search"
        | "fabric.registry.biomes" | "fabric.resources.capabilities"
        | "fabric.mixins.capabilities" | "fabric.render.capabilities"
        | "fabric.networking.capabilities")
}

pub fn capability_report() -> Value {
    capabilities::report()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn unsupported_invalid_and_full_queue_fail_closed() {
        let (sender, _receiver) = std::sync::mpsc::sync_channel(1);
        assert_eq!(forward_query("os.execute", "q1", Value::Null, 1000, &sender), Err("unsupported_query".into()));
        assert_eq!(forward_query("fabric.biome.search", "", Value::Null, 1000, &sender), Err("invalid_request_id".into()));
        assert_eq!(forward_query("fabric.biome.search", "q", Value::Null, 0, &sender), Err("invalid_query_timeout".into()));
        let (reply, _reply_receiver) = std::sync::mpsc::sync_channel(1);
        sender.try_send(NativeQuery {
            request_id: "busy".into(), path: "fabric.biome.catalog".into(),
            payload: json!({}), timeout_ms: 1000, reply,
        }).unwrap();
        assert_eq!(forward_query("fabric.biome.search", "q2", json!({}), 1000, &sender), Err("native_query_queue_full".into()));
    }

    #[test]
    fn query_deadline_is_honored() {
        let (sender, _receiver) = std::sync::mpsc::sync_channel(1);
        assert_eq!(forward_query("fabric.biome.search", "timeout", json!({"biome_id":"forest"}), 5, &sender), Err("native_query_timeout".into()));
    }

    #[test]
    fn report_does_not_claim_java_facades() {
        let report = capability_report();
        assert_eq!(report["minecraft_java_classes"], "blocked");
        assert_eq!(report["world_biome_backend"], "native-backend-only");
    }
}
