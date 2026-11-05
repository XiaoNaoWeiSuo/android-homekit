#!/usr/bin/env python3
"""Minimal Rapport (Companion Link) probe.

Speaks the frame + OPACK format used by rapportd over TCP 61156:
  frame = 1-byte type | 3-byte big-endian payload length | payload
  payload = OPACK dict

Usage: python3 rapport_probe.py [host] [port]

Run against 127.0.0.1 first (no encryption) to learn what an unauthenticated
client is allowed to do; then against the Mac's Wi-Fi address from the phone.
This is a diagnostic, not the production client: the device-side
implementation lives in crates/luftlift-rs/src/rapport.rs.
"""
import json
import socket
import struct
import sys
import time

REQUEST = 2


def pack(obj):
    if obj is None:
        return b"\x04"
    if isinstance(obj, bool):
        return b"\x01" if obj else b"\x02"
    if isinstance(obj, int):
        if obj < 0x28:
            return bytes([obj + 8])
        if obj <= 0xFF:
            return b"\x30" + obj.to_bytes(1, "little")
        if obj <= 0xFFFF:
            return b"\x31" + obj.to_bytes(2, "little")
        if obj <= 0xFFFFFFFF:
            return b"\x32" + obj.to_bytes(4, "little")
        return b"\x33" + obj.to_bytes(8, "little")
    if isinstance(obj, float):
        return b"\x36" + struct.pack("<d", obj)
    if isinstance(obj, str):
        data = obj.encode()
        if len(data) <= 0x20:
            return bytes([0x40 + len(data)]) + data
        if len(data) <= 0xFF:
            return b"\x61" + len(data).to_bytes(1, "little") + data
        return b"\x62" + len(data).to_bytes(2, "little") + data
    if isinstance(obj, bytes):
        if len(obj) <= 0x20:
            return bytes([0x70 + len(obj)]) + obj
        if len(obj) <= 0xFF:
            return b"\x91" + len(obj).to_bytes(1, "little") + obj
        return b"\x92" + len(obj).to_bytes(2, "little") + obj
    if isinstance(obj, (list, tuple)):
        out = bytes([0xD0 + min(len(obj), 0xF)])
        out += b"".join(pack(v) for v in obj)
        if len(obj) >= 0xF:
            out += b"\x03"
        return out
    if isinstance(obj, dict):
        out = bytes([0xE0 + min(len(obj), 0xF)])
        for k, v in obj.items():
            out += pack(k) + pack(v)
        if len(obj) >= 0xF:
            out += b"\x03"
        return out
    raise TypeError(type(obj))


def unpack(data):
    return _unpack(data, [])[0]


def _unpack(data, objects):
    tag = data[0]
    add = True
    if tag == 0x01:
        value, rest = True, data[1:]
        add = False
    elif tag == 0x02:
        value, rest = False, data[1:]
        add = False
    elif tag == 0x04:
        value, rest = None, data[1:]
        add = False
    elif 0x08 <= tag <= 0x2F:
        value, rest = tag - 8, data[1:]
        add = False
    elif tag == 0x36:
        value, rest = struct.unpack("<d", data[1:9])[0], data[9:]
    elif (tag & 0xF0) == 0x30:
        n = 2 ** (tag & 0xF)
        value, rest = int.from_bytes(data[1:1 + n], "little"), data[1 + n:]
    elif 0x40 <= tag <= 0x60:
        n = tag - 0x40
        value, rest = data[1:1 + n].decode("utf-8", "replace"), data[1 + n:]
    elif 0x60 < tag <= 0x64:
        n = tag & 0xF
        ln = int.from_bytes(data[1:1 + n], "little")
        value = data[1 + n:1 + n + ln].decode("utf-8", "replace")
        rest = data[1 + n + ln:]
    elif 0x70 <= tag <= 0x90:
        n = tag - 0x70
        value, rest = data[1:1 + n], data[1 + n:]
    elif 0x91 <= tag <= 0x94:
        n = 1 << ((tag & 0xF) - 1)
        ln = int.from_bytes(data[1:1 + n], "little")
        value, rest = data[1 + n:1 + n + ln], data[1 + n + ln:]
    elif (tag & 0xF0) == 0xD0:
        count = tag & 0xF
        out = []
        ptr = data[1:]
        while True:
            if count == 0xF:
                if ptr[0] == 0x03:
                    ptr = ptr[1:]
                    break
            elif len(out) >= count:
                break
            v, ptr = _unpack(ptr, objects)
            out.append(v)
        value, rest, add = out, ptr, False
    elif (tag & 0xE0) == 0xE0:
        count = tag & 0xF
        out = {}
        ptr = data[1:]
        while True:
            if count == 0xF:
                if ptr[0] == 0x03:
                    ptr = ptr[1:]
                    break
            elif len(out) >= count:
                break
            k, ptr = _unpack(ptr, objects)
            v, ptr = _unpack(ptr, objects)
            out[k] = v
        value, rest, add = out, ptr, False
    else:
        raise TypeError(hex(tag))
    if add:
        objects.append(value)
    return value, rest


class Conn:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.buf = b""
        self.xid = 0

    def send(self, frame_type, payload):
        header = bytes([frame_type]) + len(payload).to_bytes(3, "big")
        self.sock.sendall(header + payload)

    def recv_frame(self, timeout=5):
        self.sock.settimeout(timeout)
        while True:
            if len(self.buf) >= 4:
                length = 4 + int.from_bytes(self.buf[1:4], "big")
                if len(self.buf) >= length:
                    frame, self.buf = self.buf[:length], self.buf[length:]
                    return frame[0], frame[4:]
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("peer closed")
            self.buf += chunk

    def request(self, frame_type, identifier, content, timeout=5):
        self.xid += 1
        payload = pack({"_i": identifier, "_t": REQUEST, "_x": self.xid, "_c": content})
        self.send(frame_type, payload)
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                ftype, data = self.recv_frame(deadline - time.time())
            except socket.timeout:
                break
            if not data:
                print(f"  <- type={ftype} (empty)")
                continue
            try:
                obj = unpack(data)
            except Exception as exc:  # noqa: BLE001
                print(f"  <undecodable type={ftype}: {exc} raw={data[:60].hex()}>")
                continue
            print(f"  <- type={ftype} {json.dumps(obj, default=str, ensure_ascii=False)[:400]}")
            if isinstance(obj, dict) and (obj.get("_x") == self.xid or "_c" in obj):
                return obj
        return None


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 61156
    c = Conn(host, port)
    print(f"connected to {host}:{port}")

    info = {
        "_bf": 0, "_cf": 512, "_clFl": 128, "_i": "aa:bb:cc:dd:ee:ff",
        "_idsID": "aa:bb:cc:dd:ee:ff", "_pubID": "aa:bb:cc:dd:ee:ff",
        "_sf": 256, "_sv": "170.18", "model": "Android", "name": "probe",
    }
    print("== _systemInfo U_OPACK (7)")
    c.request(7, "_systemInfo", info)

    print("== _systemInfo E_OPACK (8)")
    c.request(8, "_systemInfo", info)

    print("== _sessionStart U_OPACK (7) com.apple.sharingd.AirDrop")
    c.request(7, "_sessionStart", {"_srvT": "com.apple.sharingd.AirDrop", "_sid": 12345})

    print("== SessionStartRequest frame (16) com.apple.sharingd.AirDrop")
    c.xid += 1
    c.send(16, pack({"_srvT": "com.apple.sharingd.AirDrop", "_sid": 12345, "_x": c.xid}))
    deadline = time.time() + 5
    while time.time() < deadline:
        try:
            ftype, data = c.recv_frame(deadline - time.time())
        except socket.timeout:
            break
        if not data:
            print(f"  <- type={ftype} (empty)")
            continue
        try:
            print(f"  <- type={ftype} {json.dumps(unpack(data), default=str, ensure_ascii=False)[:400]}")
        except Exception as exc:  # noqa: BLE001
            print(f"  <undecodable type={ftype}: {exc} raw={data[:60].hex()}>")


if __name__ == "__main__":
    main()
