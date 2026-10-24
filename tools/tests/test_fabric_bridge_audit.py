import io
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.fabric_bridge_audit import (
    _load_fabric_api_catalog,
    _module_for_api_ref,
    audit_jar,
    parse_class_constant_pool,
)


def _utf8(value: str) -> bytes:
    raw = value.encode("utf-8")
    return b"\x01" + struct.pack(">H", len(raw)) + raw


def _class(index: int) -> bytes:
    return b"\x07" + struct.pack(">H", index)


def _minimal_class(major_version: int = 61) -> bytes:
    pool = b"".join([
        _utf8("net/minecraft/client/Minecraft"),
        _class(1),
        _utf8("net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents"),
        _class(3),
        _utf8("org/spongepowered/asm/mixin/Mixin"),
        _class(5),
        _utf8("(Lnet/minecraft/world/level/Level;)V"),
    ])
    return struct.pack(">IHHH", 0xCAFEBABE, 0, major_version, 8) + pool


class FabricBridgeAuditTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _make_jar(self, path: Path, *, class_data: bytes | None = None,
                  metadata: dict | None = None, entries: dict[str, bytes] | None = None) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            if metadata is not None:
                archive.writestr("fabric.mod.json", json.dumps(metadata))
            if class_data is not None:
                archive.writestr("example/Test.class", class_data)
            for name, value in (entries or {}).items():
                archive.writestr(name, value)
        return path

    def test_constant_pool_extracts_minecraft_fabric_and_mixin_refs(self) -> None:
        parsed = parse_class_constant_pool(_minimal_class())
        refs = parsed["references"]
        self.assertIn("net/minecraft/client/Minecraft", refs)
        self.assertIn(
            "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents",
            refs,
        )
        self.assertIn("org/spongepowered/asm/mixin/Mixin", refs)
        self.assertEqual(parsed["major_version"], 61)

    def test_audit_reports_dependencies_features_and_resource_files(self) -> None:
        path = self._make_jar(
            self.root / "sample.jar",
            class_data=_minimal_class(),
            metadata={
                "schemaVersion": 1,
                "id": "sample_mod",
                "name": "Sample Mod",
                "version": "1.2.3",
                "license": "MIT",
                "depends": {"minecraft": "~26.3", "fabricloader": ">=0.19.3", "fabric-api": "*"},
                "entrypoints": {"client": ["example.ClientInit"]},
                "mixins": ["sample_mod.mixins.json"],
                "accessWidener": "sample_mod.accesswidener",
            },
            entries={
                "sample_mod.mixins.json": b'{"required":true,"package":"sample.mixin","mixins":["CommonMixin"],"client":["ClientMixin"],"injectors":{"defaultRequire":1}}',
                "sample_mod.accesswidener": b"accessWidener v2 named\naccessible class net/minecraft/client/Minecraft\nmutable field net/minecraft/client/Minecraft field_1 Ljava/lang/String;\n",
                "assets/sample_mod/textures/item/icon.png": b"not-a-real-png",
                "data/sample_mod/recipes/example.json": b"{}",
            },
        )
        report = audit_jar(path)
        self.assertEqual(report["metadata"]["id"], "sample_mod")
        self.assertEqual(report["metadata"]["required_dependencies"]["fabric-api"], "*")
        self.assertIn("client", report["metadata"]["entrypoints"])
        self.assertEqual(report["archive"]["resource_counts"]["textures"], 1)
        self.assertEqual(report["archive"]["resource_counts"]["recipes"], 1)
        self.assertEqual(report["archive"]["class_count"], 1)
        self.assertEqual(report["archive"]["scanned_class_count"], 1)
        self.assertGreater(report["code_references"]["minecraft_reference_count"], 0)
        self.assertGreater(report["code_references"]["fabric_api_reference_count"], 0)
        self.assertIn("client_lifecycle", report["code_references"]["feature_evidence_class_counts"])
        self.assertTrue(report["archive"]["mixin_config_paths"])
        self.assertTrue(report["archive"]["access_widener_paths"])
        self.assertTrue(any("Mixin metadata" in warning for warning in report["warnings"]))
        compat = report["fabric_compatibility"]
        self.assertFalse(compat["java_code_invoked"])
        self.assertFalse(compat["bytecode_transformations_applied"])
        self.assertEqual(compat["entrypoints"][0]["status"], "manual-port-required")
        self.assertEqual(compat["mixin_configs"][0]["package"], "sample.mixin")
        self.assertEqual(len(compat["access_wideners"][0]["entries"]), 2)
        self.assertEqual(compat["access_wideners"][0]["entries"][0]["operation"], "accessible")

    def test_data_only_jar_is_only_a_candidate_not_declared_compatible(self) -> None:
        path = self._make_jar(
            self.root / "data-only.jar",
            metadata={"schemaVersion": 1, "id": "data_only", "version": "1.0"},
            entries={
                "assets/data_only/lang/en_us.json": b"{}",
                "data/data_only/tags/items/example.json": b"{}",
            },
        )
        report = audit_jar(path)
        self.assertEqual(report["archive"]["class_count"], 0)
        self.assertIn("resource-only candidate", report["assessment"])
        self.assertEqual(report["archive"]["resource_counts"]["languages"], 1)
        self.assertEqual(report["archive"]["resource_counts"]["tags"], 1)

    def test_bad_class_file_is_reported_without_crashing_the_audit(self) -> None:
        path = self._make_jar(
            self.root / "broken.jar",
            class_data=b"not-a-class",
            metadata={"id": "broken", "version": "1"},
        )
        report = audit_jar(path)
        self.assertEqual(report["archive"]["class_count"], 1)
        self.assertEqual(report["archive"]["scanned_class_count"], 0)
        self.assertEqual(len(report["archive"]["class_scan_failures"]), 1)
        self.assertTrue(any("could not be scanned" in warning for warning in report["warnings"]))


    def test_official_26_3_catalog_maps_correct_public_packages(self) -> None:
        catalog = _load_fabric_api_catalog()
        self.assertEqual(catalog["minecraft_version"], "26.3")
        self.assertEqual(catalog["fabric_api_version"], "0.165.0")
        self.assertGreaterEqual(catalog["declared_modules_count"], 40)
        self.assertGreaterEqual(catalog["public_api_class_count"], 400)
        cases = {
            "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents":
                "fabric-lifecycle-events-v1",
            "net/fabricmc/fabric/api/blockgetter/v2/FabricBlockGetter":
                "fabric-block-getter-api-v2",
            "net/fabricmc/fabric/api/client/renderer/v1/Renderer":
                "fabric-renderer-api-v1",
            "net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin":
                "fabric-model-loading-api-v1",
            "net/fabricmc/fabric/api/creativetab/v1/FabricCreativeModeTab":
                "fabric-creative-tab-api-v1",
        }
        for reference, expected in cases.items():
            with self.subTest(reference=reference):
                self.assertEqual(_module_for_api_ref(reference, catalog), expected)

    def test_report_classifies_api_reference_by_official_module(self) -> None:
        path = self._make_jar(
            self.root / "fabric-api-use.jar",
            class_data=_minimal_class(),
            metadata={"schemaVersion": 1, "id": "api_user", "version": "1.0.0"},
        )
        report = audit_jar(path)
        coverage = report["code_references"]["fabric_api_catalog"]
        self.assertEqual(coverage["fabric_api_version"], "0.165.0")
        self.assertIn(
            "fabric-lifecycle-events-v1",
            coverage["detected_modules_by_reference_count"],
        )
        self.assertEqual(report["schema_version"], 2)

    def test_declared_nested_jar_is_inspected_without_extraction(self) -> None:
        nested_buffer = io.BytesIO()
        with zipfile.ZipFile(nested_buffer, "w", compression=zipfile.ZIP_DEFLATED) as nested:
            nested.writestr("lib/Dependency.class", _minimal_class())
        nested_jar = nested_buffer.getvalue()
        path = self._make_jar(
            self.root / "nested-parent.jar",
            metadata={
                "schemaVersion": 1, "id": "nested_parent", "version": "1.0.0",
                "jars": [{"file": "META-INF/jars/dependency.jar"}],
            },
            entries={"META-INF/jars/dependency.jar": nested_jar},
        )
        report = audit_jar(path)
        nested = report["archive"]["nested_jars"]
        self.assertEqual(nested["declared_count"], 1)
        self.assertEqual(nested["inspected_count"], 1)
        self.assertEqual(nested["class_count"], 1)
        self.assertEqual(nested["entries"][0]["status"], "inspected")
        self.assertIn("fabric-lifecycle-events-v1", nested["entries"][0]["fabric_api_modules"])
        self.assertFalse(nested["java_code_invoked"])
        self.assertNotIn("resource-only candidate", report["assessment"])

    def test_future_bytecode_version_is_flagged_against_java_25_target(self) -> None:
        path = self._make_jar(
            self.root / "future-java.jar",
            class_data=_minimal_class(major_version=70),
            metadata={"schemaVersion": 1, "id": "future_java", "version": "1.0.0"},
        )
        report = audit_jar(path)
        compatibility = report["archive"]["java_version_compatibility"]
        self.assertEqual(compatibility["max_class_major"], 70)
        self.assertFalse(compatibility["compatible_with_target"])
        self.assertTrue(any("exceeds Java 25" in warning for warning in report["warnings"]))

    def test_unsafe_nested_jar_declaration_is_reported(self) -> None:
        path = self._make_jar(
            self.root / "unsafe-nested.jar",
            metadata={
                "schemaVersion": 1, "id": "unsafe_nested", "version": "1.0.0",
                "jars": [{"file": "../outside.jar"}],
            },
        )
        report = audit_jar(path)
        self.assertEqual(report["archive"]["nested_jars"]["entries"][0]["status"], "unsafe-path")
        self.assertTrue(any("unsafe nested JAR path" in warning for warning in report["warnings"]))

    def test_non_jar_returns_a_structured_error(self) -> None:
        path = self.root / "invalid.jar"
        path.write_bytes(b"no zip here")
        report = audit_jar(path)
        self.assertIn("error", report)


if __name__ == "__main__":
    unittest.main()
