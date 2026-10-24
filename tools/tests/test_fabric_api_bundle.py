import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "fabric_java_compat" / "verify_fabric_api_bundle.py"
SPEC = importlib.util.spec_from_file_location("verify_fabric_api_bundle", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC is not None and SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class FabricApiBundleTests(unittest.TestCase):
    def write_bundle(self, path: Path, *, mod_id="fabric-api",
                     version="0.165.0+26.3", nested_path="META-INF/jars/fabric-api-base.jar",
                     include_nested=True):
        metadata = {
            "schemaVersion": 1,
            "id": mod_id,
            "version": version,
            "jars": [{"file": nested_path}],
        }
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("fabric.mod.json", json.dumps(metadata))
            if include_nested:
                archive.writestr(nested_path, b"nested jar fixture bytes")
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def test_accepts_intact_aggregate_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "fabric-api.jar"
            digest = self.write_bundle(jar)
            report = MODULE.inspect_archive(jar)
            self.assertEqual(report["bundle_mode"], "single_original_mod_jar")
            self.assertEqual(report["mod_id"], "fabric-api")
            self.assertEqual(report["nested_module_jars_declared"], 1)
            self.assertEqual(report["sha256"], digest)

    def test_rejects_wrong_mod_id(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "wrong.jar"
            self.write_bundle(jar, mod_id="not-fabric-api")
            with self.assertRaisesRegex(ValueError, "expected mod id"):
                MODULE.inspect_archive(jar)

    def test_rejects_wrong_version(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "wrong.jar"
            self.write_bundle(jar, version="0.164.0+26.3")
            with self.assertRaisesRegex(ValueError, "expected Fabric API version"):
                MODULE.inspect_archive(jar)

    def test_rejects_missing_declared_nested_module(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "missing.jar"
            self.write_bundle(jar, include_nested=False)
            with self.assertRaisesRegex(ValueError, "nested module JAR is missing"):
                MODULE.inspect_archive(jar)

    def test_rejects_traversal_in_nested_jar_path(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "traversal.jar"
            self.write_bundle(jar, nested_path="../outside.jar")
            with self.assertRaisesRegex(ValueError, "unsafe nested JAR path"):
                MODULE.inspect_archive(jar)


if __name__ == "__main__":
    unittest.main()
