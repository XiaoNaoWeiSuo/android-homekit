# Companion Link (macOS 26/27) — implementation notes

Status: **discovery implemented and verified; pair-verify implemented and
measured to the authentication wall; pair-setup is offered and needs a human to
read the 4-digit code; file transfer not yet possible on this Mac.**

All measurements below were taken on this project's reference setup: a Xiaomi 13
(fuxi) running the custom filin AWDL stack, and a Mac on macOS 27.2 (26B123)
found at `10.12.1.5` with `rapportd` listening on TCP 61156. Values are from
live captures, not from documentation.

> Provenance: an earlier handoff (2026-11-05T07:00Z) recorded "phone → Mac
> unreachable at 10.12.1.64" and "new protocol not implemented". Both are
> superseded here: the Mac's DHCP lease moved to `10.12.1.5` (ARP, ICMP and TCP
> 61156 all work), and Companion Link discovery plus the Rapport handshake are
> implemented. The 78 MiB DMG transfer remains unverified.

## 1. Discovery

macOS 27 no longer advertises `_airdrop._tcp` for third-party clients. The only
service a Mac publishes is:

```
焱林的MacBook._companion-link._tcp.local.  SRV MacBook.local.:61156
  rpAD=ddb895229ac0  rpHI=b745f2587f0a  rpHA=83b04c565445
  rpBA=3C:B1:D4:EE:EF:CF  rpVr=715.2  rpFl=0x20000  rpMac=1
```

The instance name is the human device name — unlike legacy AirDrop, whose
instance was an opaque token. That is why the phone's device list can show real
names now.

**Multicast does not work on this network for this direction.** `mdns-sd` sends
QM queries from the phone (visible in `tcpdump -i wlan0 udp port 5353`) and the
Mac never answers; the Mac's own multicast traffic does reach the phone. A
*unicast* PTR query with the QU bit to `10.12.1.5:5353` is answered immediately
with the full PTR/SRV/TXT/A set.

So `companion.rs` sweeps the local IPv4 subnet:

* startup and every 60 s: full sweep (≤ 1024 hosts, 3 rounds, 2.5 s window);
* every 15 s: targeted refresh of known peer addresses + `/proc/net/arp`
  neighbours (≈ 10 packets, 0.9 s window).

Each host gets a ~44-byte unicast mDNS question; nothing is written to disk and
no TCP connection is opened during discovery.

## 2. Wire format

```
frame   = type:u8 | length:u24be | payload[length]
payload = OPACK value (a dict for every request/response)
```

Frame types used: `5` PV_Start, `6` PV_Next, `7` U_OPACK, `8` E_OPACK,
`16` SessionStartRequest. Once a session is verified the payload is encrypted
with ChaCha20-Poly1305 (12-byte little-endian counter nonce, AAD = the 4-byte
frame header).

OPACK follows pyatv's implementation (`pyatv/support/opack.py`): ints 0x08–0x33,
doubles 0x36, strings 0x40/0x61–0x63, data 0x70/0x91–0x93, lists 0xD0–0xDF,
dicts 0xE0–0xEF. Implemented in `src/rapport.rs`.

Pairing payloads use HAP TLV8 (`type:u8 | length:u8 | value`), implemented in
`rapport::tlv8`.

## 3. Pair-verify (verified against the live server)

```
client → PV_Start  (frame 5)  OPACK { _pd: TLV8{ SeqNo=1, PublicKey=<X25519 pub> },
                                     _auTy: 4 }
server → PV_Next   (frame 6)  OPACK { _pd: TLV8{ EncryptedData(5), SeqNo=2,
                                                 PublicKey(3)=<X25519 pub> } }
```

Key derivation (from pyatv's `SRPAuthHandler.verify1`):

```
key   = HKDF-SHA512(ikm = X25519(shared), salt = "Pair-Verify-Encrypt-Salt",
                    info = "Pair-Verify-Encrypt-Info", len = 32)
plain = ChaCha20-Poly1305(key, nonce = 00 00 00 00 + "PV-Msg02", data = EncryptedData)
```

**The nonce order matters**: HAP prepends four zero bytes and puts the 8-byte
ASCII label last (`Chacha20Cipher._pad_nonce`). Putting the label first makes
every decryption fail authentication — this cost one debugging round.

The decrypted M2 contains TLV types `[1, 10]`:

```
Identifier = 8C9BBAF-B523-4E6C-B9CE-C3795972BE66   (36-byte UUID)
Signature  = 64 bytes                              (Ed25519 over the transcript)
```

M3 (client identifier + signature over `client_pub || client_id || server_pub`,
encrypted with `PV-Msg03`) was sent with a locally generated Ed25519 identity and
was rejected:

```
server → PV_Next  TLV types [7, 6]   ERROR = 2 (Authentication)
```

## 4. What blocks the session

* `PV_Start` is answered even for an unknown client, so the protocol entry point
  is fine.
* `M3` is rejected with authentication error 2: the server looks the client up
  in its pairing database and does not find it.
* `PS_Start` (pair-setup, method 0/PIN, seq 1) **is answered** when the server
  is not throttling: `PS_Next` carries a 16-byte salt and a 384-byte SRP
  public key B. The Mac's log at the same instant shows
  `sessionType=PairSetupServer, flags=[PerDevice, BuiltInUI], pinType=4Digit`
  and `Show PIN '*'` — the Mac displays a **4-digit code**.
* The pairing session lives on the connection and lasts only ~27 s: closing the
  socket (or waiting too long) yields `Read EOF` and `Hide PIN`.
* Repeated `PS_Start` attempts trigger HAP throttling
  (`PairSetup server throttling for 110 second(s)`, then 67 s); the reply then
  carries TLV `[7, 8, 6]` with `ERROR = 3` (Backoff).
* The PIN is not machine-readable from the host side: full-screen and
  window-specific screenshots (loginwindow, Notification Center, Finder
  AirDrop) do not contain it, the notification database is blocked by TCC, and
  the Accessibility API returns `kAXErrorCannotComplete`. Pairing therefore
  needs a human to read the code.
* HAP-compatible SRP (RFC 5054 group 3072, SHA-512, HAP `M1`) is implemented in
  `rapport::srp`; `examples/pair_setup.rs` runs the whole flow
  (`PS_Start → PIN → seq 3 → verify M2 → seq 5/6 → credentials`).
* Unauthenticated OPACK is ignored entirely: `_systemInfo` (U_OPACK and
  E_OPACK) and `_sessionStart` for `com.apple.sharingd.AirDrop` all time out,
  and the connection only carries the initial NoOp.

Conclusion: a non-Apple client cannot establish an authenticated Companion Link
session with this Mac, and the AirDrop app service sits behind that session.

## 5. The legacy 8770 path (measured, not the macOS 27 transport)

* The 8770 listener appears only in short windows while Finder's AirDrop pane is
  active (`lsof -nP -iTCP:8770` → `sharingd ... TCP *:8770 (LISTEN)`).
* It is reachable **only over AWDL**: from the phone, IPv4
  `10.12.1.5:8770` fails while `fe80::6ca1:81ff:fe28:a8d:8770` (the Mac's
  `awdl0`) returns `nc` rc=0. The AWDL link itself is fast: ping 20–37 ms,
  TCP 61156 rc=0.
* Our legacy client completes TCP/TLS but `POST /Discover` fails with
  `UnexpectedEof` — the server closes. Mac-side logs show sharingd using
  **TLS 1.3 with ALPN `h3` and QUIC** over awdl0, i.e. macOS 27 AirDrop is
  HTTP/3/QUIC, and 8770 is a compatibility endpoint that does not serve the old
  HTTPS client.
* The phone advertising `_airdrop._tcp` on wlan0 for 40 s did not make the Mac
  open a receiver.

## 6. Code map

| Path | Purpose |
|---|---|
| `src/dns.rs` | unicast QU query encoder, response parser (PTR/SRV/TXT/A/AAAA, compression) |
| `src/companion.rs` | subnet sweep, targeted refresh, ARP neighbours, `rp*` TXT, `_airdrop._tcp` endpoint resolution |
| `src/rapport.rs` | frame codec, OPACK, TLV8, pair-verify crypto (X25519/HKDF-SHA512/ChaCha20-Poly1305) |
| `src/netutil.rs` | `mac_to_link_local` (EUI-64 + U/L flip) for AWDL peer addresses |
| `examples/companion_scan.rs` | on-device discovery smoke test (`--host`, `--parse` modes) |
| `examples/awdl_scan.rs` | browse mDNS on awdl0 |
| `examples/announce_probe.rs` | advertise `_airdrop._tcp` on wlan0 to test receiver triggering |
| `examples/rapport_probe.rs` | talk to 61156 (unauthenticated OPACK + PV_Start) |
| `examples/pv_probe.rs` | clean pair-verify probe: decrypts M2, sends M3 |
| `examples/ps_probe.rs` | pair-setup probe (PS_Start) |

Reproduce discovery on the phone:

```bash
adb push target/aarch64-linux-android/release/examples/companion_scan /data/local/tmp/
adb shell 'su -c "timeout 30 /data/local/tmp/companion_scan wlan0 20"'
```

Reproduce the handshake (any host on the LAN):

```bash
adb shell 'su -c "timeout 25 /data/local/tmp/pv_probe 10.12.1.5 61156"'
adb shell 'su -c "timeout 20 /data/local/tmp/ps_probe 10.12.1.5 61156"'
```

## 7. Open questions

1. Whether an Apple-issued pairing can ever be established for a third-party
   client (pair-setup is silent; `rpFl=0x20000` says no).
2. The exact HTTP/3 app-service handshake for AirDrop over QUIC on AWDL — the
   Mac's own logs show ALPN `h3`, but no `_asquic` record belonging to it was
   observed, and the app service is expected to require the same session.
3. Throughput for the legacy path (78 MiB DMG test) is still unverified.
