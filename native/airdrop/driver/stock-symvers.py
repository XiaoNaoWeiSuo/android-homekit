#!/usr/bin/env python3
"""Recover provider exports from stock ELF modules and an official GKI symvers.

Does not promote import records to exports or change CRCs. The resulting
symvers is an input to modpost, not proof that candidate headers match.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct


class Module:
    def __init__(self, path):
        self.path = Path(path)
        self.data = self.path.read_bytes()
        if len(self.data) < 64 or self.data[:7] != b"\x7fELF\x02\x01\x01":
            raise ValueError(f"{path}: expected ELF64 little endian")
        offset = struct.unpack_from("<Q", self.data, 40)[0]
        size, count, names = struct.unpack_from("<HHH", self.data, 58)
        if size != 64 or not count or names >= count or offset + count * size > len(self.data):
            raise ValueError(f"{path}: invalid section table")
        self.sections = [struct.unpack_from("<IIQQQQIIQQ", self.data, offset + i * size) for i in range(count)]
        strings = self.bytes(names)
        self.names = [self.string(strings, section[0]) for section in self.sections]

    @staticmethod
    def string(data, start):
        end = data.find(b"\0", start)
        if start >= len(data) or end < 0:
            raise ValueError("invalid ELF string")
        return data[start:end].decode("ascii")

    def bytes(self, index):
        if index >= len(self.sections):
            raise ValueError("invalid section index")
        section = self.sections[index]
        offset, size = section[4:6]
        if section[1] == 8 or offset + size > len(self.data):
            raise ValueError("section has no complete file contents")
        return self.data[offset:offset + size]

    def exports(self):
        index = self.names.index(".symtab")
        table = self.bytes(index)
        strings = self.bytes(self.sections[index][6])
        if self.sections[index][9] != 24 or len(table) % 24:
            raise ValueError("invalid ELF symbol table")
        symbols = {}
        for offset in range(0, len(table), 24):
            name, info, other, section, value, size = struct.unpack_from("<IBBHQQ", table, offset)
            name = self.string(strings, name)
            if name.startswith(("__crc_", "__ksymtab_", "__kstrtabns_")):
                if name in symbols:
                    raise ValueError(f"duplicate ELF symbol {name}")
                symbols[name] = (section, value)
        module_info = self.bytes(self.names.index(".modinfo"))
        identities = [item[5:].decode("ascii") for item in module_info.split(b"\0") if item.startswith(b"name=")]
        if len(identities) != 1:
            raise ValueError("missing or duplicate module identity")
        result = {}
        for name, (section, crc) in symbols.items():
            if not name.startswith("__crc_"):
                continue
            symbol = name[6:]
            # Only absolute CRCs from a real export entry are authoritative.
            if section != 0xfff1 or crc > 0xffffffff or "__ksymtab_" + symbol not in symbols:
                raise ValueError(f"{self.path}: unsupported CRC representation for {symbol}")
            export_section, _ = symbols["__ksymtab_" + symbol]
            if export_section >= len(self.sections):
                raise ValueError("invalid export section")
            export_type = self.names[export_section]
            if export_type not in ("__ksymtab", "__ksymtab_gpl"):
                raise ValueError(f"unsupported export class {export_type}")
            if "__kstrtabns_" + symbol not in symbols:
                raise ValueError(f"missing namespace record for {symbol}")
            ns_section, ns_offset = symbols["__kstrtabns_" + symbol]
            namespace = self.string(self.bytes(ns_section), ns_offset)
            result[symbol] = (f"0x{crc:08x}", identities[0],
                              "EXPORT_SYMBOL_GPL" if export_type.endswith("_gpl") else "EXPORT_SYMBOL", namespace)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gki", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--provenance", required=True)
    parser.add_argument("modules", nargs="+")
    args = parser.parse_args()
    records = {}
    origins = {}
    for line in Path(args.gki).read_text().splitlines():
        columns = line.split()
        if len(columns) not in (4, 5) or columns[3] not in ("EXPORT_SYMBOL", "EXPORT_SYMBOL_GPL"):
            raise ValueError("unsupported GKI symvers record")
        record = (f"0x{int(columns[0], 16):08x}", columns[2], columns[3], columns[4] if len(columns) == 5 else "")
        if columns[1] in records:
            raise ValueError("duplicate GKI export")
        records[columns[1]] = record
        origins[columns[1]] = str(args.gki)
    for path in args.modules:
        for name, record in Module(path).exports().items():
            if name in records and records[name] != record:
                raise ValueError(f"conflicting provider for {name}")
            records[name] = record
            origins[name] = path
    # Generate only after all sources pass; never keep a partial symvers.
    output = "".join("\t".join((record[0], name, *record[1:])) + "\n" for name, record in sorted(records.items()))
    sources = [args.gki, *args.modules]
    provenance = {"sources": {path: hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in sources},
                  "export_count": len(records), "symbol_providers": origins,
                  "header_abi_verified": False, "loadability_verified": False}
    Path(args.output).write_text(output)
    Path(args.provenance).write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n")
    print(f"Recovered {len(records)} provider exports; header ABI and loadability remain unverified.")


if __name__ == "__main__":
    main()
