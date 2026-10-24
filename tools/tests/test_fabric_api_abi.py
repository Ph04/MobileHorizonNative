import struct
import unittest
from tools.fabric_java_compat.generate_fabric_api_abi import parse_class_api

def utf8(value: str) -> bytes:
    raw = value.encode("utf-8")
    return b"\x01" + struct.pack(">H", len(raw)) + raw

def clazz(index: int) -> bytes:
    return b"\x07" + struct.pack(">H", index)

def minimal_public_class() -> bytes:
    # Constant pool: 1 class name, 2 Class(1), 3 Object name, 4 Class(3),
    # 5 field name, 6 field descriptor, 7 method name, 8 method descriptor.
    cp = b"".join([
        utf8("example/AbiFixture"), clazz(1), utf8("java/lang/Object"), clazz(3),
        utf8("count"), utf8("I"), utf8("tick"), utf8("()V"),
    ])
    out = bytearray(struct.pack(">IHHH", 0xCAFEBABE, 0, 61, 9) + cp)
    out += struct.pack(">HHHH", 0x0021, 2, 4, 0)
    out += struct.pack(">H", 1) + struct.pack(">HHHH", 0x0001, 5, 6, 0)
    out += struct.pack(">H", 1) + struct.pack(">HHHH", 0x0001, 7, 8, 0)
    out += struct.pack(">H", 0)
    return bytes(out)

class FabricApiAbiParserTests(unittest.TestCase):
    def test_extracts_exact_public_jvm_descriptors(self):
        api = parse_class_api(minimal_public_class(), "fixture-module")
        self.assertEqual(api["name"], "example.AbiFixture")
        self.assertEqual(api["module"], "fixture-module")
        self.assertEqual(api["major_version"], 61)
        self.assertEqual([(x["kind"], x["name"], x["descriptor"]) for x in api["fields"]],
                         [("field", "count", "I")])
        self.assertEqual([(x["kind"], x["name"], x["descriptor"]) for x in api["methods"]],
                         [("method", "tick", "()V")])

    def test_rejects_truncated_class(self):
        with self.assertRaisesRegex(ValueError, "truncated"):
            parse_class_api(b"\xca\xfe\xba\xbe", "fixture-module")

if __name__ == "__main__":
    unittest.main()
