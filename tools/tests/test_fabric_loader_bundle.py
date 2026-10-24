import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "fabric_java_compat" / "verify_fabric_loader_bundle.py"
SPEC = importlib.util.spec_from_file_location("verify_fabric_loader_bundle", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC is not None and SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class FabricLoaderBundleTests(unittest.TestCase):
    def write_jar(self, path: Path, *, missing_class=None):
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for entry in MODULE.REQUIRED_CLASSES:
                if entry != missing_class:
                    archive.writestr(entry, b"fixture class bytes")
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\\n")
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def test_accepts_loader_with_required_entrypoints_and_api(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "fabric-loader.jar"
            digest = self.write_jar(jar)
            report = MODULE.inspect_archive(jar)
            self.assertEqual(report["loader_version"], "0.19.5")
            self.assertEqual(report["target_minecraft_version"], "26.3")
            self.assertEqual(report["execution_status"], "bundled_verified_not_launched")
            self.assertEqual(report["sha256"], digest)

    def test_rejects_missing_game_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "incomplete.jar"
            self.write_jar(jar, missing_class=MODULE.REQUIRED_CLASSES[1])
            with self.assertRaisesRegex(ValueError, "missing required runtime classes"):
                MODULE.inspect_archive(jar)

    def test_rejects_empty_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "empty.jar"
            jar.write_bytes(b"")
            with self.assertRaisesRegex(ValueError, "missing, empty"):
                MODULE.inspect_archive(jar)

    def test_upstream_hash_is_required_to_be_sha256(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "loader.jar"
            self.write_jar(jar)
            manifest = Path(directory) / "manifest.json"
            import subprocess
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(jar), str(manifest), "--upstream-sha256", "bad"],
                capture_output=True, text=True, check=False
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("upstream SHA-256 must be 64 hexadecimal characters", result.stderr)


if __name__ == "__main__":
    unittest.main()
