#!/usr/bin/env python3
"""Small offline contract tests for the Android native-runtime staging helper."""
import importlib.util
import json
import tempfile
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("build_horizon_native_android_runtime.py")
SPEC = importlib.util.spec_from_file_location("native_runtime_builder", SCRIPT)
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)

REVISION = "a" * 40
MIXINS = "b" * 40
MOD_ID = "sample_native"


def native_json():
    return json.dumps({
        "schema": 1,
        "id": MOD_ID,
        "version": "1.0.0",
        "game": "26.3",
        "horizon": REVISION,
        "mixins": MIXINS,
        "hooks": 1,
    })


def mixin_toml(mod_id=MOD_ID, version="1.0.0", revision=REVISION):
    return (
        "[mod]\n"
        f'id = "{mod_id}"\n'
        f'version = "{version}"\n'
        "[compatibility]\n"
        f'horizon_revision = "{revision}"\n'
    )


def make_bundle(path: Path, extras=()):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("horizon-native.json", native_json())
        archive.writestr(
            f"mixins/{MOD_ID}/horizon-mixin.toml",
            mixin_toml(),
        )
        archive.writestr(f"mixins/{MOD_ID}/hook.rs", "fn hook() {}\n")
        for name, content in extras:
            archive.writestr(name, content)


def main():
    with tempfile.TemporaryDirectory(prefix="mh-native-test-") as temp:
        root = Path(temp)
        valid = root / "valid.hnmod"
        make_bundle(valid)
        parsed = BUILDER.read_bundle(valid)
        assert parsed["manifest"]["id"] == MOD_ID
        assert parsed["manifest"]["hooks"] == 1
        assert set(parsed["files"]) == {
            f"mixins/{MOD_ID}/horizon-mixin.toml",
            f"mixins/{MOD_ID}/hook.rs",
        }

        # The same native code can be bundled with content, but both IDs must agree.
        combined = root / "combined.hmod"
        make_bundle(combined, [
            ("horizon-mod.toml",
             f'id = "{MOD_ID}"\nname = "Sample"\nversion = "1.0.0"\napi = "0.2"\n'),
            ("assets/sample_native/lang/en_us.json", "{}"),
            ("data/sample_native/tags/item/test.json", "{}"),
        ])
        assert BUILDER.read_bundle(combined)["manifest"]["id"] == MOD_ID

        bad_path = root / "bad-path.hnmod"
        make_bundle(bad_path, [(f"mixins/{MOD_ID}/data/../escape.rs", "bad")])
        try:
            BUILDER.read_bundle(bad_path)
            raise AssertionError("path traversal should have been rejected")
        except BUILDER.BundleError:
            pass

        mismatch = root / "mismatch.hnmod"
        with zipfile.ZipFile(mismatch, "w") as archive:
            archive.writestr("horizon-native.json", native_json())
            archive.writestr(
                f"mixins/{MOD_ID}/horizon-mixin.toml",
                mixin_toml(mod_id="different_id"),
            )
        try:
            BUILDER.read_bundle(mismatch)
            raise AssertionError("mismatched bundle IDs should have been rejected")
        except BUILDER.BundleError:
            pass

        duplicate_a = BUILDER.read_bundle(valid)
        duplicate_b = BUILDER.read_bundle(combined)
        key_ab = BUILDER.build_modset_key([duplicate_a, duplicate_b])
        key_ba = BUILDER.build_modset_key([duplicate_b, duplicate_a])
        assert key_ab == key_ba
        assert len(key_ab) == 64
        print("Native runtime builder contract tests passed.")


if __name__ == "__main__":
    main()
