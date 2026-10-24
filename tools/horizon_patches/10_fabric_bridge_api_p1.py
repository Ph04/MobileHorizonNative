"""Install the isolated P1 kernel and its separate WIT world into pinned Horizon."""
from pathlib import Path
import re
import shutil

source_dir = repo_root / "tools/fabric_bridge_p1"
source_lib = source_dir / "src"
target_lib = root / "crates/horizon-mods/src/fabric_bridge_p1"
source_wit = source_dir / "wit/horizon-fabric-p1.wit"
target_wit_dir = root / "crates/horizon-mod-sdk/wit-fabric-p1"
runtime = root / "crates/horizon-mods/src/runtime.rs"
mods_lib = root / "crates/horizon-mods/src/lib.rs"
mods_cargo = root / "crates/horizon-mods/Cargo.toml"
lock_file = root / "Cargo.lock"

module_files = ("lib.rs", "commands.rs", "config.rs", "events.rs", "hud.rs", "keys.rs", "quota.rs", "api_v06.rs")
for name in module_files:
    path = source_lib / name
    if not path.is_file():
        raise SystemExit(f"Missing P1 source file: {path}")
if not source_wit.is_file():
    raise SystemExit(f"Missing P1 WIT contract: {source_wit}")
for path in (runtime, mods_lib, mods_cargo, lock_file):
    if not path.is_file():
        raise SystemExit(f"Missing Horizon file required for Fabric P1 overlay: {path}")

target_lib.mkdir(parents=True, exist_ok=True)
for name in module_files:
    content = (source_lib / name).read_text(encoding="utf-8")
    if name == "lib.rs":
        target_name = "mod.rs"
    else:
        content = content.replace("use crate::Id;", "use super::Id;")
        content = content.replace("use crate::quota::{Quota, QuotaMeter};",
                                  "use super::quota::{Quota, QuotaMeter};")
        target_name = name
    (target_lib / target_name).write_text(content, encoding="utf-8")

target_wit_dir.mkdir(parents=True, exist_ok=True)
shutil.copyfile(source_wit, target_wit_dir / "horizon-fabric-p1.wit")

# Keep the existing world stable; compile-check the second WIT world without activating it yet.
runtime_text = runtime.read_text(encoding="utf-8")
old_bindings = """mod bindings {
    wasmtime::component::bindgen!({
        path: "../horizon-mod-sdk/wit",
        world: "horizon-mod",
    });
}"""
new_bindings = old_bindings + """

/// Experimental second world; host callbacks still need runtime integration.
#[allow(dead_code)]
mod fabric_bridge_p1_bindings {
    wasmtime::component::bindgen!({
        path: "../horizon-mod-sdk/wit-fabric-p1",
        world: "mod-p1",
    });
}"""
if runtime_text.count(old_bindings) != 1:
    raise SystemExit(f"{runtime}: expected one existing horizon-mod bindgen block")
runtime.write_text(runtime_text.replace(old_bindings, new_bindings, 1), encoding="utf-8")

mods_text = mods_lib.read_text(encoding="utf-8")
if "pub mod fabric_bridge_p1;" not in mods_text:
    if mods_text.count("pub mod content;") != 1:
        raise SystemExit(f"{mods_lib}: expected exactly one content module anchor")
    mods_lib.write_text(mods_text.replace(
        "pub mod content;", "pub mod content;\npub mod fabric_bridge_p1;", 1), encoding="utf-8")

cargo_text = mods_cargo.read_text(encoding="utf-8")
if "thiserror =" not in cargo_text:
    if cargo_text.count('serde_json = "1"') != 1:
        raise SystemExit(f"{mods_cargo}: expected serde_json dependency anchor")
    mods_cargo.write_text(cargo_text.replace(
        'serde_json = "1"', 'serde_json = "1"\nthiserror = "1"', 1), encoding="utf-8")

lock_text = lock_file.read_text(encoding="utf-8")
if not re.search(r'^name = "thiserror"\nversion = "1\.0\.69"$', lock_text, re.M):
    raise SystemExit("Pinned Cargo.lock does not contain thiserror 1.0.69")
pattern = r'(\[\[package\]\]\nname = "horizon-mods"\nversion = "[^"]+"\ndependencies = \[\n)(.*?)(\n\])'
match = re.search(pattern, lock_text, re.S)
if not match:
    raise SystemExit("Cargo.lock: couldn't locate horizon-mods dependency entry")
dependency_lines = match.group(2).splitlines()
if not any(line.strip().startswith('"thiserror ') or line.strip() == '"thiserror",' for line in dependency_lines):
    dependency_lines.append(' "thiserror 1.0.69",')
    dependency_lines.sort()
    replacement = match.group(1) + "\n".join(dependency_lines) + match.group(3)
    lock_text = lock_text[:match.start()] + replacement + lock_text[match.end():]
    lock_file.write_text(lock_text, encoding="utf-8")

print("Installed the Fabric Bridge kernel and v0.6 native API foundation; WIT/runtime hooks remain separately capability-gated.")
