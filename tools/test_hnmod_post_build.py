import json
import tempfile
import unittest
from pathlib import Path

from run_hnmod_post_build import load_steps, native_data_index, safe_data_path


class HnmodPostBuildTests(unittest.TestCase):
    def test_missing_post_build_file_means_no_steps(self):
        with tempfile.TemporaryDirectory() as temp:
            self.assertEqual(load_steps(Path(temp)), [])

    def test_loads_valid_post_build_steps(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "native-post-build.json").write_text(json.dumps([{
                "mod": "mod_menu",
                "package": "mod-menu-engine",
                "bin": "bake_tables",
                "args": ["--fast", "data file"],
            }]), encoding="utf-8")
            self.assertEqual(load_steps(root), [{
                "mod": "mod_menu",
                "package": "mod-menu-engine",
                "bin": "bake_tables",
                "args": ["--fast", "data file"],
            }])

    def test_rejects_unsafe_mod_or_program_name(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "native-post-build.json").write_text(
                '[{"mod":"../escape","package":"p","bin":"b"}]', encoding="utf-8")
            with self.assertRaises(ValueError):
                load_steps(root)

    def test_rejects_non_array_post_build_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "native-post-build.json").write_text("{}", encoding="utf-8")
            with self.assertRaises(ValueError):
                load_steps(root)

    def test_native_data_paths_reject_traversal_and_absolute_paths(self):
        for path in (
            "../escape", "/native-data/mod/a.bin", "native-data/mod/../x",
            "native-data/../other/file", "native-data/mod\\file", "native-data/mod/",
        ):
            self.assertFalse(safe_data_path(path), path)
        self.assertTrue(safe_data_path("native-data/mod_menu/tables/index.bin"))

    def test_indexes_generated_files_with_sha256(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            file = output / "native-data" / "mod_menu" / "tables.bin"
            file.parent.mkdir(parents=True)
            file.write_bytes(b"compiled-native-data")
            records = native_data_index(output)
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0]["path"], "native-data/mod_menu/tables.bin")
            self.assertEqual(records[0]["size"], len(b"compiled-native-data"))
            self.assertEqual(len(records[0]["sha256"]), 64)


if __name__ == "__main__":
    unittest.main()
