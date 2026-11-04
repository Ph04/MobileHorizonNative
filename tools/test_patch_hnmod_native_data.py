import tempfile
import unittest
from pathlib import Path

from patch_hnmod_native_data import patch


SOURCE = '''use std::path::PathBuf;
pub fn built(mod_id: &str) -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    Some(exe.parent()?.join("native-data").join(mod_id))
}
'''


class PatchHnmodNativeDataTests(unittest.TestCase):
    def test_patches_android_lookup_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            target = root / "crates/horizon-client/src/native_data.rs"
            target.parent.mkdir(parents=True)
            target.write_text(SOURCE, encoding="utf-8")
            self.assertTrue(patch(root))
            patched = target.read_text(encoding="utf-8")
            self.assertIn("HORIZON_NATIVE_DATA_ROOT", patched)
            self.assertIn("current_exe()", patched)
            self.assertFalse(patch(root))

    def test_refuses_unexpected_upstream_shape(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            target = root / "crates/horizon-client/src/native_data.rs"
            target.parent.mkdir(parents=True)
            target.write_text("pub fn changed() {}", encoding="utf-8")
            with self.assertRaises(ValueError):
                patch(root)


if __name__ == "__main__":
    unittest.main()
