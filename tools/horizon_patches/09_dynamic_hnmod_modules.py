# Ordered patch step: let the official Horizon WIT loader discover dynamic .hnmod bundles.
# Legacy source/mixin .hnmod packages stay in horizon-native-mods and are never read here.

mods_runtime = root / "crates/horizon-mods/src/runtime.rs"
mods_cargo_path = root / "crates/horizon-mods/Cargo.toml"
mods_wit_path = root / "crates/horizon-mod-sdk/wit/horizon.wit"
manifest_path = root / "crates/horizon-mods/src/manifest.rs"

if not (
    mods_runtime.is_file()
    and mods_cargo_path.is_file()
    and mods_wit_path.is_file()
    and manifest_path.is_file()
    and 'wasmtime = {' in mods_cargo_path.read_text(encoding="utf-8")
):
    print("Dynamic .hnmod modules: no WIT runtime in this Horizon revision; legacy native path unchanged.")
else:
    source = manifest_path.read_text(encoding="utf-8")
    old = '.filter(|p| p.extension().is_some_and(|e| e == "hmod") || p.join(MANIFEST).is_file())'
    new = '.filter(|p| p.extension().is_some_and(|e| e == "hmod" || e == "hnmod") || p.join(MANIFEST).is_file())'
    if source.count(old) == 1:
        manifest_path.write_text(source.replace(old, new, 1), encoding="utf-8")
        print("Horizon WIT loader now discovers dynamic .hnmod component archives.")
    elif source.count(new) == 1:
        print("Horizon WIT loader already discovers dynamic .hnmod component archives.")
    else:
        raise SystemExit(
            f"{manifest_path}: expected exactly one supported .hmod discovery filter; "
            f"found old={source.count(old)}, new={source.count(new)}"
        )
