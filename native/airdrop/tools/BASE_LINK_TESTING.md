# Basic link validation

The order is driver lifecycle → frames → bidirectional IP → TCP/TLS → AirDrop.
Do not infer a later level from an earlier success.

`awdl-ip-probe.c` is a bounded, two-ended IPv6 UDP echo probe. It sends only a
fixed 64-byte pattern with origin/sequence fields, admits replies from the chosen
peer and port, and checks payload integrity. It does not run DNS-SD, AirDrop,
TLS, or transfer user files. Its exit status means some bidirectional traffic
worked, **not** that loss/latency meet a stability requirement. Assess the printed
counts over the common interval. End the Mac probe before removing the phone
interface; otherwise final routing errors measure teardown rather than the link.

Build on Mac:

```sh
clang -std=c11 -Wall -Wextra -Werror -O2 awdl-ip-probe.c -o mac-probe
```

Build with the pinned Android NDK's `aarch64-linux-android26-clang` using the same
flags for the phone. Mac uses SO_RECV_ANYIF plus IPV6_BOUND_IF; Android uses
SO_BINDTODEVICE. Always obtain current IPv6 addresses/interface indexes first.

```text
awdl-ip-probe IFACE LOCAL_IPV6 PEER_IPV6 LOCAL_PORT SECONDS ORIGIN [PEER_PORT]
```

Use origins 1 and 2 on opposite ends, matching ports unless doing a loopback
self-check. Duration is 1–120 seconds. Two Mac loopback processes can use ::1,
lo0, ports 39921/39922 and opposite peer ports; both reported 6/6 in a 3-second
self-check. This tests the measurement tool, not AWDL.

For paired measurements set `AWDL_IP_PROBE_START_FILE` to a fresh, absent file
on **each endpoint**. Start captures first, launch both probes, and wait for both
logs to say `ready` (the socket is now bound). Only then create both gate files.
The probe waits at most 10 seconds for its gate, exits 2 on timeout, and starts
its requested duration when the gate appears (`started` in the log). Include
these extra 10 seconds in the guardian budget. Never reuse an existing gate.
This prevents startup ICMP port-unreachable: on 2026-10-27 the two received Mac
UDP requests reached the phone about 100 ms before its probe bound the port.
The loopback and absent-gate regressions run with
`python3 tests/test_airdrop_ip_probe.py`; they do not touch wireless interfaces.

Record a live host-side kernel follower before module loading, keep its parent
process alive, and use an independent device-side guardian that stops the radio
before rollback. Retain both raw awdlsink pcap and awdl0 TAP evidence. The updated
loader temporarily quiesces scan-always and restores the user's setting. It also
waits out wificond-death framework recovery before enabling Wi-Fi. Wait for an
IPv4 address before a gateway check and require all probes, not just ping exit 0.
Never reuse the old 150-second test scripts without accounting for the full
bounded load/failure/recovery path.

Offline receive replay:

```sh
cargo run --offline --manifest-path native/airdrop/Cargo.toml -p filin-rs \
  --example pcap-peer-replay -- CAPTURE.pcap LOCAL_MAC
```

It runs actual Action parsing, peer updates, RX admission and pending-RX code on
capture timestamps. It does not simulate firmware, scheduling delay or an IP
stack. The baseline capture from 2026-10-26 releases three unicast packets after
23–32 ms when the corresponding Action appears. The second capture has two
packets without subsequent Mac Action and releases none. Do not describe replay
as wireless success.

Latest phone evidence: `build/airdrop/ip-probe/lease-20261027/link-audit.json`.
The 60-second peer lease retained the Mac but its announced sequence had no
channel 52, while the phone radio was fixed there. A pending unicast stopped
all TAP reads; the new bounded per-peer TX queue removes that blockage without
bypassing channel admission. This has offline regression coverage only.
The session suffered firmware RDDM at approximately 48 seconds with
`0x1366428:0x1c34a98, line#1413`; the root cause remains unknown. No repeat of
that wireless configuration was attempted. Stock restoration and gateway 3/3
passed, boot_count remained 78. A similar SFR string appears in the older
`driver/mld-owner/capture-follow/kernel-001.log`; it is not proof of one cause.

Previous evidence: `build/airdrop/ip-probe/progress.json`. Normal monitor
STOP completed six times without the former STOP/DELETE warning. Real UDP
requests arrived occasionally in both directions, but there were no echo round
trips. Long-term lifecycle, recovery-fault paths and stable IP remain open.
