#!/usr/bin/env python3
"""Unit tests for native Android mod build selection."""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

TOOL = Path(__file__).with_name("build_horizon_native_android_runtime.py")
SPEC = importlib.util.spec_from_file_location("native_android_builder", TOOL)
assert SPEC and SPEC.loader
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class NativeModSelectionTests(unittest.TestCase):
    def write_selection(self, text: str) -> Path:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name) / "enabled-mods.txt"
        path.write_text(text, encoding="utf-8")
        return path

    def test_only_true_entries_are_selected(self) -> None:
        path = self.write_selection(
            "# Same convention as the PC launcher's horizon-mods.txt\n"
            "mod.mod_menu:true\n"
            "mod.map_overlay:false\n"
            "mod.fps_counter:true\n"
        )
        self.assertEqual(
            {"mod_menu", "fps_counter"},
            BUILDER.read_enabled_mod_ids(path),
        )

    def test_empty_selection_is_valid_and_means_no_native_runtime(self) -> None:
        path = self.write_selection("# All native mods disabled\nmod.mod_menu:false\n")
        self.assertEqual(set(), BUILDER.read_enabled_mod_ids(path))

    def test_rejects_unknown_keys_invalid_states_and_duplicate_ids(self) -> None:
        for text in (
            "resourcepack.mod_menu:true\n",
            "mod.mod_menu:yes\n",
            "mod.mod_menu:true\nmod.mod_menu:false\n",
            "mod.invalid id:true\n",
            "mod.mod_menu:true:extra\n",
        ):
            with self.subTest(text=text):
                path = self.write_selection(text)
                with self.assertRaises(BUILDER.BundleError):
                    BUILDER.read_enabled_mod_ids(path)

    def test_requires_selection_file_when_requested(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            missing = Path(temp) / "missing.txt"
            with self.assertRaises(BUILDER.BundleError):
                BUILDER.read_enabled_mod_ids(missing)


if __name__ == "__main__":
    unittest.main(verbosity=2)
