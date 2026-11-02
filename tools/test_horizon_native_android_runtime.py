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


def native_json(mod_id=MOD_ID):
    return json.dumps({
        "schema": 1,
        "id": mod_id,
        "version": "1.0.0",
        "game": "26.3",
        "horizon": REVISION,
        "mixins": MIXINS,
        "hooks": 1,
    })


def mixin_toml(mod_id=MOD_ID, version="1.0.0", revision=REVISION, injections=False):
    text = (
        "[mod]\n"
        f'id = "{mod_id}"\n'
        f'version = "{version}"\n'
        "[compatibility]\n"
        f'horizon_revision = "{revision}"\n'
    )
    if injections:
        text += (
            "\n[[injection]]\n"
            'id = "head"\n'
            'target = "fixture::tick"\n'
            'kind = "HEAD"\n'
            'helper = "hook.rs"\n'
        )
    return text


def make_bundle(path: Path, mod_id=MOD_ID, extras=(), injections=False,
                extra_mixin_manifest=""):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("horizon-native.json", native_json(mod_id))
        archive.writestr(
            f"mixins/{mod_id}/horizon-mixin.toml",
            mixin_toml(mod_id=mod_id, injections=injections) + extra_mixin_manifest,
        )
        archive.writestr(f"mixins/{mod_id}/hook.rs", "fn hook() {}\n")
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
        assert parsed["mixins_content"] is False
        assert set(parsed["files"]) == {
            f"mixins/{MOD_ID}/horizon-mixin.toml",
            f"mixins/{MOD_ID}/hook.rs",
        }

        # Native Rust source is exclusively .hnmod; .hmod remains content-only.
        combined = root / "combined.hmod"
        make_bundle(combined, extras=[
            ("horizon-mod.toml",
             f'id = "{MOD_ID}"\\nname = "Sample"\\nversion = "1.0.0"\\napi = "0.2"\\n'),
            ("assets/sample_native/lang/en_us.json", "{}"),
            ("data/sample_native/tags/item/test.json", "{}"),
        ])
        try:
            BUILDER.read_bundle(combined)
            raise AssertionError(".hmod must not enter the native Rust compiler")
        except BUILDER.BundleError:
            pass

        injected = root / "injected.hnmod"
        make_bundle(injected, mod_id="injected_native", injections=True)
        injected_bundle = BUILDER.read_bundle(injected)
        assert injected_bundle["mixins_content"] is True

        # Android build deliberately refuses desktop-only extensions until they are ported.
        unsupported_features = [
            "[api]\npath = \"native-api\"\n",
            "[dependencies]\nother_mod = \"^1.0\"\n",
            "[[post_build]]\nprogram = \"tools/build.rs\"\n",
        ]
        for index, extra in enumerate(unsupported_features):
            unsupported = root / f"unsupported-{index}.hnmod"
            make_bundle(unsupported, mod_id=f"unsupported_{index}",
                        extra_mixin_manifest=extra)
            try:
                BUILDER.read_bundle(unsupported)
                raise AssertionError("unsupported desktop native feature should have been rejected")
            except BUILDER.BundleError as error:
                assert "does not yet support" in str(error)

        # Data-only native bundles bypass mixin injection but still populate native-data/<id>.
        mods_dir = root / "mods"
        data_root = mods_dir / MOD_ID / "data" / "example"
        data_root.mkdir(parents=True)
        (data_root / "payload.json").write_text("{}", encoding="utf-8")
        stage = root / "stage"
        stage.mkdir()
        BUILDER.stage_trait_data(stage, [parsed], mods_dir)
        assert (stage / "native-data" / MOD_ID / "example" / "payload.json").read_text(
            encoding="utf-8"
        ) == "{}"

        bad_path = root / "bad-path.hnmod"
        make_bundle(
            bad_path,
            extras=[(f"mixins/{MOD_ID}/data/../escape.rs", "bad")],
        )
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

        second = root / "second.hnmod"
        make_bundle(second, mod_id="other_native")
        bundle_a = BUILDER.read_bundle(valid)
        bundle_b = BUILDER.read_bundle(second)
        key_ab = BUILDER.build_modset_key([bundle_a, bundle_b])
        key_ba = BUILDER.build_modset_key([bundle_b, bundle_a])
        assert key_ab == key_ba
        assert len(key_ab) == 64
        print("Native runtime builder contract tests passed.")


if __name__ == "__main__":
    main()
