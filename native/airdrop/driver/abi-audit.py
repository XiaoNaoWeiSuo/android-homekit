#!/usr/bin/env python3
"""Compare module symbol CRCs with reference modules (ELF64 LE, no deps).

This compares recorded imports, not kernel layouts, CFI types, signatures,
firmware or runtime behavior. It never loads or changes a module.
"""
import argparse
import json
from pathlib import Path
import struct
import sys


def imported_versions(path):
    data = Path(path).read_bytes()
    if data[:7] != b"\x7fELF\x02\x01\x01" or len(data) < 64:
        raise ValueError(f"{path}: expected ELF64 little endian")
    offset = struct.unpack_from("<Q", data, 40)[0]
    size, count, names_index = struct.unpack_from("<HHH", data, 58)
    if size != 64 or not count or names_index >= count or offset + count * size > len(data):
        raise ValueError(f"{path}: invalid section table")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, offset + i * size) for i in range(count)]

    def section_bytes(section):
        start, length = section[4:6]
        if start + length > len(data):
            raise ValueError(f"{path}: truncated section")
        return data[start:start + length]

    names = section_bytes(sections[names_index])
    versions = None
    for section in sections:
        start = section[0]
        end = names.find(b"\0", start)
        if start >= len(names) or end < 0:
            raise ValueError(f"{path}: invalid section name")
        if names[start:end] == b"__versions":
            if versions is not None:
                raise ValueError(f"{path}: duplicate version section")
            versions = section_bytes(section)
    if versions is None or not versions or len(versions) % 64:
        raise ValueError(f"{path}: no complete 64-byte module version records")
    result = {}
    for start in range(0, len(versions), 64):
        crc = struct.unpack_from("<Q", versions, start)[0]
        record = versions[start + 8:start + 64]
        end = record.find(b"\0")
        if end <= 0:
            raise ValueError(f"{path}: invalid version record")
        symbol = record[:end].decode("ascii")
        if symbol in result:
            raise ValueError(f"{path}: duplicate symbol {symbol}")
        result[symbol] = f"0x{crc:08x}"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", help="compiled .ko or reference .ko for an inventory")
    parser.add_argument("references", nargs="*", help="stock .ko files from the same device")
    parser.add_argument("--provider-symvers", action="append", default=[],
                        help="actual provider exports from official GKI or stock-symvers.py")
    args = parser.parse_args()
    candidate = imported_versions(args.candidate)
    references = {}
    conflicts = {}
    for path in args.references:
        for symbol, crc in imported_versions(path).items():
            if symbol in references and references[symbol] != crc:
                conflicts[symbol] = sorted({references[symbol], crc})
            references[symbol] = crc
    for path in args.provider_symvers:
        for line in Path(path).read_text().splitlines():
            columns = line.split()
            if len(columns) not in (4, 5) or columns[3] not in ("EXPORT_SYMBOL", "EXPORT_SYMBOL_GPL"):
                raise ValueError(f"{path}: unsupported provider symvers record")
            crc, symbol = f"0x{int(columns[0], 16):08x}", columns[1]
            if symbol in references and references[symbol] != crc:
                conflicts[symbol] = sorted({references[symbol], crc})
            references[symbol] = crc
    mismatches = {symbol: {"candidate": crc, "reference": references[symbol]}
                  for symbol, crc in candidate.items()
                  if symbol in references and references[symbol] != crc}
    missing = {symbol: crc for symbol, crc in candidate.items() if symbol not in references}
    report = {
        "candidate": args.candidate,
        "import_count": len(candidate),
        "matching_imports": len(candidate) - len(missing) - len(mismatches),
        "mismatched_imports": mismatches,
        "unverified_imports": missing,
        "conflicting_reference_crcs": conflicts,
        "crc_comparison_complete": bool(references) and not missing and not mismatches and not conflicts,
        "loadability_verified": False,
        "limitation": "Import CRCs alone do not verify CFI, layouts, signatures, GKI/vendor ABI or firmware.",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if report["crc_comparison_complete"] else 3


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, struct.error, UnicodeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
