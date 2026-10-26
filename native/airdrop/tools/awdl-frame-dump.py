#!/usr/bin/env python3
"""Decode AWDL action frames captured by marionette-awdl-capture.

Ground-truth tool. The AWDL wire format cannot be guessed: an Apple peer only
synchronises when every TLV carries the exact fields it expects. This reads a
capture log (lines `AWDL freq=<mhz> len=<n> seq=<s> <hex>`) and prints the
802.11 header, the 16-byte AWDL action header and every TLV, so our emitter can
be diffed against a real Mac byte for byte.

The TLV chain starts at frame offset 40, i.e. 16 bytes into the action body:
    24: 7f 00 17 f2 08 10    vendor category/OUI/type/subtype
    30: 00 | 03              AWDL subtype (PSF | MIF)
    31: 00                   reserved
    32: phy_tx    (u32 LE)
    36: target_tx (u32 LE)
    40: TLV chain (type u8, length u16 LE, value)

Observed on air (45 frames, 2026-10-21):
    PSF: 4, 5, 18, 19, 6, 24, 12, 7, 17, 21, 33
    MIF: 4, 5, 18, 24, 12, 7, 17, 16, 21, 6, 2 * 9

Usage: awdl-frame-dump.py <capture.log> [--raw]
"""
"""Parse real AWDL action frames captured from Apple peers.

Prints the 802.11 header, the AWDL action header and every TLV (type, len,
first bytes) so the wire format can be compared against filin-rs.
"""
import re
import sys
from collections import Counter, defaultdict

AWDL_BSSID = bytes([0x00, 0x25, 0x00, 0xFF, 0x94, 0x73])
PREFIX = bytes([0x7F, 0x00, 0x17, 0xF2, 0x08, 0x10])


def parse(path):
    frames = []
    for line in open(path):
        m = re.match(r"AWDL freq=(\d+) len=(\d+) seq=(\d+) ([0-9a-f]+)", line)
        if not m:
            continue
        freq = int(m.group(1))
        raw = bytes.fromhex(m.group(4))
        frames.append((freq, raw))
    return frames


def tlvs(body, start):
    out = []
    off = start
    while len(body) - off >= 3:
        t = body[off]
        ln = body[off + 1] | (body[off + 2] << 8)
        if ln > len(body) - off - 3:
            out.append((t, ln, None))
            break
        out.append((t, ln, body[off + 3:off + 3 + ln]))
        off += 3 + ln
    return out, len(body) - off


def main():
    frames = parse(sys.argv[1])
    print(f"frames={len(frames)}")
    by_sub = defaultdict(list)
    for freq, raw in frames:
        if raw[:2] != b"\xd0\x00":
            continue
        if raw[16:22] != AWDL_BSSID:
            continue
        if raw[24:30] != PREFIX:
            continue
        sub = raw[30]
        by_sub[sub].append((freq, raw))

    for sub, group in sorted(by_sub.items()):
        name = {0: "PSF", 3: "MIF"}.get(sub, f"sub{sub}")
        print(f"\n=== subtype {sub} ({name}) n={len(group)} ===")
        freq, raw = group[0]
        print(f"sample freq={freq} len={len(raw)}")
        print("  fc        ", raw[0:2].hex())
        print("  addr1     ", ":".join(f"{b:02x}" for b in raw[4:10]))
        print("  addr2     ", ":".join(f"{b:02x}" for b in raw[10:16]))
        print("  addr3     ", ":".join(f"{b:02x}" for b in raw[16:22]))
        print("  seq       ", int.from_bytes(raw[22:24], "little") >> 4)
        print("  awdl sub  ", raw[30])
        print("  phy_tx    ", hex(int.from_bytes(raw[31:35], "little")))
        print("  target_tx ", hex(int.from_bytes(raw[35:39], "little")))
        for start in (39, 40, 41):
            parsed, tail = tlvs(raw, start)
            types = [t for t, _, _ in parsed]
            print(f"  tlvs@+{start}: {types} tail={tail}")
        parsed, tail = tlvs(raw, 39)
        for t, ln, val in parsed:
            head = val[:16].hex() if val else "<truncated>"
            print(f"    TLV {t:3d} len={ln:4d} {head}")

    # TLV type histogram across every frame, assuming offset 39
    hist = Counter()
    for sub, group in by_sub.items():
        for _, raw in group:
            parsed, _ = tlvs(raw, 39)
            hist[tuple(t for t, _, _ in parsed)] += 1
    print("\n=== TLV type sequences ===")
    for seq, n in hist.most_common():
        print(f"  {list(seq)} x{n}")


if __name__ == "__main__":
    main()
