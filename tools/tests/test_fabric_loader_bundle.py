import hashlib
import importlib.util
import subprocess
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
    def write_loader(self, path: Path, missing_class=None):
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for entry in MODULE.REQUIRED_CLASSES:
                if entry != missing_class:
                    archive.writestr(entry, b"fixture class bytes")
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def write_provider(self, path: Path, service=None):
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for entry in MODULE.PROVIDER_CLASSES:
                archive.writestr(entry, b"fixture class bytes")
            archive.writestr(MODULE.PROVIDER_SERVICE, service or MODULE.PROVIDER_NAME)
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def test_accepts_loader_with_required_runtime_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "loader.jar"
            digest = self.write_loader(jar)
            report = MODULE.inspect_archive(jar)
            self.assertEqual(report["loader_version"], "0.19.5")
            self.assertEqual(report["target_minecraft_version"], "26.3")
            self.assertEqual(report["execution_status"], "bundled_verified_not_launched")
            self.assertEqual(report["sha256"], digest)

    def test_rejects_loader_missing_game_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "incomplete.jar"
            self.write_loader(jar, MODULE.REQUIRED_CLASSES[1])
            with self.assertRaisesRegex(ValueError, "missing required runtime classes"):
                MODULE.inspect_archive(jar)

    def test_accepts_provider_service_and_locks_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            provider = Path(directory) / "provider.jar"
            digest = self.write_provider(provider)
            report = MODULE.inspect_provider(provider)
            self.assertEqual(report["provider_service"], MODULE.PROVIDER_NAME)
            self.assertEqual(report["provider_sha256"], digest)

    def test_rejects_unexpected_provider_service(self):
        with tempfile.TemporaryDirectory() as directory:
            provider = Path(directory) / "provider.jar"
            self.write_provider(provider, "unexpected.Provider")
            with self.assertRaisesRegex(ValueError, "unexpected provider"):
                MODULE.inspect_provider(provider)

    def write_dependencies(self, directory: Path, missing_class=None):
        directory.mkdir(parents=True, exist_ok=True)
        for _, filename, required_class in MODULE.DEPENDENCIES:
            with zipfile.ZipFile(directory / filename, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                if required_class != missing_class:
                    archive.writestr(required_class, b"fixture class bytes")

    def test_locks_all_six_official_loader_dependencies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            loader = root / "loader.jar"
            self.write_loader(loader)
            dependencies = root / "deps"
            self.write_dependencies(dependencies)
            report = MODULE.inspect_archive(loader, dependency_dir=dependencies)
            self.assertEqual(len(report["dependencies"]), 6)
            self.assertEqual(
                [item["file"] for item in report["dependencies"]],
                [item[1] for item in MODULE.DEPENDENCIES],
            )
            self.assertEqual(report["dependency_execution_status"], "official_runtime_dependencies_bundled")

    def test_rejects_loader_dependency_missing_required_class(self):
        with tempfile.TemporaryDirectory() as directory:
            dependencies = Path(directory) / "deps"
            self.write_dependencies(dependencies, missing_class=MODULE.DEPENDENCIES[0][2])
            with self.assertRaisesRegex(ValueError, "lacks"):
                MODULE.inspect_dependencies(dependencies)

    def test_rejects_incomplete_dependency_set(self):
        with tempfile.TemporaryDirectory() as directory:
            dependencies = Path(directory) / "deps"
            self.write_dependencies(dependencies)
            (dependencies / MODULE.DEPENDENCIES[0][1]).unlink()
            with self.assertRaisesRegex(ValueError, "incomplete"):
                MODULE.inspect_dependencies(dependencies)

    def test_rejects_invalid_upstream_checksum_format(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "loader.jar"
            self.write_loader(jar)
            manifest = Path(directory) / "manifest.json"
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(jar), str(manifest), "--upstream-sha256", "bad"],
                capture_output=True, text=True, check=False
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("upstream SHA-256 must be 64 hexadecimal characters", result.stderr)

if __name__ == "__main__":
    unittest.main()
