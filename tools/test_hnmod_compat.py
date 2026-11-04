import json
import tempfile
import unittest
from pathlib import Path

from prepare_hnmod_compat import prepare


class NativeHooksCompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "horizon-source"
        self.mods = self.root / "hnmod-mods"
        self.source_file = self.source / "crates/horizon-client/src/game/native_hooks.rs"
        self.source_file.parent.mkdir(parents=True)
        self.source_file.write_text(
            "pub(crate) const NATIVE_HOOKS_API: u32 = 359;\n", encoding="utf-8"
        )
        (self.mods / "jei" / "mixins" / "jei").mkdir(parents=True)
        self.toml = self.mods / "jei" / "mixins" / "jei" / "horizon-mixin.toml"
        self.toml.write_text(
            '[mod]\nid = "jei"\nversion = "0.1.0"\n'
            '[compatibility]\nhorizon_revision = "1da354b67a509812c4141b67de2affd6de65c342"\n',
            encoding="utf-8",
        )
        self.records_path = self.root / "hnmods.json"
        self.base_path = self.root / "base-runtime.json"
        self.records = [{
            "id": "jei",
            "version": "0.1.0",
            "game": "26.3",
            "horizon": "1da354b67a509812c4141b67de2affd6de65c342",
            "mixins": "0d6b103c70633d8fd9443d8d0acd3aadaacedb06",
            "hooks": 2,
            "sha256": "a" * 64,
        }]
        self.base = {"upstream_commit": "67dad0d1a5adca139b426c8114e74a33b376ba64"}
        self.write_inputs()

    def tearDown(self):
        self.temp.cleanup()

    def write_inputs(self):
        self.records_path.write_text(json.dumps(self.records), encoding="utf-8")
        self.base_path.write_text(json.dumps(self.base), encoding="utf-8")

    def test_jei_hook_v2_is_accepted_and_repinned_to_installed_horizon(self):
        api = prepare(self.records_path, self.base_path, self.source, self.mods)
        target = self.base["upstream_commit"]
        self.assertEqual(api, 359)
        self.assertIn(f'horizon_revision = "{target}"', self.toml.read_text())
        records = json.loads(self.records_path.read_text())
        self.assertEqual(records[0]["horizon"], "1da354b67a509812c4141b67de2affd6de65c342")
        self.assertEqual(json.loads(self.base_path.read_text())["native_hooks_api"], 359)

    def test_rejects_module_when_target_hook_api_is_too_old(self):
        self.source_file.write_text(
            "pub(crate) const NATIVE_HOOKS_API: u32 = 1;\n", encoding="utf-8"
        )
        before = self.toml.read_text()
        with self.assertRaisesRegex(ValueError, "requires native hook points API v2"):
            prepare(self.records_path, self.base_path, self.source, self.mods)
        self.assertEqual(self.toml.read_text(), before)

    def test_modules_without_hooks_keep_exact_horizon_pin(self):
        self.records[0]["hooks"] = None
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "must target the exact installed Horizon"):
            prepare(self.records_path, self.base_path, self.source, self.mods)

    def test_hook_bundle_must_match_manifest_toml_tested_revision_before_repin(self):
        self.toml.write_text(
            '[mod]\nid = "jei"\nversion = "0.1.0"\n'
            '[compatibility]\nhorizon_revision = "' + "b" * 40 + '"\n',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "does not match its native bundle manifest"):
            prepare(self.records_path, self.base_path, self.source, self.mods)


if __name__ == "__main__":
    unittest.main()
