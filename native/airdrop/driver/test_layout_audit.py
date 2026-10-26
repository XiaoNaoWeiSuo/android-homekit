import importlib.util
from pathlib import Path
import struct
import unittest

SPEC = importlib.util.spec_from_file_location("layout_audit", Path(__file__).with_name("layout-audit.py"))
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class LayoutTests(unittest.TestCase):
    def test_dwarf4_little_endian_bit_offset(self):
        source = '''0x000001: DW_TAG_structure_type
  DW_AT_name ("sk_buff")
  DW_AT_byte_size (0xf0)
0x000002:   DW_TAG_member
  DW_AT_name ("fclone")
  DW_AT_byte_size (0x01)
  DW_AT_bit_size (0x02)
  DW_AT_bit_offset (0x04)
  DW_AT_data_member_location (0x7e)
'''
        name, record = AUDIT.dwarf_record(source.splitlines(True))
        self.assertEqual(name, "sk_buff")
        self.assertEqual(record["members"], [{"name": "fclone", "bit_offset": 1010, "bit_size": 2}])

    def test_dwarf5_absolute_bit_offset(self):
        source = '''0x000001: DW_TAG_structure_type
  DW_AT_name ("flags")
  DW_AT_byte_size (0x04)
0x000002:   DW_TAG_member
  DW_AT_name ("enabled")
  DW_AT_bit_size (0x01)
  DW_AT_data_bit_offset (0x09)
'''
        _, record = AUDIT.dwarf_record(source.splitlines(True))
        self.assertEqual(record["members"][0]["bit_offset"], 9)

    def test_btf_bounds_and_bitfield_offsets(self):
        strings = b"\0flags\0enabled\0"
        payload = struct.pack("<III", 1, 0x84000001, 4) + struct.pack("<III", 7, 0, (1 << 24) | 9)
        header = struct.pack("<HBBIIIII", 0xeb9f, 1, 0, 24, 0, len(payload), len(payload), len(strings))
        data = header + payload + strings
        record = AUDIT.btf_layouts(data, {"flags"})["flags"]
        self.assertEqual(record["members"], [{"name": "enabled", "bit_offset": 9, "bit_size": 1}])
        for truncated in [data[:23], data[:-1], header + payload[:-1]]:
            with self.assertRaises(ValueError):
                AUDIT.btf_layouts(truncated, {"flags"})

    def test_equal_size_does_not_hide_shifted_member(self):
        compiled = {"module": {"size": 960, "members": [{"name": "refcnt", "bit_offset": 7040}]}}
        running = {"module": {"size": 960, "members": [{"name": "refcnt", "bit_offset": 7168}]}}
        report = AUDIT.compare(compiled, running, {"module", "missing_type"})
        self.assertIn("refcnt", report["module"]["offset_mismatches"])
        self.assertTrue(report["missing_type"]["missing"])

    def test_anonymous_kabi_alias_must_have_the_same_offset(self):
        member = {"name": "reserved", "bit_offset": 64}
        compiled = {"wiphy": {"size": 16, "members": [member]}}
        running = {"wiphy": {"size": 16, "members": [{"name": None, "bit_offset": 64}],
                             "member_positions_including_anonymous": {"reserved": member}}}
        result = AUDIT.compare(compiled, running, {"wiphy"})["wiphy"]
        self.assertTrue(result["compiled_member_positions_match"])
        running["wiphy"]["member_positions_including_anonymous"] = {
            "reserved": {"name": "reserved", "bit_offset": 72}}
        self.assertFalse(AUDIT.compare(compiled, running, {"wiphy"})["wiphy"]["compiled_member_positions_match"])

    def test_llvm_bitfield_offsets_and_forward_declarations(self):
        source = '''!1 = !DICompositeType(tag: DW_TAG_structure_type, name: "flags", size: 32, elements: !2)
!2 = !{!3}
!3 = !DIDerivedType(tag: DW_TAG_member, name: "enabled", size: 1, offset: 9, flags: DIFlagBitField)
!4 = !DICompositeType(tag: DW_TAG_structure_type, name: "opaque", flags: DIFlagFwdDecl)
'''
        roots = AUDIT.llvm_layouts(source, {"flags", "opaque"})
        self.assertEqual(roots["flags"], {"size": 4, "members": [{"name": "enabled", "bit_offset": 9, "bit_size": 1}]})
        self.assertNotIn("opaque", roots)
        with self.assertRaises(ValueError):
            AUDIT.llvm_layouts(source.replace("elements: !2", "elements: !99"), {"flags"})


if __name__ == "__main__":
    unittest.main()
