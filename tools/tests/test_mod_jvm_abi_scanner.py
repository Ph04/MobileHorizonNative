import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.fabric_java_compat.scan_mod_jvm_abi import parse_class_file, scan_mod


def _utf8(value):
    raw=value.encode("utf-8")
    return b"\x01"+struct.pack(">H",len(raw))+raw

def _class(index):
    return b"\x07"+struct.pack(">H",index)

def _name_type(name_index, descriptor_index):
    return b"\x0c"+struct.pack(">HH",name_index,descriptor_index)

def _member_ref(tag, class_index, name_type_index):
    return bytes((tag,))+struct.pack(">HH",class_index,name_type_index)

def synthetic_class():
    cp=[
        _utf8("test/Fixture"), _class(1),
        _utf8("java/lang/Object"), _class(3),
        _utf8("net/minecraft/world/level/Level"), _class(5),
        _utf8("getBiome"), _utf8("(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome;"),
        _name_type(7,8), _member_ref(10,6,9),
        _utf8("net/fabricmc/fabric/api/event/Event"), _class(11),
        _utf8("register"), _utf8("()V"), _name_type(13,14), _member_ref(11,12,15),
    ]
    count=len(cp)+1
    return (b"\xca\xfe\xba\xbe"+struct.pack(">HHH",0,69,count)+b"".join(cp)
            +struct.pack(">HHHHHH",0x0021,2,4,0,0,0))

class ModJvmAbiScannerTests(unittest.TestCase):
    def test_parser_extracts_field_method_owner_and_descriptor_types(self):
        row=parse_class_file(synthetic_class(),"fixture.class")
        self.assertEqual(row["class_name"],"test.Fixture")
        refs={(v["owner"],v["name"],v["descriptor"],v["kind"]) for v in row["member_references"]}
        self.assertIn(("net.minecraft.world.level.Level","getBiome",
                       "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome;","method"),refs)
        self.assertIn(("net.fabricmc.fabric.api.event.Event","register","()V","interface_method"),refs)
        self.assertIn("net/minecraft/core/BlockPos",row["class_references_internal"])

    def test_jar_scan_never_executes_mod_code_and_reports_public_abi_members(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            jar=root/"fixture.jar"
            with zipfile.ZipFile(jar,"w") as archive:
                archive.writestr("fabric.mod.json",json.dumps({"schemaVersion":1,"id":"fixturemod","version":"1.0.0"}))
                archive.writestr("test/Fixture.class",synthetic_class())
            mc=root/"mc.json"
            mc.write_text(json.dumps({"classes":[
                {"name":"net.minecraft.world.level.Level","internal_name":"net/minecraft/world/level/Level",
                 "methods":[{"kind":"method","name":"getBiome","descriptor":"(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome;"}]},
            ]}),encoding="utf-8")
            api=root/"api.json"
            api.write_text(json.dumps({"classes":[
                {"name":"net.fabricmc.fabric.api.event.Event","internal_name":"net/fabricmc/fabric/api/event/Event",
                 "methods":[{"kind":"method","name":"register","descriptor":"()V"}]},
            ]}),encoding="utf-8")
            report=scan_mod(jar,mc,api)
            self.assertEqual(report["mod"]["id"],"fixturemod")
            self.assertFalse(report["executed_mod_code"])
            self.assertEqual(report["official_abi"]["mc_member_refs_not_in_public_abi"],[])
            self.assertEqual(report["official_abi"]["fabric_member_refs_not_in_public_abi"],[])

    def test_truncated_class_file_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_class_file(b"\xca\xfe\xba\xbe\x00","truncated.class")

if __name__=="__main__":
    unittest.main()
