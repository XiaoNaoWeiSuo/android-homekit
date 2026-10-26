#!/usr/bin/env python3
"""Give our replayed MIF frames our own AirDrop service identity.

The MIF template is a real Mac's frame (filin-rs `announce_template.rs`).
The Rust builder rewrites the addresses and the TLV 16 ARPA name, but the
service records in the TLV 2 chain still carry *the Mac's* identity: its
mDNS hostname, its display name, its AirDrop session id, its capability
string, and the service-instance UUID that is byte-identical to TLV 16.
Broadcasting those back at the Mac advertises the Mac to itself, so nothing
can ever be discovered as us.

Structure, measured on nine MIFs captured from eight different Apple peers
(ROADMAP §16.6):

    TLV 2 payload := <record>* <2 bytes>
    record        := <u16 LE L> <body of L bytes>

The last record's body runs two bytes past the length it declares, and
those two bytes are the payload's trailing bytes. Every captured frame
does this, so it is part of the wire format, not a defect, and it is
reproduced here byte for byte.

Records come in pairs, a name record followed by its rdata:

    name  := <labels...> <c0 xx> <type u8>
    rdata := <u16 0x0000> <rdata>

with type 0x0c = PTR, 0x21 = SRV, 0x10 = TXT. Labels are ordinary DNS
labels. The label immediately before `_appsvcprepair` /
`_applicationservicepairing` is the mDNS hostname (lower case); the PTR
rdata is the display name (title case); the TXT rdata repeats the display
name as `dnm=`; and the SRV rdata ends with the 36-character service
instance UUID, the same string as TLV 16.

There is no 9-byte binary host id. What looked like one was the first nine
bytes of a UTF-8 hostname: `e7 84 b1 e6 9e 97 e7 9a 84` is "yan lin de", so
the Mac's hostname is "yan lin de MacBook" and its lowercase form is
"yan lin de macbook". The same label is the display name in title case and
the mDNS hostname in lower case, which is why two spellings have to be
rewritten.

Usage:
    awdl-identity.py <plan.txt> <out.txt> [--host NAME] [--display NAME]
                     [--sid UUID] [--at HEX12] [--uuid UUID]
                     [--check] [--selftest]
"""
import os
import re
import sys

RX = re.compile(r"AWDL (?:ts=\d+ )?freq=(\d+) len=(\d+) seq=(\d+) ([0-9a-f]+)")
PLAN = re.compile(r"^(\d+) (PSF|MIF|DATA) (\d+) ([0-9a-f]+)$")
TLV_START = 40

SERVICE_LABELS = (b"_appsvcprepair", b"_applicationservicepairing")
UUID_RE = re.compile(
    rb"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-"
    rb"[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
PTR, SRV, TXT = 0x0C, 0x21, 0x10


def tlvs(body, start=TLV_START):
    """Walk the AWDL TLV chain. Returns (spans, end_offset, ok)."""
    out = []
    off = start
    while len(body) - off >= 3:
        t = body[off]
        ln = body[off + 1] | (body[off + 2] << 8)
        if ln > len(body) - off - 3:
            return out, off, False
        out.append((t, ln, off + 3))
        off += 3 + ln
    return out, off, True


def split_name(b):
    """Leading DNS labels of a record body.

    Returns ([(offset, length, label_bytes)], end_offset). `end_offset` is
    just past the terminating root label or compression pointer, or None if
    the name runs off the end of the body.
    """
    out = []
    i = 0
    while i < len(b):
        n = b[i]
        if n == 0:
            return out, i + 1
        if (n & 0xC0) == 0xC0:
            return out, i + 2
        if i + 1 + n > len(b):
            return out, None
        out.append((i, n, b[i + 1:i + 1 + n]))
        i += 1 + n
    return out, len(b)


def parse_records(v):
    """Split a TLV 2 payload into record bodies plus trailing bytes."""
    recs = []
    off = 0
    while off + 2 <= len(v):
        ln = v[off] | (v[off + 1] << 8)
        if off + 2 + ln > len(v):
            break
        recs.append(v[off + 2:off + 2 + ln])
        off += 2 + ln
    return recs, v[off:]


def is_name_record(b):
    """A name record ends with a compression pointer and a type byte."""
    if len(b) < 3:
        return False
    _, end = split_name(b)
    if end is None or end != len(b) - 1:
        return False
    return (b[end - 2] & 0xC0) == 0xC0


def rewrite_name_record(body, host):
    """Replace the host label: the label before a service label."""
    if not host:
        return body
    labels, _ = split_name(body)
    if len(labels) < 2:
        return body
    for i in range(len(labels) - 1, -1, -1):
        if labels[i][2] in SERVICE_LABELS:
            if i == 0:
                return body
            off, n, _ = labels[i - 1]
            new = host.encode()
            if len(new) > 60:
                return body
            return body[:off] + bytes([len(new)]) + new + body[off + 1 + n:]
    return body


def rewrite_rdata(body, typ, ident, service):
    """Rewrite the identity inside one rdata record.

    `service` says whether the paired name record carried one of the two
    AirDrop service labels. Records for other services share the TLV 2
    chain but carry their own names, which are not ours to change.
    """
    if len(body) < 2 or body[0] != 0 or body[1] != 0:
        return body
    rd = body[2:]

    if typ == PTR:
        # The whole rdata is one DNS label: the display name.
        if not service or not ident.get("display"):
            return body
        labels, _ = split_name(rd)
        if len(labels) != 1:
            return body
        off, n, _ = labels[0]
        new = ident["display"].encode()
        if len(new) > 60:
            return body
        return body[:2] + bytes([len(new)]) + new + rd[off + 1 + n:]

    if typ == TXT:
        # A sequence of <len><string>; only sid=/at=/dnm= carry identity.
        if not service:
            return body
        out = bytearray()
        i = 0
        while i < len(rd):
            n = rd[i]
            if i + 1 + n > len(rd):
                out += rd[i:]
                break
            s = rd[i + 1:i + 1 + n]
            if s.startswith(b"sid=") and ident.get("sid"):
                s = b"sid=" + ident["sid"].encode()
            elif s.startswith(b"at=") and ident.get("at"):
                s = b"at=" + ident["at"].encode()
            elif s.startswith(b"dnm=") and ident.get("display"):
                s = b"dnm=" + ident["display"].encode()
            if len(s) > 255:
                return body
            out.append(len(s))
            out += s
            i += 1 + n
        return body[:2] + bytes(out)

    if typ == SRV:
        # ... <u8 36> <36-char service instance UUID>. The UUID is the same
        # string as TLV 16, so both have to move together.
        if ident["old_uuid"] and ident["old_uuid"] in body:
            return body.replace(ident["old_uuid"], ident["uuid"].encode())
        return body

    return body


def rewrite_tlv2(v, ident):
    """Rebuild one TLV 2 payload with our identity."""
    bodies, tail = parse_records(v)
    if not bodies:
        return v
    # The last record's body extends past its declared length by exactly the
    # trailing bytes, so fold them in before rewriting and let the re-emit
    # below split them back off.
    bodies = list(bodies)
    has_tail = len(tail) > 0
    if has_tail:
        bodies[-1] = bodies[-1] + tail

    out = []
    cur_type = None
    cur_service = False
    for body in bodies:
        if is_name_record(body):
            cur_type = body[-1]
            labels, _ = split_name(body)
            cur_service = any(l[2] in SERVICE_LABELS for l in labels)
            out.append(rewrite_name_record(body, ident["host"]))
        else:
            out.append(rewrite_rdata(body, cur_type, ident, cur_service))

    res = bytearray()
    for i, b in enumerate(out):
        declared = len(b) - 2 if (i == len(out) - 1 and has_tail) else len(b)
        if declared < 0:
            return None
        res += declared.to_bytes(2, "little")
        res += b
    return bytes(res)


def collect_old_uuid(spans, frame):
    """The service instance UUID still sitting in the SRV rdata."""
    for t, ln, vs in spans:
        if t != 2:
            continue
        bodies, _ = parse_records(bytes(frame[vs:vs + ln]))
        cur_type = None
        for body in bodies:
            if is_name_record(body):
                cur_type = body[-1]
            elif cur_type == SRV:
                m = UUID_RE.search(body)
                if m:
                    return m.group()
    return None


def extract_identity(frame):
    """Recover the identity a MIF frame currently advertises.

    Used to record what the template peer was called, so `--check` can
    prove none of it survives the rewrite.
    """
    spans, _, ok = tlvs(frame)
    if not ok:
        return None
    got = {"host": None, "display": None, "sid": None, "at": None,
           "uuid": None, "old_uuid": None}
    for t, ln, vs in spans:
        value = bytes(frame[vs:vs + ln])
        if t == 16:
            n = value[1] if len(value) > 1 else 0
            got["uuid"] = value[2:2 + n].decode("ascii", "replace")
        elif t == 2:
            bodies, tail = parse_records(value)
            if not bodies:
                continue
            bodies = list(bodies)
            bodies[-1] = bodies[-1] + tail
            cur_type = None
            service = False
            for body in bodies:
                if is_name_record(body):
                    cur_type = body[-1]
                    labels, _ = split_name(body)
                    service = any(l[2] in SERVICE_LABELS for l in labels)
                    if service and got["host"] is None:
                        for j in range(len(labels) - 1, -1, -1):
                            if labels[j][2] in SERVICE_LABELS and j > 0:
                                got["host"] = labels[j - 1][2].decode(
                                    "utf-8", "replace")
                                break
                elif service:
                    if cur_type == PTR and got["display"] is None:
                        labels, _ = split_name(body[2:])
                        if len(labels) == 1:
                            got["display"] = labels[0][2].decode(
                                "utf-8", "replace")
                    elif cur_type == TXT:
                        rd = body[2:]
                        i = 0
                        while i < len(rd):
                            n = rd[i]
                            if i + 1 + n > len(rd):
                                break
                            s = rd[i + 1:i + 1 + n]
                            if s.startswith(b"sid=") and got["sid"] is None:
                                got["sid"] = s[4:].decode("ascii", "replace")
                            elif s.startswith(b"at=") and got["at"] is None:
                                got["at"] = s[3:].decode("ascii", "replace")
                            elif (s.startswith(b"dnm=")
                                  and got["display"] is None):
                                got["display"] = s[4:].decode(
                                    "utf-8", "replace")
                            i += 1 + n
                    elif cur_type == SRV and got["old_uuid"] is None:
                        m = UUID_RE.search(body)
                        if m:
                            got["old_uuid"] = m.group().decode("ascii")
    return got


def rewrite_mif(frame, ident):
    """Rebuild a MIF frame with our identity in the service TLVs.

    The whole TLV chain is re-emitted rather than spliced in place: a MIF
    carries six TLV 2 records and the hostname length moves all of them, so
    per-TLV splicing would invalidate every span after the first edit.
    """
    spans, end, ok = tlvs(frame)
    if not ok:
        return None
    if not ident.get("old_uuid"):
        ident["old_uuid"] = collect_old_uuid(spans, frame)
    elif isinstance(ident["old_uuid"], str):
        ident["old_uuid"] = ident["old_uuid"].encode()

    out = bytearray(frame[:TLV_START])
    for t, ln, vs in spans:
        value = bytes(frame[vs:vs + ln])
        if t == 2:
            new = rewrite_tlv2(value, ident)
            if new is None:
                return None
            value = new
        elif t == 16 and ident.get("uuid"):
            # `03 <len> <name> c0 0c`; the name is the service instance UUID,
            # the same string the SRV rdata carries.
            name = ident["uuid"].encode()
            if len(name) > 255:
                return None
            value = bytes([0x03, len(name)]) + name + b"\xc0\x0c"
        out.append(t)
        out += len(value).to_bytes(2, "little")
        out += value
    out += frame[end:]

    new = bytes(out)
    if new == frame:
        return frame
    _, _, ok = tlvs(new)
    if not ok:
        return None
    return new


def leftovers(frame, ident):
    """Identity strings of the template peer that survive in `frame`."""
    bad = []
    for key in ("old_host", "old_display", "old_sid", "old_at", "old_uuid"):
        val = ident.get(key)
        if not val:
            continue
        needle = val if isinstance(val, bytes) else val.encode()
        if needle in frame:
            bad.append("%s=%r" % (key, val))
    return bad


def selftest():
    """Round-trip the embedded template with the identity it already carries.

    This is the regression guard for the parser: the TLV 2 walker, the
    record splitter, the name/label splitter and the re-emitter all have to
    agree, or a frame rewritten with its own identity comes back different.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "..", "crates", "filin-rs", "templates", "mif.bin")
    try:
        with open(path, "rb") as f:
            frame = f.read()
    except OSError as exc:
        print("selftest: cannot read %s: %s" % (path, exc))
        return 1

    got = extract_identity(frame)
    if got is None or not got.get("host"):
        print("selftest: template does not parse")
        return 1
    ident = {
        "host": got["host"],
        "display": got["display"],
        "sid": got["sid"],
        "at": got["at"],
        "uuid": got["uuid"],
        "old_uuid": None,
    }
    out = rewrite_mif(frame, dict(ident))
    if out != frame:
        diffs = [i for i, (a, b) in enumerate(zip(frame, out)) if a != b]
        print("selftest: FAIL, %d bytes differ (first at %s)"
              % (len(diffs), diffs[:5]))
        return 1

    # A rewrite to a different identity must change exactly the identity
    # fields and nothing else structurally.
    mine = dict(ident)
    mine.update({"host": "marionette", "display": "Marionette",
                 "sid": "2C9D473F-65EB-4AB7-82C7-757E4B8D06B9",
                 "at": "4656d6de6b8a",
                 "uuid": "8DD0D393-ABA1-4727-B338-75B9F049A66A"})
    out = rewrite_mif(frame, mine)
    if out is None:
        print("selftest: FAIL, rewrite to our identity did not rebuild")
        return 1
    if tlvs(out)[2] is not True:
        print("selftest: FAIL, rewritten chain does not parse")
        return 1
    for key in ("host", "display", "sid", "at", "uuid"):
        val = got.get(key)
        if val and val.encode() in out:
            print("selftest: FAIL, template %s=%r survived" % (key, val))
            return 1
    print("selftest: OK (template round-trips byte-exact, "
          "identity swaps cleanly)")
    return 0


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    if len(args) < 2:
        print(__doc__)
        return 2
    plan, out_path = args[0], args[1]
    ident = {
        "host": "marionette",
        "display": "Marionette",
        "sid": "2C9D473F-65EB-4AB7-82C7-757E4B8D06B9",
        "at": "4656d6de6b8a",
        # Apple uses a lower-case UUID for the service instance and an upper
        # case one for `sid=`, so the defaults follow that.
        "uuid": "8dd0d393-aba1-4727-b338-75b9f049a66a",
    }
    check = False

    i = 2
    while i < len(args):
        if args[i] == "--host":
            ident["host"] = args[i + 1]
        elif args[i] == "--display":
            ident["display"] = args[i + 1]
        elif args[i] == "--sid":
            ident["sid"] = args[i + 1]
        elif args[i] == "--at":
            ident["at"] = args[i + 1]
        elif args[i] == "--uuid":
            ident["uuid"] = args[i + 1]
        elif args[i] == "--check":
            check = True
            i += 1
            continue
        else:
            print("unknown argument %r" % args[i])
            return 2
        i += 2

    for k in ("sid", "uuid"):
        if len(ident[k]) != 36 or ident[k].count("-") != 4:
            print("%s must be a 36-character UUID" % k)
            return 2
    if len(ident["at"]) != 12:
        print("at must be 12 hex characters")
        return 2

    n = 0
    changed = 0
    problems = []
    old = None
    with open(plan) as fin, open(out_path, "w") as fout:
        for line in fin:
            m = PLAN.match(line.strip())
            if not m:
                fout.write(line)
                continue
            off, kind, ln, hexs = m.group(1), m.group(2), m.group(3), m.group(4)
            frame = bytes.fromhex(hexs)
            if kind == "MIF":
                if old is None:
                    old = extract_identity(frame) or {}
                    for k in ("host", "display", "sid", "at"):
                        ident["old_" + k] = old.get(k)
                    ident["old_uuid"] = (old.get("old_uuid")
                                         or old.get("uuid"))
                new = rewrite_mif(frame, ident)
                if new is None:
                    print("MIF at %s did not rebuild; left unchanged" % off)
                    fout.write(line)
                    continue
                if new != frame:
                    changed += 1
                _, _, ok = tlvs(new)
                if not ok:
                    print("MIF at %s: rebuilt chain does not parse" % off)
                    return 1
                frame = new
                if check:
                    bad = leftovers(frame, ident)
                    if bad:
                        problems.append("MIF at %s: %s" % (off, ", ".join(bad)))
            fout.write("%s %s %d %s\n" % (off, kind, len(frame), frame.hex()))
            n += 1

    print("ours:    host=%s display=%s sid=%s at=%s uuid=%s"
          % (ident["host"], ident["display"], ident["sid"], ident["at"],
             ident["uuid"]))
    if old:
        print("template: host=%r display=%r sid=%s at=%s uuid=%s"
              % (old.get("host"), old.get("display"), old.get("sid"),
                 old.get("at"), old.get("old_uuid") or old.get("uuid")))
    print("rewrote %d frames (%d MIF changed) -> %s" % (n, changed, out_path))
    if check:
        if problems:
            print("LEFTOVERS:")
            for p in problems:
                print("  " + p)
            return 1
        print("check: no template identity left in any MIF")
    return 0


if __name__ == "__main__":
    sys.exit(main())
