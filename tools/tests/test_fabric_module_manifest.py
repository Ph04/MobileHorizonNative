import json
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MANIFEST=ROOT/"fabric_java_compat"/"modules"/"module-manifest.json"
NATURES=ROOT/"fabric_java_compat"/"modules"/"naturescompass-26.3.json"
VALID={"implemented","native-backend-only","preflight-only","contract-only","blocked"}
class FabricModuleManifestTests(unittest.TestCase):
 def test_modules_have_unique_ids_known_statuses_and_declared_dependencies(self):
  data=json.loads(MANIFEST.read_text(encoding="utf-8"));mods=data["modules"];ids=[m["id"] for m in mods];self.assertEqual(len(ids),len(set(ids)));by_id={m["id"]:m for m in mods}
  for m in mods:
   self.assertIn(m["status"],VALID)
   for dep in m["requires"]:self.assertIn(dep,by_id)
  self.assertEqual(data["target"]["minecraft"],"26.3");self.assertEqual(data["profile"],"fabric-bridge")
 def test_natures_compass_stays_blocked(self):
  d=json.loads(NATURES.read_text(encoding="utf-8"));self.assertEqual(d["mod"]["id"],"naturescompass");self.assertEqual(d["mod"]["jar_sha256"],"ea2ec4407489fea9662759c1693ca911ed3f6cdf7b558102e6ecbe77d4c97618");self.assertEqual(d["compatibility_status"],"BLOCKED")
  statuses={m["module"]:m["status"] for m in d["required_modules"]};self.assertEqual(statuses["world-biomes"],"native-backend-only");self.assertEqual(statuses["mixin-adapters"],"blocked")
if __name__=="__main__":unittest.main()
