#!/usr/bin/env python3
"""Backport Horizon's 26.3 asset-index republication fix without moving the pinned revision."""
from pathlib import Path
import json
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_horizon_prepare_asset_index.py <horizon-source-root>")

root = Path(sys.argv[1]).resolve()


def read(relative):
    path = root / relative
    if not path.is_file():
        raise SystemExit(f"Expected pinned Horizon source file is missing: {relative}")
    return path, path.read_text(encoding="utf-8")


# Add the known Mojang republications to the lock shipped with this preparer.
lock_path, lock_text = read("research-lock.json")
lock = json.loads(lock_text)
minecraft = lock.get("minecraft")
if not isinstance(minecraft, dict) or minecraft.get("version") != "26.3":
    raise SystemExit("Pinned research-lock.json is not the expected Minecraft 26.3 profile")
accepted = [
    "abfaa525f923f807df8b4e4d29c1b5e3a104adbe",
    "1e4e4a68dece1c0f51b8f93b45da8fe8da51bcd3",
    "0c8f43b28f837869c1e54a6aeb22c396a1548946",
]
existing = minecraft.get("accepted_asset_index_sha1", [])
if not isinstance(existing, list):
    raise SystemExit("research-lock.json accepted_asset_index_sha1 must be an array")
for checksum in accepted:
    if checksum not in existing:
        existing.append(checksum)
minecraft["accepted_asset_index_sha1"] = existing
minecraft["accepted_asset_index_note"] = (
    "Known Mojang republications of asset index id 34 for Minecraft 26.3. "
    "Unknown indexes are also usable with a warning when individual objects pass SHA-1 checks."
)
lock_path.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def replace_once(source, old, new, description):
    count = source.count(old)
    if count != 1:
        raise SystemExit(
            f"Refusing unsafe asset-index patch for {description}: expected one match, got {count}"
        )
    return source.replace(old, new, 1)


# Extend Horizon's Lock interface with the allowlist lookup.
path, artifacts = read("crates/horizon-prepare/src/artifacts.rs")
method = """    /// The known asset indexes of Mojang's republished Minecraft 26.3 version.
    pub(crate) fn asset_index_accepted(&self, checksum: &str) -> bool {
        self.json
            .get("minecraft")
            .and_then(|g| g.get("accepted_asset_index_sha1"))
            .and_then(Value::as_array)
            .is_some_and(|list| list.iter().any(|v| v.as_str() == Some(checksum)))
    }
"""
if "asset_index_accepted" not in artifacts:
    marker = "\n}\n\nfn home() -> PathBuf {"
    artifacts = replace_once(artifacts, marker, "\n" + method + marker, "artifacts.rs Lock implementation")
    path.write_text(artifacts, encoding="utf-8")

# The older pinned Rust source rejects any index SHA mismatch. Allow it with a warning:
# all objects used by the resource exporter are still verified by their own SHA-1.
path, sounds = read("crates/horizon-prepare/src/sounds.rs")
if "fn launcher_assets(\n    lock: &Lock," not in sounds:
    pattern = r"(?ms)^/// The launcher's asset directory.*?^}\n(?=\n/// Where the launcher's asset store keeps)"
    new_function = """/// The launcher's asset directory and its version's asset index.
/// Mojang may republish an index under the same ID; each object is still checked by SHA-1.
fn launcher_assets(
    lock: &Lock,
    manifest: &Value,
    minecraft: &Path,
    log: Log,
) -> Result<(PathBuf, PathBuf), String> {
    let assets = minecraft.join("assets");
    let info = manifest
        .get("assetIndex")
        .ok_or("The version JSON has no assetIndex")?;
    let field = |key: &str| {
        info.get(key)
            .and_then(Value::as_str)
            .ok_or_else(|| format!("The version JSON's assetIndex has no {key}"))
    };
    let (id, sha1) = (field("id")?, field("sha1")?);
    if artifacts::safe_relative(id).is_none_or(|p| p.components().count() != 1) {
        return Err(format!("Unsafe asset index id {id:?}"));
    }
    let index = assets.join("indexes").join(format!("{id}.json"));
    if !index.is_file() {
        let version = manifest.get("id").and_then(Value::as_str).unwrap_or("");
        return Err(format!(
            "Missing {}. {}",
            index.display(),
            artifacts::launcher_hint(version)
        ));
    }
    let checksum = digest(&index, "sha1")?;
    if checksum != sha1 && !lock.asset_index_accepted(&checksum) {
        log(&format!(
            "{} is an asset index Horizon does not know yet (sha1 {checksum}); Mojang may have \\
             republished it. Using it: every file is still checked against its own SHA-1.",
            index.display()
        ));
    }
    Ok((assets, index))
}"""
    sounds, n = re.subn(pattern, lambda _match: new_function, sounds, count=1)
    if n != 1:
        raise SystemExit("Refusing unsafe asset-index patch: pinned sounds.rs function changed")
    path.write_text(sounds, encoding="utf-8")

old_call = "let (store, index) = launcher_assets(&manifest, &minecraft)?;"
new_call = "let (store, index) = launcher_assets(&lock, &manifest, &minecraft, log)?;"
# Horizon v0.2.81 already contains the newer, equivalent loader. Keep the legacy backport
# for older pinned sources, but do not require replacing its modern GameFiles/cache API.
upstream_asset_loader = (
    "files: &artifacts::GameFiles" in sounds
    and "cached_index: &Path" in sounds
    and "let (store, index, bytes) = launcher_assets(" in sounds
)
if new_call not in sounds and not upstream_asset_loader:
    sounds = replace_once(sounds, old_call, new_call, "sounds.rs launcher_assets call")

old_sounds = 'copy_assets(root, minecraft, log, is_sound, "Sound", &[])'
new_sounds = """copy_assets(
        root,
        minecraft,
        log,
        is_sound,
        "Sound",
        &["minecraft/sounds.json"],
    )"""
if new_sounds not in sounds:
    sounds = replace_once(sounds, old_sounds, new_sounds, "sounds.rs required sound index")
path.write_text(sounds, encoding="utf-8")

# Final invariants: fail the Android build if the backport only partly applied.
if "asset_index_accepted" not in artifacts:
    artifacts = (root / "crates/horizon-prepare/src/artifacts.rs").read_text(encoding="utf-8")
upstream_asset_loader = (
    "files: &artifacts::GameFiles" in sounds
    and "cached_index: &Path" in sounds
    and "let (store, index, bytes) = launcher_assets(" in sounds
)
if ("asset_index_accepted" not in artifacts
        or (new_call not in sounds and not upstream_asset_loader)
        or new_sounds not in sounds):
    raise SystemExit("Asset-index compatibility backport did not fully apply")
checked_lock = json.loads((root / "research-lock.json").read_text(encoding="utf-8"))
if not all(h in checked_lock["minecraft"]["accepted_asset_index_sha1"] for h in accepted):
    raise SystemExit("Asset-index allowlist was not written correctly")

print("Patched pinned Horizon preparer for republished 26.3 asset indexes; object SHA-1 checks remain enabled.")
