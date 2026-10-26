#!/usr/bin/env python3
"""Join an observed AWDL cluster: rewrite a replay plan's cluster identity.

Every Apple device on channel 2437 here broadcasts PSFs that point at the same
master (measured: all nine nodes carry 2e:1a:3f:c6:56:08 in TLV 4 bytes 21..27
and TLV 5 bytes 5..11).  A node that announces itself as a master instead is
left out of the cluster, so nothing ever addresses it.  This tool rewrites a
plan so that we claim to be a leaf of the observed cluster instead.

What it changes, per frame:
  TLV 4  bytes 21..27   master address
  TLV 5  bytes  5..11   master address
  TLV 5  bytes 11..13   master election metric (taken from a live peer, since it
                        rotates and a stale value marks the frame as old)
  TLV 24 bytes  0..12   master address and sync address

What it deliberately leaves alone: addr2 (cfg80211 requires it to equal the
sending interface's address), TLV 12 byte 15 (the transmitter's own address),
and the timing fields.

Usage: awdl-join.py <capture.log> <plan.txt> <out.txt>
"""
import re
import sys
from collections import Counter

RX = re.compile(r"AWDL (?:ts=\d+ )?freq=(\d+) len=(\d+) seq=(\d+) ([0-9a-f]+)")


def tlvs(body, start=40):
    out = []
    off = start
    while len(body) - off >= 3:
        t = body[off]
        ln = body[off + 1] | (body[off + 2] << 8)
        if ln > len(body) - off - 3:
            break
        out.append((t, ln, body[off + 3:off + 3 + ln], off + 3))
        off += 3 + ln
    return out


def observed_master(frames):
    votes = Counter()
    for f in frames:
        if len(f) < 340:
            continue
        for t, ln, v, start in tlvs(f):
            if t == 4 and ln >= 27:
                votes[v[21:27].hex()] += 1
    return votes.most_common(1)[0][0] if votes else None


def observed_metric(frames, master_hex):
    votes = Counter()
    for f in frames:
        if len(f) < 340:
            continue
        for t, ln, v, start in tlvs(f):
            if t == 5 and ln >= 13:
                if v[5:11].hex() == master_hex:
                    votes[v[11:13].hex()] += 1
    return votes.most_common(1)[0][0] if votes else None


def main():
    cap, plan, out = sys.argv[1], sys.argv[2], sys.argv[3]
    frames = []
    for line in open(cap):
        m = RX.match(line)
        if m:
            frames.append(bytes.fromhex(m.group(4)))
    master = observed_master(frames)
    metric = observed_metric(frames, master)
    assert master, "no master observed in capture"
    assert metric, "no master metric observed in capture"
    mbytes = bytes.fromhex(master)
    gbytes = bytes.fromhex(metric)
    print("cluster master=%s metric=%s (from %d frames)"
          % (":".join(mbytes[i:i + 1].hex() for i in range(6)), metric, len(frames)))

    n = 0
    with open(plan) as fin, open(out, "w") as fout:
        for line in fin:
            if line.startswith("#"):
                fout.write(line)
                continue
            parts = line.split()
            if len(parts) < 4:
                fout.write(line)
                continue
            off, kind, ln, hexs = parts[0], parts[1], parts[2], parts[3]
            frame = bytearray(bytes.fromhex(hexs))
            for t, l, v, start in tlvs(frame):
                if t == 4 and l >= 27:
                    frame[start + 21:start + 27] = mbytes
                elif t == 5 and l >= 13:
                    frame[start + 5:start + 11] = mbytes
                    frame[start + 11:start + 13] = gbytes
                elif t == 24 and l >= 12:
                    frame[start + 0:start + 6] = mbytes
                    frame[start + 6:start + 12] = mbytes
            fout.write("%s %s %d %s\n" % (off, kind, len(frame), frame.hex()))
            n += 1
    print("rewrote %d frames -> %s" % (n, out))


if __name__ == "__main__":
    main()
