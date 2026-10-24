import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.fabric_java_compat.generate_minecraft_abi import REQUIRED_PUBLIC_CLASSES, generate_catalog


class MinecraftAbiCatalogTests(unittest.TestCase):
    def test_rejects_incorrect_sha1_before_scanning(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "not-minecraft.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("fabric.mod.json", "{}")
            with self.assertRaisesRegex(ValueError, "SHA-1 mismatch"):
                generate_catalog(jar, "0" * 40)

    def test_required_class_list_covers_core_26_3_abi(self):
        self.assertIn("net/minecraft/client/Minecraft", REQUIRED_PUBLIC_CLASSES)
        self.assertIn("net/minecraft/world/item/ItemStack", REQUIRED_PUBLIC_CLASSES)
        self.assertIn("net/minecraft/core/BlockPos", REQUIRED_PUBLIC_CLASSES)
        self.assertGreaterEqual(len(REQUIRED_PUBLIC_CLASSES), 6)


if __name__ == "__main__":
    unittest.main()
