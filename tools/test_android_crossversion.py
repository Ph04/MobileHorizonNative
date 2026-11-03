#!/usr/bin/env python3
"""Static preflight for the built-in CrossVersion Android integration."""
from __future__ import annotations

import argparse
import re
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("CrossVersion preflight failed: " + message)


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as error:
        raise SystemExit(f"Cannot read {path}: {error}") from error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path,
                        help="patched pinned Horizon source directory")
    parser.add_argument("--repo", required=True, type=Path,
                        help="MobileHorizonNative checkout")
    args = parser.parse_args()
    source = args.source.resolve()
    repo = args.repo.resolve()

    adapter_path = repo / "tools/horizon_patches/11_crossversion_builtin.py"
    adapter = read(adapter_path)
    for marker, reason in (
        ("connect_legacy_old", "the stable Horizon connect signature is not supported"),
        ("connect_current_old", "the current Nightly connect signature is not supported"),
        ("transfer_legacy_old", "the stable transferred-connect signature is not supported"),
        ("transfer_current_old", "the current Nightly transferred-connect signature is not supported"),
        ("replace_one_of", "the adapter no longer validates exact supported API anchors"),
    ):
        require(marker in adapter, reason)

    net_path = source / "crates/horizon-client/src/net.rs"
    engine_path = source / "crates/horizon-client/src/crossversion_engine.rs"
    decisions_path = source / "crates/horizon-client/src/crossversion_decisions.rs"
    cargo_path = source / "crates/horizon-client/Cargo.toml"
    net = read(net_path)
    engine = read(engine_path)
    decisions = read(decisions_path)
    cargo = read(cargo_path)

    require(net.count("crossversion::route(address)") == 2,
            "both normal and transferred server connections must be routed")
    require("crossversion::session_ended(&self.address);" in net,
            "Session::disconnect must release its ViaProxy reference")
    require("crossversion::should_badge()" in net
            and "crossversion_decisions::badge(json, address)" in net,
            "server-list status pings must display the translated protocol")
    require("pub mod crossversion" in net
            and 'include!("crossversion_engine.rs")' in net,
            "CrossVersion must be compiled into horizon-client, not loaded as a mod")
    require("horizon_protocol::PROTOCOL_VERSION as i64" in decisions,
            "server-list compatibility must follow the pinned Horizon protocol")
    require("HORIZON_CROSSVERSION_DATA_DIR" in engine,
            "the engine and app must share the same private CrossVersion directory")
    require("is_loopback_address(address)" in engine
            and "forced.is_none()" in engine,
            "local servers must not be probed automatically unless an override is configured")
    require("fn auth_account(force_offline: bool, saved_account: Option<usize>) -> Option<usize>" in engine
            and "auth_account(settings.offline_auth, proxy_account())" in engine
            and "proxy.account == account" in engine
            and '.args(auth_method_args(account))' in engine
            and 'None => vec!["--auth-method".to_owned(), "NONE".to_owned()]' in engine,
            "CrossVersion must force ViaProxy offline authentication on request and separate proxy processes by auth mode")
    require("no_microsoft_account_uses_viaproxy_offline_backend_authentication" in engine
            and "force_offline_mode_ignores_a_saved_microsoft_account" in engine
            and "automatic_mode_uses_a_saved_microsoft_account" in engine,
            "offline override, no-account, and automatic Microsoft account modes must have Rust unit tests")
    offline_login_patch = read(repo / "tools/horizon_patches/01_offline_server_login.py")
    require("offline-mode fallback did not reach the server" in offline_login_patch
            and "sending the normal encrypted response" in offline_login_patch,
            "offline-account login behavior and its matching Rust test must remain aligned")
    android_native_patch = read(repo / "tools/horizon_patches/02_android_native.py")
    require("entrypoints = {" in android_native_patch
            and 'if len(entrypoints) != 1:' in android_native_patch
            and 'call_pattern = r"horizon_client::game::run(?:_with)?\\(\\)"' in android_native_patch
            and 'Selected Horizon game entry point:' in android_native_patch,
            "the Android NativeActivity must select game::run or game::run_with from the pinned Horizon API")
    require("HORIZON_CROSSVERSION_JAVA" in engine
            and "java_command(java)" in engine,
            "ViaProxy must use the Java runtime selected by MobileHorizonNative")
    require("/system/bin/linker64" in engine,
            "Android app-private Java must be launched through linker64")
    require("HORIZON_CROSSVERSION_LD_LIBRARY_PATH" in engine
            and "HORIZON_CROSSVERSION_JAVA_TOOL_OPTIONS" in engine,
            "the ViaProxy process must receive its Java 25 environment and memory limits")
    require("2894bbfb2f4342f8fde887af6c689313efd5b0e46a8006775eea0e21e2948ab5" in engine,
            "ViaProxy 3.4.14 SHA-256 pin is missing")
    require('sha2 = "0.10.9"' in cargo and 'ureq = { version = "2"' in cargo,
            "pinned Horizon client dependencies must support checksum and HTTPS downloads")

    manager_path = repo / "app/src/main/java/dev/mobilehorizon/app/CrossVersionManager.java"
    service_path = repo / "app/src/main/java/dev/mobilehorizon/app/CrossVersionService.java"
    activity_path = repo / "app/src/main/java/dev/mobilehorizon/app/CrossVersionActivity.java"
    native_activity_path = repo / "app/src/main/java/dev/mobilehorizon/app/NativeGameActivity.java"
    settings_path = repo / "app/src/main/java/dev/mobilehorizon/app/SettingsActivity.java"
    manifest_path = repo / "app/src/main/AndroidManifest.xml"
    manager = read(manager_path)
    service = read(service_path)
    activity = read(activity_path)
    native_activity = read(native_activity_path)
    settings = read(settings_path)
    manifest = read(manifest_path)

    rust_hash = re.search(r'const VIAPROXY_SHA256: &str = "([0-9a-f]{64})";', engine)
    java_hash = re.search(r'VIA_PROXY_SHA256\s*=\s*\n?\s*"([0-9a-f]{64})"', manager)
    require(rust_hash is not None and java_hash is not None
            and rust_hash.group(1) == java_hash.group(1),
            "Java download manager and built-in runtime must share the exact ViaProxy SHA-256")
    require("HttpURLConnection" in manager and "getContentLengthLong" in manager
            and "SHA-256" in manager,
            "the app downloader must verify bounded HTTPS downloads")
    require("accountsV4" in manager and "MicrosoftAccount" in manager,
            "the UI must detect ViaProxy-owned Microsoft accounts without reading Horizon sessions")
    require('defaults.put("offline_auth", false)' in manager
            and 'config.put("offline_auth", offline)' in manager
            and "setOfflineAuth" in activity
            and "Forçar autenticação offline no ViaProxy" in activity
            and 'offlineAuthCheck.setChecked(config.optBoolean("offline_auth", false))' in activity,
            "CrossVersion must expose and persist an offline override even when a Microsoft account is saved")
    require("account add microsoft" in service
            and "microsoft.com/link" in activity,
            "the Microsoft device-code login flow is missing")
    require("runtime.java.getAbsolutePath()" in service
            and "/system/bin/linker64" in service,
            "the sign-in service must use the installed Android Java runtime via linker64")
    for variable in (
        "HORIZON_CROSSVERSION_DATA_DIR",
        "HORIZON_CROSSVERSION_JAVA",
        "HORIZON_CROSSVERSION_JAVA_HOME",
        "HORIZON_CROSSVERSION_LD_LIBRARY_PATH",
        "HORIZON_CROSSVERSION_HOME",
        "HORIZON_CROSSVERSION_TMPDIR",
        "HORIZON_CROSSVERSION_JAVA_TOOL_OPTIONS",
    ):
        require(variable in native_activity,
                f"NativeGameActivity does not export {variable}")
    require("CrossVersionActivity.class" in settings,
            "launcher settings do not link to CrossVersion")
    require('android:name=".CrossVersionActivity"' in manifest
            and 'android:name=".CrossVersionService"' in manifest,
            "CrossVersion activity/service are not registered in AndroidManifest.xml")

    archive_path = repo / "app/src/main/java/dev/mobilehorizon/app/HorizonModArchive.java"
    archive = read(archive_path)
    require('endsWith(".hmod")' in archive and 'endsWith(".hnmod")' not in archive,
            "the WIT archive reader must accept only the official .hmod suffix")
    native_import = read(repo / "app/src/main/java/dev/mobilehorizon/app/ResourceManagerActivity.java")
    require("HorizonNativeModArchive" not in native_import
            and "HorizonNativeRuntimeManager" not in native_import
            and "NATIVE_MOD" not in native_import
            and 'lower.endsWith(".hnmod")' not in native_import,
            "the legacy .hnmod importer and per-mod runtime compiler must be absent")
    require("readModId(staging)" in native_import
            and "HorizonModArchive.readId(file)" in native_import
            and 'lower.endsWith(".hmod")' in native_import,
            "the official Horizon .hmod import path must remain available")
    require("HorizonNativeRuntimeManager" not in native_activity
            and ".hnmod" not in native_activity,
            "game startup must always use the normal runtime or the independent Java Bridge runtime")
    print("CrossVersion preflight passed: built-in routing, proxy lifecycle, Android Java, device login,")
    print("server-version overrides, checksum pin, official .hmod preservation, and .hnmod cleanup.")
    print("This is a static source check; Android engine compilation and real server testing remain required.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
