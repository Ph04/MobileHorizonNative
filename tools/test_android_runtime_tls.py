import unittest
from check_android_runtime_tls import find_tls_requirements

class DynamicTlsAuditTests(unittest.TestCase):
    def test_normal_dynamic_relocations_pass(self):
        relocs = """
Relocation section '.rela.dyn' contains 2 entries:
0000000000000000  R_AARCH64_RELATIVE
0000000000000008  R_AARCH64_GLOB_DAT
"""
        headers = """
Program Headers:
  LOAD           0x000000 0x0000000000000000 0x0000000000000000
  DYNAMIC        0x010000 0x0000000000010000 0x0000000000010000
"""
        self.assertEqual(find_tls_requirements(relocs, headers), [])

    def test_initial_exec_tls_relocation_is_rejected(self):
        relocs = "0000000000000000  R_AARCH64_TLS_TPREL64  0000000000000000  (null)"
        self.assertEqual(find_tls_requirements(relocs, ""), ["R_AARCH64_TLS_TPREL64"])

    def test_tls_descriptor_relocation_is_rejected(self):
        relocs = "0000000000000000  R_AARCH64_TLSDESC  0000000000000000  symbol"
        self.assertEqual(find_tls_requirements(relocs, ""), ["R_AARCH64_TLSDESC"])

    def test_pt_tls_segment_is_rejected(self):
        headers = "  TLS            0x000100 0x0000000000010000 0x0000000000010000"
        self.assertEqual(
            find_tls_requirements("", headers),
            ["PT_TLS program header (ELF TLS segment)"],
        )

    def test_no_dynamic_tls_requirements_pass(self):
        self.assertEqual(find_tls_requirements("", ""), [])

if __name__ == "__main__":
    unittest.main()
