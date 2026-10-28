#!/usr/bin/env python3
"""Parse an 802.11+radiotap pcap and classify frames, looking for AWDL.

AWDL action-frame layout (verified on air, see filin-rs announce_template.rs):
    bytes 0..24   802.11 action header (FC 0x00d0, addr1 broadcast, addr3 = AWDL BSSID)
    bytes 24..30  7f 00 17 f2 08 10   vendor category / OUI / type / subtype
    byte  30      AWDL subtype (0 = PSF, 3 = MIF)
"""
import struct
import sys
from collections import Counter

PATH = sys.argv[1] if len(sys.argv) > 1 else "/tmp/awdl-fast.pcap"
AWDL_PREFIX = bytes.fromhex("7f0017f20810")

TYPE_NAMES = {0: "mgmt", 1: "ctrl", 2: "data", 3: "ext"}
MGMT_SUB = {
    0: "assoc-req", 1: "assoc-resp", 2: "reassoc-req", 3: "reassoc-resp",
    4: "probe-req", 5: "probe-resp", 8: "beacon", 9: "atim", 10: "disassoc",
    11: "auth", 12: "deauth", 13: "action", 14: "action-noack",
}


def radiotap_freq(buf):
    """Return the channel frequency from a radiotap header, or None."""
    if len(buf) < 8:
        return None
    rt_len = struct.unpack_from("<H", buf, 2)[0]
    if rt_len > len(buf) or rt_len < 8:
        return None
    it_present = struct.unpack_from("<I", buf, 4)[0]
    offset = 8
    # Bit 0..: TSFT(8, aligned 8), Flags(1), Rate(1), Channel(4, aligned 2)
    if it_present & (1 << 0):          # TSFT
        offset = (offset + 7) & ~7
        offset += 8
    if it_present & (1 << 1):          # Flags
        offset += 1
    if it_present & (1 << 2):          # Rate
        offset += 1
    if it_present & (1 << 3):          # Channel
        offset = (offset + 1) & ~1
        if offset + 4 <= len(buf):
            freq, _flags = struct.unpack_from("<HH", buf, offset)
            return freq
    return None


def main():
    with open(PATH, "rb") as fh:
        data = fh.read()
    magic = struct.unpack_from("<I", data, 0)[0]
    if magic not in (0xA1B2C3D4, 0xD4C3B2A1):
        sys.exit(f"not a pcap: magic {magic:#x}")
    endian = "<" if magic == 0xA1B2C3D4 else ">"
    off = 24
    total = 0
    freqs = Counter()
    kinds = Counter()
    awdl_hits = []
    action_hits = []
    addr3_ctr = Counter()

    while off + 16 <= len(data):
        _ts, _tu, incl, _orig = struct.unpack_from(endian + "IIII", data, off)
        off += 16
        pkt = data[off:off + incl]
        off += incl
        total += 1

        freq = radiotap_freq(pkt)
        if freq:
            freqs[freq] += 1

        rt_len = struct.unpack_from("<H", pkt, 2)[0] if len(pkt) >= 4 else 0
        if rt_len + 24 > len(pkt):
            continue
        frame = pkt[rt_len:]
        fc = frame[0]
        ftype = (fc >> 2) & 0x3
        subtype = (fc >> 4) & 0xF
        kinds[(TYPE_NAMES.get(ftype, "?"), subtype)] += 1
        a3 = frame[16:22].hex(":")
        addr3_ctr[a3] += 1

        if ftype == 0 and subtype == 13:      # action
            body = frame[24:]
            action_hits.append((a3, frame[10:16].hex(":"), body[:40].hex(" ")))
            if body[:6] == AWDL_PREFIX:
                awdl_hits.append((a3, body.hex(" ")))

    print(f"total packets: {total}")
    print(f"radiotap frequencies: {dict(freqs)}")
    print("frame types (type, subtype):")
    for (tname, sub), n in kinds.most_common(12):
        label = MGMT_SUB.get(sub, str(sub)) if tname == "mgmt" else str(sub)
        print(f"  {tname:5s} sub={sub:2d} ({label:12s}) {n}")
    print(f"top addr3: {addr3_ctr.most_common(8)}")
    print(f"\naction frames: {len(action_hits)}")
    for a3, a2, body in action_hits[:10]:
        print(f"  a2={a2} a3={a3} body={body}")
    print(f"\nAWDL-prefix frames: {len(awdl_hits)}")
    for a3, body in awdl_hits[:5]:
        print(f"  a3={a3} {body[:120]}")


if __name__ == "__main__":
    main()
