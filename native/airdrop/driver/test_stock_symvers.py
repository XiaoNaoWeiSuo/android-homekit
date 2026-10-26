import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("stock_symvers", Path(__file__).with_name("stock-symvers.py"))
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def image(crc_section=0xfff1, namespace=b"TEST_NS\0", include_export=True):
    section_names = ["", ".shstrtab", ".strtab", ".symtab", "__ksymtab_gpl", "__ksymtab_strings", ".modinfo"]
    shstrings = b"\0"
    offsets = []
    for name in section_names:
        offsets.append(len(shstrings))
        shstrings += name.encode() + b"\0"
    names = ["", "__crc_alpha", "__ksymtab_alpha", "__kstrtabns_alpha"]
    strings, indexes = b"", []
    for name in names:
        indexes.append(len(strings))
        strings += name.encode() + b"\0"
    symbols = bytes(24) + struct.pack("<IBBHQQ", indexes[1], 0x10, 0, crc_section, 0x12345678, 0)
    if include_export:
        symbols += struct.pack("<IBBHQQ", indexes[2], 0x01, 0, 4, 0, 12)
    symbols += struct.pack("<IBBHQQ", indexes[3], 0x01, 0, 5, 0, len(namespace))
    payloads = [b"", shstrings, strings, symbols, bytes(12), namespace, b"name=test_provider\0"]
    data = bytearray(64)
    data[:7] = b"\x7fELF\x02\x01\x01"
    sections = []
    for i, payload in enumerate(payloads):
        start = len(data)
        data.extend(payload)
        sections.append(struct.pack("<IIQQQQIIQQ", offsets[i], 2 if i == 3 else 1, 0, 0,
                                    start, len(payload), 2 if i == 3 else 0, 0, 1, 24 if i == 3 else 0))
    struct.pack_into("<Q", data, 40, len(data))
    struct.pack_into("<HHH", data, 58, 64, len(sections), 1)
    return bytes(data) + b"".join(sections)


class ExportTests(unittest.TestCase):
    def test_recovers_absolute_crc_export_class_and_namespace(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "module.ko"
            path.write_bytes(image())
            self.assertEqual(AUDIT.Module(path).exports(), {
                "alpha": ("0x12345678", "test_provider", "EXPORT_SYMBOL_GPL", "TEST_NS")})

    def test_never_promotes_imported_or_unexported_crc_to_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "module.ko"
            for data in [image(crc_section=0), image(include_export=False)]:
                path.write_bytes(data)
                with self.assertRaises(ValueError):
                    AUDIT.Module(path).exports()

    def test_rejects_truncated_namespace_or_section_table(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "module.ko"
            for data in [image()[:-1], image(namespace=b"UNTERMINATED")]:
                path.write_bytes(data)
                with self.assertRaises(ValueError):
                    AUDIT.Module(path).exports()


if __name__ == "__main__":
    unittest.main()
