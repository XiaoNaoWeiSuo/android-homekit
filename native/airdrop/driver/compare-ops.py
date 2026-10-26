#!/usr/bin/env python3
"""Compare a named, pointer-aligned static callback table in two ARM64 modules.

Resolves relocations to functions, objects and literal strings so differing
ELF section addresses do not hide a null callback or create false differences.
This compares the selected table only; it does not prove full ABI compatibility.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import struct

SPEC = importlib.util.spec_from_file_location("stock_symvers", Path(__file__).with_name("stock-symvers.py"))
ELF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ELF)


def inspect(path, name):
    elf = ELF.Module(path)
    index = elf.names.index(".symtab")
    blob = elf.bytes(index)
    strings = elf.bytes(elf.sections[index][6])
    if len(blob) % 24:
        raise ValueError("invalid symbol table")
    symbols, by_address = [], {}
    for offset in range(0, len(blob), 24):
        no, info, other, section, value, size = struct.unpack_from("<IBBHQQ", blob, offset)
        symbol = {"name": elf.string(strings, no), "section": section,
                  "value": value, "size": size, "kind": info & 15}
        symbols.append(symbol)
        if symbol["name"] and symbol["kind"] in (1, 2):
            by_address.setdefault((section, value), []).append(symbol)
    matches = [symbol for symbol in symbols if symbol["name"] == name]
    if len(matches) != 1:
        raise ValueError(f"{path}: expected one definition of {name}, found {len(matches)}")
    selected = matches[0]
    section, start, size = selected["section"], selected["value"], selected["size"]
    if not size or size % 8:
        raise ValueError("expected a nonempty, pointer-aligned object")
    contents = elf.bytes(section)
    if start + size > len(contents):
        raise ValueError("object extends beyond its ELF section")
    fields = {str(offset): {"kind": "scalar", "value": struct.unpack_from("<Q", contents, start + offset)[0]}
              for offset in range(0, size, 8)}
    for index, header in enumerate(elf.sections):
        if header[1] != 4 or header[7] != section:
            continue
        relocations = elf.bytes(index)
        if len(relocations) % 24:
            raise ValueError("invalid ELF relocation table")
        for offset in range(0, len(relocations), 24):
            location, info, addend = struct.unpack_from("<QQq", relocations, offset)
            if not start <= location < start + size:
                continue
            if (location - start) % 8 or info & 0xffffffff != 257:
                raise ValueError("expected aligned ARM64 ABS64 table relocations")
            symbol_index = info >> 32
            if symbol_index >= len(symbols):
                raise ValueError("invalid relocation symbol")
            target = symbols[symbol_index]
            target_section, value = target["section"], target["value"] + addend
            resolved = target
            if target["kind"] == 3:
                aliases = by_address.get((target_section, value), [])
                if aliases:
                    resolved = sorted(aliases, key=lambda s: (not s["name"].endswith(".cfi_jt"), len(s["name"])))[0]
                    addend = 0
                elif target_section < len(elf.sections) and "rodata" in elf.names[target_section]:
                    data = elf.bytes(target_section)
                    end = data.find(b"\0", value)
                    if 0 <= value <= end < value + 200:
                        try:
                            fields[str(location - start)] = {"kind": "string", "value": data[value:end].decode("ascii")}
                            continue
                        except UnicodeError:
                            pass
            if resolved["name"] and resolved["kind"] in (1, 2):
                # C identifiers do not contain periods; LLVM appends hashes
                # and .cfi_jt suffixes to internal function names.
                fields[str(location - start)] = {"kind": "function" if resolved["kind"] == 2 else "object",
                                                 "name": resolved["name"].split(".", 1)[0], "addend": addend}
            else:
                fields[str(location - start)] = {"kind": "unresolved", "symbol": target["name"],
                                                 "section": elf.names[target_section] if target_section < len(elf.sections) else target_section,
                                                 "value": value}
    return {"size": size, "fields": fields}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock")
    parser.add_argument("candidate")
    parser.add_argument("--symbol", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    stock, candidate = inspect(args.stock, args.symbol), inspect(args.candidate, args.symbol)
    offsets = stock["fields"].keys() | candidate["fields"].keys()
    differences = {offset: {"stock": stock["fields"].get(offset), "candidate": candidate["fields"].get(offset)}
                   for offset in sorted(offsets, key=int) if stock["fields"].get(offset) != candidate["fields"].get(offset)}
    unresolved = any(field["kind"] == "unresolved" for table in [stock, candidate] for field in table["fields"].values())
    identical = stock["size"] == candidate["size"] and not differences and not unresolved
    report = {"symbol": args.symbol, "stock": stock, "candidate": candidate, "differences": differences,
              "selected_table_matches": identical, "loadability_verified": False}
    Path(args.output).write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"selected_table_matches": identical, "differences": differences}, indent=2))
    return 0 if identical else 3


if __name__ == "__main__":
    raise SystemExit(main())
