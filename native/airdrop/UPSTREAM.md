# Marionette Android backend

Upstream: https://github.com/ayourtch-llm/opendrop-rs
Pinned commit: dccc798e244363eb92d35e3c52e9a913188dda91
Imported 2026-10-14. GPL-3.0-only, see LICENSE. This source tree accompanies
the two separate executable helpers shipped with the app. Cargo.lock pins dependencies.

Android modifications: ioctl request ABI casts, bounded netlink waits,
SET_WIPHY monitor tuning, getifaddrs instead of restricted /proc/net reads,
per-transfer acceptance via app-private files, bounded HTTP/decompression,
local-link-only receive connections, exclusive file creation and filename
flattening. Kotlin runs the file-transfer helper without root. Only the radio
helper needs root. Build with ../../build-airdrop-backend.sh.

This is an experimental port, not an Apple-certified accessory. Monitor mode
alone does not establish radio injection, AWDL interoperability, or successful
AirDrop transfers. Record device test evidence in docs/AIRDROP.md.
