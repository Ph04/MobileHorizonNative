import json
import subprocess
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
        subprocess.run(["git", "init", "-q"], cwd=self.source, check=True)
        subprocess.run(["git", "config", "user.email", "hnmod-tests@example.invalid"],
                       cwd=self.source, check=True)
        subprocess.run(["git", "config", "user.name", "HNMOD tests"],
                       cwd=self.source, check=True)
        subprocess.run(["git", "add", "."], cwd=self.source, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "legacy Horizon revision"],
                       cwd=self.source, check=True)
        self.legacy_revision = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.source, text=True
        ).strip()
        self.main_branch = subprocess.check_output(
            ["git", "branch", "--show-current"], cwd=self.source, text=True
        ).strip()
        subprocess.run(["git", "branch", "divergent"], cwd=self.source, check=True)
        self.source_file.write_text(
            "pub(crate) const NATIVE_HOOKS_API: u32 = 359;\n// target revision\n",
            encoding="utf-8",
        )
        subprocess.run(["git", "add", "."], cwd=self.source, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "target Horizon revision"],
                       cwd=self.source, check=True)
        self.target_revision = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.source, text=True
        ).strip()
        subprocess.run(["git", "checkout", "-q", "divergent"], cwd=self.source, check=True)
        (self.source / "divergent.txt").write_text("not an ancestor\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.source, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "divergent Horizon revision"],
                       cwd=self.source, check=True)
        self.divergent_revision = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.source, text=True
        ).strip()
        subprocess.run(["git", "checkout", "-q", self.main_branch], cwd=self.source, check=True)

        (self.mods / "jei" / "mixins" / "jei").mkdir(parents=True)
        self.toml = self.mods / "jei" / "mixins" / "jei" / "horizon-mixin.toml"
        self.toml.write_text(
            '[mod]\nid = "jei"\nversion = "0.1.0"\n'
            '[compatibility]\nhorizon_revision = "' + self.legacy_revision + '"\n',
            encoding="utf-8",
        )
        self.records_path = self.root / "hnmods.json"
        self.base_path = self.root / "base-runtime.json"
        self.records = [{
            "id": "jei",
            "version": "0.1.0",
            "game": "26.3",
            "horizon": self.legacy_revision,
            "mixins": "0d6b103c70633d8fd9443d8d0acd3aadaacedb06",
            "hooks": 2,
            "sha256": "a" * 64,
        }]
        self.base = {"upstream_commit": self.target_revision}
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
        self.assertEqual(records[0]["horizon"], self.legacy_revision)
        self.assertEqual(json.loads(self.base_path.read_text())["native_hooks_api"], 359)

    def test_selected_modules_can_keep_different_mixins_pins(self):
        target = self.base["upstream_commit"]
        (self.mods / "mod_menu" / "mixins" / "mod_menu").mkdir(parents=True)
        mod_menu_toml = self.mods / "mod_menu" / "mixins" / "mod_menu" / "horizon-mixin.toml"
        mod_menu_toml.write_text(
            '[mod]\nid = "mod_menu"\nversion = "0.3.0"\n'
            '[compatibility]\nhorizon_revision = "' + target + '"\n',
            encoding="utf-8",
        )
        self.records.append({
            "id": "mod_menu",
            "version": "0.3.0",
            "game": "26.3",
            "horizon": target,
            "mixins": "d9c83d7096b7ea5b83678da2efdfd6161f74997e",
            "hooks": None,
            "sha256": "b" * 64,
        })
        self.write_inputs()

        api = prepare(self.records_path, self.base_path, self.source, self.mods)

        self.assertEqual(api, 359)
        records = json.loads(self.records_path.read_text())
        self.assertEqual(
            {item["mixins"] for item in records},
            {
                "0d6b103c70633d8fd9443d8d0acd3aadaacedb06",
                "d9c83d7096b7ea5b83678da2efdfd6161f74997e",
            },
        )
        self.assertIn(f'horizon_revision = "{target}"', mod_menu_toml.read_text())

    def test_rejects_module_when_target_hook_api_is_too_old(self):
        self.source_file.write_text(
            "pub(crate) const NATIVE_HOOKS_API: u32 = 1;\n", encoding="utf-8"
        )
        before = self.toml.read_text()
        with self.assertRaisesRegex(ValueError, "requires native hook points API v2"):
            prepare(self.records_path, self.base_path, self.source, self.mods)
        self.assertEqual(self.toml.read_text(), before)

    def test_legacy_bundle_is_repinned_only_after_git_ancestry_proof(self):
        self.records[0]["hooks"] = None
        self.write_inputs()

        api = prepare(self.records_path, self.base_path, self.source, self.mods)

        self.assertEqual(api, 359)
        self.assertIn(f'horizon_revision = "{self.target_revision}"', self.toml.read_text())
        records = json.loads(self.records_path.read_text())
        self.assertEqual(records[0]["horizon"], self.legacy_revision)
        base = json.loads(self.base_path.read_text())
        self.assertTrue(base["legacy_horizon_revisions_verified"])

    def test_legacy_bundle_from_divergent_horizon_history_is_rejected(self):
        self.records[0]["hooks"] = None
        self.records[0]["horizon"] = self.divergent_revision
        self.toml.write_text(
            '[mod]\nid = "jei"\nversion = "0.1.0"\n'
            '[compatibility]\nhorizon_revision = "' + self.divergent_revision + '"\n',
            encoding="utf-8",
        )
        self.write_inputs()

        with self.assertRaisesRegex(ValueError, "not a verified ancestor"):
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
