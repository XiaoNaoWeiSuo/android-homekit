#!/usr/bin/env python3
"""Compare selected compiled DWARF layouts with BTF read from the running GKI.

This checks structure sizes and common named members only. It does not prove
the ABI of unlisted types, nested anonymous types, functions, or firmware.
"""
import argparse
import json
from pathlib import Path
import re
import struct
import subprocess

DEFAULT_TYPES = ["net_device", "net_device_ops", "sk_buff", "wireless_dev", "wiphy",
                 "device", "timer_list", "module", "work_struct"]


def btf_layouts(data, wanted):
    if len(data) < 24:
        raise ValueError("truncated BTF header")
    magic, version, flags, header, toff, tlen, soff, slen = struct.unpack_from("<HBBIIIII", data)
    if magic != 0xeb9f or version != 1 or header < 24 or header + toff + tlen > len(data) or header + soff + slen > len(data):
        raise ValueError("invalid BTF header or bounds")
    strings = data[header + soff:header + soff + slen]

    def text(offset):
        end = strings.find(b"\0", offset)
        if offset >= len(strings) or end < 0:
            raise ValueError("invalid BTF string")
        return strings[offset:end].decode("ascii")

    roots, composites, root_ids = {}, {}, {}
    type_id = 1
    pos, end = header + toff, header + toff + tlen
    while pos < end:
        if pos + 12 > end:
            raise ValueError("truncated BTF type")
        name, info, size = struct.unpack_from("<III", data, pos)
        kind, count = info >> 24 & 31, info & 65535
        lengths = {1: 4, 2: 0, 3: 12, 4: count*12, 5: count*12, 6: count*8,
                   7: 0, 8: 0, 9: 0, 10: 0, 11: 0, 12: 0, 13: count*8,
                   14: 4, 15: count*12, 16: 0, 17: 4, 18: 0, 19: count*12}
        if kind not in lengths or pos + 12 + lengths[kind] > end:
            raise ValueError("unsupported or truncated BTF payload")
        pos += 12
        name = text(name)
        if kind in (4, 5):
            members = []
            typed_members = []
            for index in range(count):
                moff, mtype, bits = struct.unpack_from("<III", data, pos + index*12)
                member = {"name": text(moff) or None, "bit_offset": bits & 0xffffff if info >> 31 else bits}
                if info >> 31 and bits >> 24:
                    member["bit_size"] = bits >> 24
                members.append(member)
                typed_members.append((member, mtype))
            composites[type_id] = typed_members
            if kind == 4 and name in wanted:
                record = {"size": size, "members": members}
                if name in roots and roots[name] != record:
                    raise ValueError(f"conflicting BTF type definitions for {name}")
                roots[name] = record
                root_ids[name] = type_id
        pos += lengths[kind]
        type_id += 1

    def aliases(index, base=0, seen=None):
        seen = set() if seen is None else seen
        if index in seen or len(seen) > 32:
            raise ValueError("cyclic or excessively nested BTF composite")
        found = {}
        for member, child in composites.get(index, []):
            offset = base + member["bit_offset"]
            if member["name"]:
                record = dict(member, bit_offset=offset)
                if member["name"] in found and found[member["name"]] != record:
                    raise ValueError("ambiguous anonymous BTF member")
                found[member["name"]] = record
            elif child in composites:
                for name, record in aliases(child, offset, seen | {index}).items():
                    if name in found and found[name] != record:
                        raise ValueError("ambiguous anonymous BTF member")
                    found[name] = record
        return found

    for name, record in roots.items():
        record["member_positions_including_anonymous"] = aliases(root_ids[name])
    return roots


def dwarf_record(lines):
    if not lines or "DW_TAG_structure_type" not in lines[0]:
        return None
    parts = re.split(r"(?=^0x[0-9a-f]+:\s+DW_TAG_)", "".join(lines), flags=re.M)
    head = next((p for p in parts if "DW_TAG_structure_type" in p), "")
    name = re.search(r'DW_AT_name\s+\("([^"]+)"\)', head)
    size = re.search(r'DW_AT_byte_size\s+\((0x[0-9a-f]+|[0-9]+)\)', head)
    if not name or not size:
        return None  # forward declaration
    members = []
    for part in parts:
        if not re.match(r"^0x[0-9a-f]+:\s+DW_TAG_member\b", part):
            continue

        def number(attribute):
            match = re.search(attribute + r"\s+\((0x[0-9a-f]+|[0-9]+)\)", part)
            return int(match[1], 0) if match else None

        label = re.search(r'DW_AT_name\s+\("([^"]*)"\)', part)
        offset = number("DW_AT_data_bit_offset")
        width = number("DW_AT_bit_size")
        if offset is None:
            byte_offset = number("DW_AT_data_member_location")
            if byte_offset is None:
                raise ValueError("DWARF member location is not a constant")
            offset = byte_offset * 8
            old_bit_offset = number("DW_AT_bit_offset")
            if old_bit_offset is not None:
                storage = number("DW_AT_byte_size")
                if storage is None or width is None:
                    raise ValueError("incomplete DWARF4 bit field")
                # DWARF4 bit_offset counts from the MSB of the storage unit.
                offset += storage * 8 - old_bit_offset - width
        member = {"name": label[1] if label else None, "bit_offset": offset}
        if width is not None:
            member["bit_size"] = width
        members.append(member)
    return name[1], {"size": int(size[1], 0), "members": members}


def compiled_layouts(dumper, obj, wanted):
    pattern = "^(" + "|".join(re.escape(name) for name in sorted(wanted)) + ")$"
    process = subprocess.Popen([dumper, "--regex", "--name=" + pattern, "--show-children",
                                "--recurse-depth=1", obj], stdout=subprocess.PIPE, text=True)
    roots, block = {}, []

    def store():
        record = dwarf_record(block)
        if record and record[0] in wanted and record[0] not in roots:
            roots[record[0]] = record[1]

    try:
        for line in process.stdout:
            if re.match(r"^0x[0-9a-f]+: DW_TAG_", line):
                store()
                block = []
                if roots.keys() >= wanted:
                    break
            block.append(line)
        else:
            store()
            if process.wait() != 0:
                raise ValueError("llvm-dwarfdump failed")
    finally:
        if process.poll() is None:
            process.terminate()
        process.wait()
        process.stdout.close()
    return roots


def llvm_layouts(text, wanted):
    """Read layout metadata from llvm-dis output of one compiled bitcode unit.

    LLVM member offsets already count from the least-significant bit, unlike
    the older DWARF4 encoding. Forward declarations are never treated as a
    complete type. Other compilation units remain outside this input's scope.
    """
    nodes = {int(index): body for index, body in re.findall(r"^!(\d+) = (.+)$", text, re.M)}
    roots = {}
    for node in nodes.values():
        if "DICompositeType(tag: DW_TAG_structure_type" not in node or "DIFlagFwdDecl" in node:
            continue
        name = re.search(r'name: "([^"]+)"', node)
        size = re.search(r"\bsize: (\d+)", node)
        elements = re.search(r"elements: !(\d+)", node)
        if not name or name[1] not in wanted or not size or not elements:
            continue
        if int(elements[1]) not in nodes or int(size[1]) % 8:
            raise ValueError("incomplete LLVM structure metadata")
        members = []
        for index in re.findall(r"!(\d+)", nodes[int(elements[1])]):
            if int(index) not in nodes:
                raise ValueError("missing LLVM member metadata")
            member = nodes[int(index)]
            if "DIDerivedType(tag: DW_TAG_member" not in member or "DIFlagStaticMember" in member:
                continue
            label = re.search(r'name: "([^"]*)"', member)
            offset = re.search(r"\boffset: (\d+)", member)
            record = {"name": label[1] if label else None, "bit_offset": int(offset[1]) if offset else 0}
            if "DIFlagBitField" in member:
                width = re.search(r"\bsize: (\d+)", member)
                if not width:
                    raise ValueError("incomplete LLVM bit field metadata")
                record["bit_size"] = int(width[1])
            members.append(record)
        record = {"size": int(size[1]) // 8, "members": members}
        if name[1] in roots and roots[name[1]] != record:
            raise ValueError(f"conflicting LLVM type definitions for {name[1]}")
        roots[name[1]] = record
    return roots


def compare(compiled, running, wanted):
    report = {}
    for name in sorted(wanted):
        if name not in compiled or name not in running:
            report[name] = {"missing": True}
            continue
        c, r = compiled[name], running[name]
        cm = {m["name"]: m for m in c["members"] if m["name"]}
        rm = {m["name"]: m for m in r["members"] if m["name"]}
        aliases = r.get("member_positions_including_anonymous", rm)
        report[name] = {"compiled_size": c["size"], "running_size": r["size"],
                        "offset_mismatches": {n: {"compiled": cm[n], "running": rm[n]}
                                              for n in sorted(cm.keys() & rm.keys()) if cm[n] != rm[n]},
                        "compiled_only": sorted(cm.keys()-rm.keys()),
                        "running_only": sorted(rm.keys()-cm.keys()),
                        "compiled_members_found_in_running_anonymous_types": {
                            n: aliases[n] for n in sorted(cm.keys()-rm.keys()) if n in aliases},
                        "compiled_member_positions_match": c["size"] == r["size"] and
                            all(n in aliases and member == aliases[n] for n, member in cm.items())}
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dwarfdump")
    parser.add_argument("--btf", required=True)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--object")
    inputs.add_argument("--llvm-ir", help="llvm-dis output from a compiled bitcode unit")
    parser.add_argument("--output", required=True)
    parser.add_argument("--types", help="comma-separated structure names; default is the initial nine-type sample")
    args = parser.parse_args()
    wanted = set(args.types.split(",")) if args.types else set(DEFAULT_TYPES)
    if not wanted or any(not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", name) for name in wanted):
        parser.error("types must be comma-separated C structure names")
    if args.object and not args.dwarfdump:
        parser.error("--object requires --dwarfdump")
    compiled = llvm_layouts(Path(args.llvm_ir).read_text(), wanted) if args.llvm_ir else \
        compiled_layouts(args.dwarfdump, args.object, wanted)
    running = btf_layouts(Path(args.btf).read_bytes(), wanted)
    report = compare(compiled, running, wanted)
    complete = all(not v.get("missing") and v["compiled_size"] == v["running_size"]
                   and not v["offset_mismatches"] and not v["compiled_only"] and not v["running_only"]
                   for v in report.values())
    result = {"selected_layouts_identical": complete,
              "selected_compiled_member_positions_match": all(v.get("compiled_member_positions_match", False)
                                                             for v in report.values()),
              "loadability_verified": False,
              "compiled_layout_source": "single compilation unit LLVM metadata" if args.llvm_ir else "linked object DWARF",
              "types": report, "scope": "Selected direct compiled named members, including running BTF anonymous aliases. Compiled anonymous members, nested types and other types remain unverified."}
    Path(args.output).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"selected_layouts_identical": complete,
                      "differences": {n: v for n, v in report.items() if v.get("missing") or
                                      v["compiled_size"] != v["running_size"] or v["offset_mismatches"] or
                                      v["compiled_only"] or v["running_only"]}}, indent=2))
    return 0 if complete else 3


if __name__ == "__main__":
    raise SystemExit(main())
