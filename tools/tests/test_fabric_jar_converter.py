import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.fabric_jar_converter import convert_jar


def _tiny_class() -> bytes:
    # A valid minimal constant pool; the audit needs only the class-file header
    # and constants for this regression fixture.
    return struct.pack(">IHHH", 0xCAFEBABE, 0, 61, 1)


class FabricJarConverterTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.output = self.root / "converted"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def make_jar(self, name: str, *, with_code: bool = False,
                 extra_entries: dict[str, bytes] | None = None,
                 metadata: dict | None = None) -> Path:
        jar = self.root / name
        manifest = metadata or {
            "schemaVersion": 1, "id": "sample_mod", "name": "Sample Mod",
            "version": "1.2.3", "environment": "*",
        }
        with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("fabric.mod.json", json.dumps(manifest))
            if with_code:
                archive.writestr("sample/Entry.class", _tiny_class())
            for path, data in (extra_entries or {}).items():
                archive.writestr(path, data)
        return jar

    def test_resource_only_jar_produces_versioned_resource_and_data_packs(self) -> None:
        jar = self.make_jar("resource-only.jar", extra_entries={
            "assets/sample_mod/lang/en_us.json": b'{"sample_mod.item":"Example"}',
            "assets/sample_mod/textures/item/example.png": b"test texture",
            "data/sample_mod/tags/items/example.json": b'{"values":["minecraft:stone"]}',
        })
        result = convert_jar(jar, self.output)
        self.assertEqual(result["status"], "resource_only")
        self.assertEqual(len(result["converted_outputs"]), 2)

        by_type = {item["type"]: Path(item["path"]) for item in result["converted_outputs"]}
        with zipfile.ZipFile(by_type["resource_pack"]) as resource_pack:
            meta = json.loads(resource_pack.read("pack.mcmeta"))
            self.assertEqual(meta["pack"]["min_format"], [97, 1])
            self.assertIn("assets/sample_mod/lang/en_us.json", resource_pack.namelist())
            self.assertNotIn("data/sample_mod/tags/items/example.json", resource_pack.namelist())
        with zipfile.ZipFile(by_type["data_pack"]) as data_pack:
            meta = json.loads(data_pack.read("pack.mcmeta"))
            self.assertEqual(meta["pack"]["min_format"], [121, 0])
            self.assertIn("data/sample_mod/tags/items/example.json", data_pack.namelist())
            self.assertNotIn("assets/sample_mod/lang/en_us.json", data_pack.namelist())

    def test_code_jar_is_only_partially_converted_and_never_copies_class_files(self) -> None:
        jar = self.make_jar("code-mod.jar", with_code=True, extra_entries={
            "assets/sample_mod/lang/en_us.json": b'{}',
            "data/sample_mod/recipes/example.json": b'{}',
        }, metadata={
            "schemaVersion": 1, "id": "sample_mod", "name": "Sample Mod",
            "version": "1.2.3", "entrypoints": {"main": ["sample.Entry"]},
        })
        result = convert_jar(jar, self.output)
        self.assertEqual(result["status"], "partial_resources_only")
        self.assertEqual(result["source_class_count"], 1)
        self.assertTrue(any("not converted" in warning for warning in result["warnings"]))
        for item in result["converted_outputs"]:
            with zipfile.ZipFile(item["path"]) as output:
                self.assertFalse(any(name.endswith(".class") for name in output.namelist()))
                self.assertIn("pack.mcmeta", output.namelist())

    def test_unsafe_resource_path_is_rejected_without_partial_outputs(self) -> None:
        jar = self.make_jar("traversal.jar", extra_entries={
            "assets/sample_mod/../outside.txt": b"unsafe",
        })
        result = convert_jar(jar, self.output)
        self.assertEqual(result["status"], "error")
        self.assertIn("unsafe archive path", result["error"])
        self.assertFalse(self.output.exists())

    def test_jar_without_assets_or_data_cannot_be_converted(self) -> None:
        jar = self.make_jar("empty.jar")
        result = convert_jar(jar, self.output)
        self.assertEqual(result["status"], "error")
        self.assertIn("no assets/ or data/", result["error"])

    def test_invalid_fabric_id_is_rejected(self) -> None:
        jar = self.make_jar("invalid-id.jar", extra_entries={
            "assets/sample_mod/lang/en_us.json": b"{}",
        }, metadata={"id": "../escape", "version": "1"})
        result = convert_jar(jar, self.output)
        self.assertEqual(result["status"], "error")
        self.assertIn("valid mod ID", result["error"])


if __name__ == "__main__":
    unittest.main()
