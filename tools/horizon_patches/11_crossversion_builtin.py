#!/usr/bin/env python3
"""Install the CrossVersion proxy manager as a built-in Horizon client feature."""
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
repo_root = Path(__file__).resolve().parents[2]
net = root / "crates/horizon-client/src/net.rs"
source_dir = repo_root / "tools/crossversion"
engine_source = source_dir / "engine.rs"
decisions_source = source_dir / "decisions.rs"
engine_target = root / "crates/horizon-client/src/crossversion_engine.rs"
decisions_target = root / "crates/horizon-client/src/crossversion_decisions.rs"

for path in (net, engine_source, decisions_source):
    if not path.is_file():
        raise SystemExit(f"CrossVersion integration source is missing: {path}")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0 and new in text:
        return text
    if count != 1:
        raise SystemExit(f"{net}: expected one {label} anchor, found {count}")
    return text.replace(old, new, 1)

# The files are copied into the exact pinned Horizon checkout. No Cargo feature,
# native mixin registry or separate mod archive is needed for this integration.
for source, target in ((engine_source, engine_target), (decisions_source, decisions_target)):
    shutil.copyfile(source, target)

text = net.read_text(encoding="utf-8")

module_anchor = "use std::{"
module_insert = """#[path = "crossversion_decisions.rs"]
pub(crate) mod crossversion_decisions;

/// Built-in protocol translation through ViaProxy. The manager is compiled
/// directly into the Horizon client and is not an importable mod package.
pub mod crossversion {
    include!("crossversion_engine.rs");
}

use std::{"""
text = replace_once(text, module_anchor, module_insert, "CrossVersion module declarations")

connect_old = """        Self::start(address, name, auth, view_distance, lang, None, None)"""
connect_new = """        let routed = crossversion::route(address);
        Self::start(
            routed.as_deref().unwrap_or(address),
            name,
            auth,
            view_distance,
            lang,
            None,
            None,
        )"""
text = replace_once(text, connect_old, connect_new, "Session::connect routing")

transfer_old = """        Self::start(
            address,
            name,
            auth,
            view_distance,
            lang,
            None,
            Some(cookies),
        )"""
transfer_new = """        let routed = crossversion::route(address);
        Self::start(
            routed.as_deref().unwrap_or(address),
            name,
            auth,
            view_distance,
            lang,
            None,
            Some(cookies),
        )"""
text = replace_once(text, transfer_old, transfer_new, "Session::connect_transferred routing")

disconnect_old = """    pub fn disconnect(&mut self) {
        if let Some(w) = self.writer.lock().unwrap().take() {"""
disconnect_new = """    pub fn disconnect(&mut self) {
        crossversion::session_ended(&self.address);
        if let Some(w) = self.writer.lock().unwrap().take() {"""
text = replace_once(text, disconnect_old, disconnect_new, "Session::disconnect cleanup")

ping_old = """    let json = match status::Clientbound::decode(id, &payload).map_err(e)? {
        status::Clientbound::StatusResponse(s) => {
            serde_json::from_str(&s.json).map_err(|e| e.to_string())?
        }
        _ => return Err("unexpected status reply".into()),
    };
    let start = std::time::Instant::now();"""
ping_new = """    let json = match status::Clientbound::decode(id, &payload).map_err(e)? {
        status::Clientbound::StatusResponse(s) => {
            serde_json::from_str(&s.json).map_err(|e| e.to_string())?
        }
        _ => return Err("unexpected status reply".into()),
    };
    // The internal protocol probe from route() keeps the real server version;
    // ordinary server-list pings are dressed as compatible when ViaProxy can
    // translate them.
    let json = if crossversion::should_badge() {
        crossversion_decisions::badge(json, address)
    } else {
        json
    };
    let start = std::time::Instant::now();"""
text = replace_once(text, ping_old, ping_new, "net::ping protocol badge")

net.write_text(text, encoding="utf-8")
print("Installed built-in CrossVersion routing, transferred-session handling, proxy cleanup and server-list badges.")
