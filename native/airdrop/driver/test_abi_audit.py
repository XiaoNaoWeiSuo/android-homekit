import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("abi-audit.py")
SPEC = importlib.util.spec_from_file_location("abi_audit", SCRIPT)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def module_image(records):
    names = b"\0.shstrtab\0__versions\0"
    versions = b"".join(struct.pack("<Q", crc) + name.encode().ljust(56, b"\0") for name, crc in records)
    header = bytearray(64)
    header[:7] = b"\x7fELF\x02\x01\x01"
    table = 64 + len(names) + len(versions)
    struct.pack_into("<Q", header, 40, table)
    struct.pack_into("<HHH", header, 58, 64, 3, 1)
    sections = bytes(64)
    sections += struct.pack("<IIQQQQIIQQ", 1, 3, 0, 0, 64, len(names), 0, 0, 1, 0)
    sections += struct.pack("<IIQQQQIIQQ", 11, 1, 0, 0, 64 + len(names), len(versions), 0, 0, 8, 0)
    return bytes(header) + names + versions + sections


class AuditTests(unittest.TestCase):
    def test_detects_crc_conflicts_and_unverified_symbols(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / name for name in ("candidate.ko", "stock.ko", "other.ko")]
            paths[0].write_bytes(module_image([("same", 1), ("changed", 2), ("new", 3)]))
            paths[1].write_bytes(module_image([("same", 1), ("changed", 4)]))
            paths[2].write_bytes(module_image([("same", 9)]))
            result = subprocess.run([sys.executable, str(SCRIPT), *map(str, paths)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 3)
            report = json.loads(result.stdout)
            self.assertEqual(report["mismatched_imports"]["changed"], {"candidate": "0x00000002", "reference": "0x00000004"})
            self.assertEqual(report["unverified_imports"], {"new": "0x00000003"})
            self.assertIn("same", report["conflicting_reference_crcs"])
            self.assertFalse(report["crc_comparison_complete"])
            self.assertFalse(report["loadability_verified"])

    def test_matching_crcs_never_imply_loadability(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "stock.ko"
            path.write_bytes(module_image([("test_symbol", 0xabcdef01)]))
            result = subprocess.run([sys.executable, str(SCRIPT), str(path), str(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0)
            report = json.loads(result.stdout)
            self.assertTrue(report["crc_comparison_complete"])
            self.assertFalse(report["loadability_verified"])

    def test_rejects_truncated_and_duplicate_records(self):
        image = module_image([("symbol", 123)])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "module.ko"
            for length in (0, 7, 63, 64, len(image) - 1):
                path.write_bytes(image[:length])
                with self.assertRaises(ValueError):
                    AUDIT.imported_versions(path)
            path.write_bytes(module_image([("symbol", 1), ("symbol", 2)]))
            with self.assertRaises(ValueError):
                AUDIT.imported_versions(path)

    def test_no_reference_is_an_inventory_only(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "module.ko"
            path.write_bytes(module_image([("symbol", 1)]))
            result = subprocess.run([sys.executable, str(SCRIPT), str(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 3)
            report = json.loads(result.stdout)
            self.assertFalse(report["crc_comparison_complete"])
            self.assertEqual(report["matching_imports"], 0)

    def test_provider_symvers_covers_new_import_without_claiming_loadability(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "module.ko"
            provider = Path(directory) / "provider.symvers"
            path.write_bytes(module_image([("new_symbol", 42)]))
            provider.write_text("0x0000002a\tnew_symbol\tvmlinux\tEXPORT_SYMBOL_GPL\t\n")
            result = subprocess.run([sys.executable, str(SCRIPT), str(path), "--provider-symvers", str(provider)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0)
            report = json.loads(result.stdout)
            self.assertTrue(report["crc_comparison_complete"])
            self.assertFalse(report["loadability_verified"])
            provider.write_text("0x0000002b\tnew_symbol\tvmlinux\tEXPORT_SYMBOL_GPL\t\n")
            result = subprocess.run([sys.executable, str(SCRIPT), str(path), "--provider-symvers", str(provider)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 3)


if __name__ == "__main__":
    unittest.main()
