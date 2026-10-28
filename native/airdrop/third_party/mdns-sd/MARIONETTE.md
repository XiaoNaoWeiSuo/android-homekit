# Local mDNS transport fix

Source: crates.io mdns-sd 0.11.5, checksum
`8fe7c11a1eb3cfbfcf702d1601c1f5f4c102cdc8665b8a557783ef634741676e`.
Upstream MIT/Apache-2.0 licenses are preserved.

The only production change is in src/service_daemon.rs: both multicast
interface sockets use new_mdns_socket, which sets IPv4 TTL and IPv6 hop
limit to 255 for multicast and unicast sends, with errors propagated.
RFC 6762 section 11: https://www.rfc-editor.org/rfc/rfc6762.html#section-11
The daemon signal socket retains the upstream behavior.

Observed before the fix: Android awdl0 mDNS responses had IPv6 hop-limit 1
(build/airdrop/device-20261026/tap.log). This alone does not prove why an
Apple peer failed discovery. DNS record TTLs and cache behavior are unchanged.

The added unit test opens ephemeral loopback sockets and verifies both families'
multicast/unicast socket options without transmitting. Run independently:

    cargo test --manifest-path native/airdrop/third_party/mdns-sd/Cargo.toml --lib mdns_socket_hop_limits_cover_both_families_and_reply_modes

Android capture after the fix shows hop-limit 255 for the actual AirDrop PTR,
SRV, TXT and AAAA announcements (device-20261026/round2/tap.log).
