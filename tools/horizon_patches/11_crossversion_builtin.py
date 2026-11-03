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

def replace_one_of(text: str, variants: tuple[tuple[str, str], ...], label: str) -> str:
    """Apply one known upstream API shape; fail if none or multiple shapes match."""
    if any(new in text for _, new in variants):
        return text
    matches = [(old, new) for old, new in variants if text.count(old) == 1]
    if len(matches) != 1:
        counts = [text.count(old) for old, _ in variants]
        raise SystemExit(
            f"{net}: expected exactly one supported {label} anchor; "
            f"matched {len(matches)} variants with counts {counts}"
        )
    old, new = matches[0]
    return text.replace(old, new, 1)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
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

connect_legacy_old = """        Self::start(address, name, auth, view_distance, lang, None, None)"""
connect_legacy_new = """        let routed = crossversion::route(address);
        Self::start(
            routed.as_deref().unwrap_or(address),
            name,
            auth,
            view_distance,
            lang,
            None,
            None,
        )"""
connect_current_old = """        Self::start(
            address, name, auth, info, lang, None, None, resources, false,
        )"""
connect_current_new = """        let routed = crossversion::route(address);
        Self::start(
            routed.as_deref().unwrap_or(address),
            name,
            auth,
            info,
            lang,
            None,
            None,
            resources,
            false,
        )"""
text = replace_one_of(
    text,
    (
        (connect_legacy_old, connect_legacy_new),
        (connect_current_old, connect_current_new),
    ),
    "Session::connect routing",
)

transfer_legacy_old = """        Self::start(
            address,
            name,
            auth,
            view_distance,
            lang,
            None,
            Some(cookies),
        )"""
transfer_legacy_new = """        let routed = crossversion::route(address);
        Self::start(
            routed.as_deref().unwrap_or(address),
            name,
            auth,
            view_distance,
            lang,
            None,
            Some(cookies),
        )"""
transfer_current_old = """        Self::start(
            address,
            name,
            auth,
            info,
            lang,
            None,
            Some(cookies),
            resources,
            false,
        )"""
transfer_current_new = """        let routed = crossversion::route(address);
        Self::start(
            routed.as_deref().unwrap_or(address),
            name,
            auth,
            info,
            lang,
            None,
            Some(cookies),
            resources,
            false,
        )"""
text = replace_one_of(
    text,
    (
        (transfer_legacy_old, transfer_legacy_new),
        (transfer_current_old, transfer_current_new),
    ),
    "Session::connect_transferred routing",
)

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
