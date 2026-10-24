import hashlib
import io
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.fabric_java_compat.build_fabric_target_map import build_target_map, scan_fabric_api_jar


def _utf8(value):
    raw = value.encode("utf-8")
    return b"\x01" + struct.pack(">H", len(raw)) + raw


def _clazz(index):
    return b"\x07" + struct.pack(">H", index)


def _name_type(name_index, descriptor_index):
    return b"\x0c" + struct.pack(">HH", name_index, descriptor_index)


def _method_ref(class_index, name_type_index):
    return b"\x0a" + struct.pack(">HH", class_index, name_type_index)


def minimal_class(source_name):
    # CP: source Class, Object Class, Level Class, getBiome name/descriptor,
    # NameAndType and Methodref. The parser deliberately only needs the header.
    entries = [
        _utf8(source_name), _clazz(1),
        _utf8("java/lang/Object"), _clazz(3),
        _utf8("net/minecraft/world/level/Level"), _clazz(5),
        _utf8("getBiome"),
        _utf8("()Lnet/minecraft/world/level/biome/Biome;"),
        _name_type(7, 8), _method_ref(6, 9),
    ]
    cp = b"".join(entries)
    # There are 10 occupied constant-pool entries plus slot zero.
    return (struct.pack(">IHHH", 0xCAFEBABE, 0, 61, 11) + cp
            + struct.pack(">HHHH", 0x0021, 2, 4, 0))


def make_api_jar(path):
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("fabric.mod.json", json.dumps({
            "schemaVersion": 1, "id": "fabric-biome-api-v1", "version": "1.0",
            "accessWidener": "fabric.accesswidener", "mixins": ["fabric.mixins.json"]
        }))
        archive.writestr("fabric.accesswidener",
                         "accessWidener v2 named\n"
                         "accessible class net/minecraft/world/level/Level\n"
                         "accessible method net/minecraft/world/level/Level getBiome ()Lnet/minecraft/world/level/biome/Biome;\n")
        archive.writestr("fabric.mixins.json", json.dumps({
            "package": "net.fabricmc.fabric.mixin.biome",
            "mixins": ["ExampleMixin"]
        }))
        archive.writestr("net/fabricmc/fabric/api/biome/v1/BiomeAccess.class",
                         minimal_class("net/fabricmc/fabric/api/biome/v1/BiomeAccess"))
        archive.writestr("net/fabricmc/fabric/mixin/biome/ExampleMixin.class",
                         minimal_class("net/fabricmc/fabric/mixin/biome/ExampleMixin"))
    inner_bytes = inner.getvalue()
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("fabric.mod.json", json.dumps({
            "schemaVersion": 1, "id": "fabric-api", "version": "0.165.0+26.3",
            "jars": [{"file": "META-INF/jars/fabric-biome-api-v1.jar"}]
        }))
        archive.writestr("META-INF/jars/fabric-biome-api-v1.jar", inner_bytes)


class FabricTargetMapTests(unittest.TestCase):
    def test_scans_minecraft_class_and_exact_member_references(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "fabric-api.jar"
            make_api_jar(jar)
            scanned = scan_fabric_api_jar(jar)
            member_refs = list(scanned["member_refs"])
            self.assertTrue(any(
                row[2] == "net/minecraft/world/level/Level"
                and row[3] == "getBiome"
                and row[4] == "()Lnet/minecraft/world/level/biome/Biome;"
                for row in member_refs
            ))
            self.assertTrue(any(row[2] == "net/minecraft/world/level/biome/Biome"
                                for row in scanned["class_refs"]))

    def test_builds_target_map_with_native_markers_and_aw_mixins(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "fabric-api.jar"
            make_api_jar(jar)
            aggregate_sha = hashlib.sha256(jar.read_bytes()).hexdigest()
            minecraft_abi = {
                "target_minecraft": "26.3", "client_sha1": "a" * 40, "client_sha256": "b" * 64,
                "classes": [
                    {"name": "net.minecraft.world.level.Level",
                     "internal_name": "net/minecraft/world/level/Level",
                     "fields": [], "methods": [
                         {"kind": "method", "name": "getBiome",
                          "descriptor": "()Lnet/minecraft/world/level/biome/Biome;"}
                     ]},
                    {"name": "net.minecraft.world.level.biome.Biome",
                     "internal_name": "net/minecraft/world/level/biome/Biome",
                     "fields": [], "methods": []},
                ],
            }
            fabric_abi = {
                "target_minecraft": "26.3", "fabric_api_version": "0.165.0+26.3",
                "aggregate_sha256": aggregate_sha, "public_class_count": 1,
                "public_member_count": 1, "modules": [{"id": "fabric-biome-api-v1"}],
                "classes": [{"name": "net.fabricmc.fabric.api.biome.v1.BiomeAccess",
                             "module": "fabric-biome-api-v1", "fields": [],
                             "methods": [{"name": "query", "descriptor": "()V"}]}],
            }
            module_manifest = {
                "profile": "fabric-bridge", "target": {"minecraft": "26.3"},
                "modules": [
                    {"id": "world-biomes", "status": "native-backend-only"},
                    {"id": "mixin-adapters", "status": "blocked"},
                    {"id": "bridge-protocol", "status": "implemented"},
                    {"id": "diagnostics", "status": "implemented"},
                ],
            }
            shim = {"schema_version": 1, "native_bridge_operations": ["fabric.biome.search"]}
            targets = {
                "target_minecraft": "26.3",
                "infrastructure_marker_ids": ["HZN-FABRIC-BRIDGE-001", "HZN-FABRIC-DIAG-001"],
                "targets": [
                    {"id": "HZN-FABRIC-BRIDGE-001", "module_id": "bridge-protocol",
                     "repository_source_path": "native_overlay/mod.rs", "horizon_path": "runtime/mod.rs",
                     "fabric_module_tokens": [], "match_source_features": []},
                    {"id": "HZN-FABRIC-BIOME-001", "module_id": "world-biomes",
                     "repository_source_path": "native_overlay/biome.rs", "horizon_path": "runtime.rs",
                     "fabric_module_tokens": ["fabric-biome-api"], "match_source_features": []},
                    {"id": "HZN-FABRIC-MIXIN-001", "module_id": "mixin-adapters",
                     "repository_source_path": "native_overlay/mixin.rs", "horizon_path": "runtime/mixin.rs",
                     "fabric_module_tokens": [], "match_source_features": ["mixin", "access_widener"]},
                    {"id": "HZN-FABRIC-DIAG-001", "module_id": "diagnostics",
                     "repository_source_path": "native_overlay/capabilities.rs", "horizon_path": "runtime/capabilities.rs",
                     "fabric_module_tokens": [], "match_source_features": []},
                ],
            }
            report = build_target_map(jar, minecraft_abi, fabric_abi, module_manifest, shim, targets)
            self.assertFalse(report["bytecode_executed"])
            self.assertGreaterEqual(report["summary"]["mixin_config_count"], 1)
            self.assertGreaterEqual(report["summary"]["access_widener_reference_count"], 2)
            biome_refs = [row for row in report["references"]
                          if row["target_class_internal"] == "net/minecraft/world/level/Level"
                          and row["target_member"] == "getBiome"]
            self.assertTrue(biome_refs)
            self.assertTrue(any("HZN-FABRIC-BIOME-001" in row["marker_ids"] for row in biome_refs))
            aw_refs = [row for row in report["references"] if row["target_kind"] == "access_widener_method"]
            self.assertTrue(aw_refs)
            self.assertTrue(any("HZN-FABRIC-MIXIN-001" in row["marker_ids"] for row in aw_refs))
            self.assertTrue(all(row["exact_native_binding_verified"] is False
                                for row in report["references"] if row["native_candidates"]))

    def test_rejects_catalog_version_mismatch(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "fabric-api.jar"
            make_api_jar(jar)
            with self.assertRaisesRegex(ValueError, "both target Minecraft 26.3"):
                build_target_map(jar, {"target_minecraft": "26.2"}, {"target_minecraft": "26.3"},
                                 {}, {}, {})


if __name__ == "__main__":
    unittest.main()
