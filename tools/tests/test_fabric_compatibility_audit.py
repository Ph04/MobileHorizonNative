import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.fabric_java_compat.audit_mod_compat import audit_mod


def minimal_class(name="test/Fixture", references=("net/minecraft/world/level/Level",)):
    utf8 = lambda value: b"\x01" + struct.pack(">H", len(value.encode())) + value.encode()
    class_info = lambda index: b"\x07" + struct.pack(">H", index)
    strings = [name, "java/lang/Object", *references]
    cp = []
    for value in strings:
        cp.append(utf8(value))
        cp.append(class_info(len(cp) + 1))
    cp_count = len(cp) + 1
    return (
        b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 69, cp_count)
        + b"".join(cp)
        + struct.pack(">HHHHHH", 0x0021, 2, 4, 0, 0, 0)
    )


class FabricCompatibilityAuditTests(unittest.TestCase):
    def test_finds_minecraft_fabric_mixins_and_access_wideners(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            jar = root / "fixture.jar"
            metadata = {
                "schemaVersion": 1,
                "id": "fixturemod",
                "version": "1.0.0",
                "entrypoints": {"main": "test.Fixture"},
                "depends": {"minecraft": "~26.3", "fabric-api": "*"},
                "mixins": ["fixture.mixins.json"],
                "accessWidener": "fixture.accesswidener",
            }
            with zipfile.ZipFile(jar, "w") as out:
                out.writestr("fabric.mod.json", json.dumps(metadata))
                out.writestr("test/Fixture.class", minimal_class(references=(
                    "net/minecraft/world/level/Level",
                    "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking",
                )))
                out.writestr("fixture.mixins.json", json.dumps({
                    "required": True, "package": "test.mixin",
                    "mixins": ["ServerMixin"], "client": ["HudMixin"],
                }))
                out.writestr("fixture.accesswidener",
                    "accessWidener v2 official\naccessible class net/minecraft/world/level/Level\n")
            surface = root / "surface.json"
            surface.write_text(json.dumps({
                "target_minecraft": "26.3",
                "implemented_minecraft_classes": [],
                "implemented_fabric_api_classes": [],
                "native_bridge_operations": ["world.snapshot"],
                "native_events": ["client_tick"],
            }))
            report = audit_mod(jar, shim_manifest=surface)
            self.assertEqual(report["mod"]["id"], "fixturemod")
            self.assertEqual(report["mod"]["max_class_major"], 69)
            self.assertIn("net.minecraft.world.level.Level", report["missing_minecraft_java_shims"])
            self.assertIn("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking",
                          report["missing_fabric_api_adapters"])
            self.assertEqual(len(report["mixins"][0]["declared_mixins"]), 2)
            self.assertEqual(len(report["access_widener_entries"]), 1)
            self.assertEqual(report["compatibility_status"], "BLOCKED")
            self.assertFalse(report["executed_mod_code"])

    def test_rejects_unsafe_nested_jar_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            jar = Path(tmp) / "fixture.jar"
            with zipfile.ZipFile(jar, "w") as out:
                out.writestr("fabric.mod.json", json.dumps({
                    "schemaVersion": 1, "id": "fixturemod", "version": "1",
                    "jars": [{"file": "../outside.jar"}],
                }))
            with self.assertRaisesRegex(ValueError, "unsafe nested JAR path"):
                audit_mod(jar)


if __name__ == "__main__":
    unittest.main()
