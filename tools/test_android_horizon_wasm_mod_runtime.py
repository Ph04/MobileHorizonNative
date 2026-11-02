#!/usr/bin/env python3
"""Static preflight for Horizon's optional Android WIT/WebAssembly mod runtime.

This does not compile Wasmtime or execute a mod. It checks that the selected
upstream revision exposes the official component-model runtime and that the
Android cdylib enables it only when the full SDK/runtime are present.
"""
from __future__ import annotations

import argparse
import tomllib
from pathlib import Path


def read_toml(path: Path) -> dict:
    try:
        return tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as exc:
        raise SystemExit(f"Cannot read {path}: {exc}") from exc


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("Android WIT mod runtime preflight failed: " + message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path,
                        help="patched Horizon source/staging directory")
    args = parser.parse_args()
    root = args.source.resolve()

    client_cargo_path = root / "crates/horizon-client/Cargo.toml"
    mods_cargo_path = root / "crates/horizon-mods/Cargo.toml"
    mods_runtime_path = root / "crates/horizon-mods/src/runtime.rs"
    wit_path = root / "crates/horizon-mod-sdk/wit/horizon.wit"
    android_cargo_path = root / "crates/mobilehorizon-android/Cargo.toml"

    require(client_cargo_path.is_file(), "horizon-client/Cargo.toml is missing")
    require(android_cargo_path.is_file(), "mobilehorizon-android/Cargo.toml is missing")

    client = read_toml(client_cargo_path)
    android = read_toml(android_cargo_path)
    mods_feature = client.get("features", {}).get("mods", [])
    has_official_runtime = (
        mods_cargo_path.is_file()
        and mods_runtime_path.is_file()
        and wit_path.is_file()
        and "horizon-mods/runtime" in mods_feature
    )

    dependency = android.get("dependencies", {}).get("horizon-client")
    require(isinstance(dependency, dict),
            "Android target must depend on the pinned horizon-client crate")
    enabled = set(dependency.get("features", []))

    if not has_official_runtime:
        require("mods" not in enabled,
                "Android enables the mods feature but this Horizon revision has no complete WIT runtime")
        print("WIT modules: not available in this upstream revision; native .hnmod path unchanged.")
        return 0

    require("mods" in enabled,
            "the upstream WIT runtime exists but Android did not enable horizon-client/mods")

    mods_cargo = read_toml(mods_cargo_path)
    wasmtime = mods_cargo.get("dependencies", {}).get("wasmtime")
    require(isinstance(wasmtime, dict),
            "horizon-mods must declare the optional Wasmtime dependency")
    wasmtime_features = set(wasmtime.get("features", []))
    require({"runtime", "component-model"}.issubset(wasmtime_features),
            "Wasmtime must include runtime and component-model support")
    require("cranelift" in wasmtime_features or "pulley" in wasmtime_features,
            "Wasmtime needs an execution backend")

    runtime = mods_runtime_path.read_text(encoding="utf-8")
    for marker, reason in (
        ("consume_fuel(true)", "fuel metering is not enabled"),
        ("memory_size(MEMORY)", "per-module memory limit is not installed"),
        ("set_fuel(FUEL_INIT)", "initialization fuel is not bounded"),
        ("const MEMORY: usize = 64 << 20;", "expected 64 MiB module memory cap is missing"),
        ("const FUEL_TICK: u64 = 50_000_000;", "tick fuel budget is missing"),
    ):
        require(marker in runtime, reason)

    wit = wit_path.read_text(encoding="utf-8")
    require("package horizon:mods@" in wit, "WIT contract has no versioned horizon:mods package")
    for interface in ("game", "chat", "hud", "storage", "ui", "render", "input"):
        require(f"interface {interface} {{" in wit,
                f"WIT contract is missing the {interface} interface")

    game_mods = root / "crates/horizon-client/src/game/mods.rs"
    require(game_mods.is_file(), "Horizon game mod host is missing")
    game_source = game_mods.read_text(encoding="utf-8")
    require("Mods::load_for" in game_source,
            "game startup does not load installed sandboxed mods")

    # MobileHorizonNative's dynamic .hnmod package uses the same official
    # horizon-mod.toml + mod.wasm component layout; only the archive suffix differs.
    manifest_source_path = root / "crates/horizon-mods/src/manifest.rs"
    require(manifest_source_path.is_file(), "Horizon mod package discovery is missing")
    manifest_source = manifest_source_path.read_text(encoding="utf-8")
    require('e == "hnmod"' in manifest_source,
            "the WIT loader is not patched to discover dynamic .hnmod archives")

    # The Android overlay extends the official component contract with an
    # explicit, capability-gated manager API for listing and toggling modules.
    require("interface mod-manager {" in wit,
            "WIT contract is missing the mod-manager capability interface")
    capability_source = manifest_source
    require("ModManager" in capability_source,
            "manifest capability parser does not recognize mod-manager")
    runtime_source = runtime
    require("impl mod_manager::Host for State" in runtime_source,
            "the Wasmtime host does not implement mod-manager")
    require("mod-manager.modules" in (root / "crates/horizon-mods/src/api_level.rs").read_text(encoding="utf-8"),
            "mod-manager functions are not registered in the API-level table")
    require("Output::ModEnabled" in game_source and "self.start_mods();" in game_source,
            "the game does not apply enabled-state changes after the current WIT hook returns")

    print("WIT modules: enabled for Android.")
    print("Dynamic .hnmod: discovered by Horizon's official component loader.")
    print("Host API: game/chat/HUD/storage/UI/render/input/mod-manager via versioned WIT.")
    print("Runtime safeguards: 64 MiB per module, fuel metering, capability import checks.")
    print("This is a static source preflight, not an Android Wasmtime build/device test.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
