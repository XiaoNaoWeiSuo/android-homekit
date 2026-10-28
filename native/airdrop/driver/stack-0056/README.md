# WLAN stack 0056 — offline candidate, not a stability release

**2026-10-27 续测**：4轮有界20秒UDP，新增4次正常STOP/自动原厂恢复，末次boot_count78、网关3/3。首次双向往返为手机5/40、Mac3/39；另一轮无共同52信道又0/40，尚未稳定。用户态已修首次选主采用时钟的顺序及统计，未解决固件line#1413；短测无故障不能当长时稳定证明。详见HANDOFF最新条目和 `build/airdrop/ip-probe/continued-20261027.json`。

Update, 2026-10-26: the same module has now completed six normal monitor STOP
cycles on the phone (including three consecutive recreate cycles and two basic
UDP sessions). STOP completion preceded DELETE each time, with no old
DELETE-before-STOP warning. Final stock recovery/IPv4 gateway verification passed.
This is limited normal-path evidence, not SSR/error-path or link stability proof.
See `build/airdrop/ip-probe/progress.json` and the latest `docs/HANDOFF.md`.

This bundle reproduces the current Xiaomi 13 WLAN source modifications from
MiCode commit `518147fd7e0199758efe9bd96a22c9c2ea30bcd9`. It is an alternative to
replaying historical numbered patches, several of which were rejected or
superseded. **Do not apply both this consolidated patch and the numbered series.**
The five additional platform headers are included; all 44 touched files have
before/after SHA256 hashes. The bundle does not modify phone firmware.

## Reproduce

On a clean checkout of the pinned WLAN source:

```sh
python3 apply.py /path/to/wlan             # verify baseline and apply check
python3 apply.py /path/to/wlan --apply
python3 apply.py /path/to/wlan --verify
```

For an existing exact 0054 tree, use the separate 0055 and 0056 patches instead.
Do not apply 0028 or other rejected candidates. Keep existing 0053/0054 fixes.

Kernel source is pinned to `7fad87dc178d09489b74a714e4a525d56f546855` and Android
Clang r450784e 14.0.7. Use the prepared output and actual stock-provider.symvers
with `../build-baseline.sh`; the existing cfg80211 0043 module is a separate
kernel patch/build, not included in this WLAN-only patch. Recorded cfg80211 SHA:
`658d1ef57fc77433d84da6010750704ee189ff71c9c9c7b786d27e4710986425`.

```sh
DRIVER_TARGET=modules DRIVER_PROVIDER_SYMVERS="$PWD/stock-provider.symvers" \
  bash driver/build-baseline.sh "$PWD/kernel" "$PWD/kernel-out" "$PWD/wlan" "$PWD/clang"
python3 driver/check-monitor-stop.py "$PWD/wlan"
python3 driver/check-monitor-timer.py "$PWD/wlan"
```

The real build enables SERIALIZE_VDEV_RESP. The asynchronous monitor-delete
continuation requires that context: a timeout must not call SME mutex-taking
code directly from softirq. The pre-delete gate is disabled in other builds.
It is limited to the existing STA-monitor experiment flag and monitor vdevs.

## Lifecycle changes

- SME intercepts monitor deletion before logical deletion, retaining its normal
  SME/WMA ownership. The state machine sends STOP and waits for the real target-if
  response. It no longer immediately pretends DOWN completed in this path.
- Monitor STOP and pending START responses are wired into monitor operations.
  START failure, STOP send failure, DOWN send failure and response timeout have
  explicit completion/recovery paths. Host timeout is carried separately from
  a successful firmware response.
- A four-state atomic delete gate makes the continuation run once. The DOWN
  notification only marks readiness; the continuation runs after the outer SM
  delivery releases its lock. Nested events use the already-locked SM API.
- The monitor PE session survives until STOP completes. HDD's existing delete
  wait includes START/STOP timeouts. Late duplicate responses are rejected by
  object identity and transaction state. WMA detach does not send another STOP.
- DP's timer start, callback rearm and stop share a lock. Stop closes rearm before
  synchronous cancellation; it releases the lock while joining the callback.
  Concurrent stop callers each wait, and deinit closes admission before freeing.

The loader outside this bundle (`native/airdrop/tools/awdl-stack-load.sh`) now
waits for framework disable and a new running wificond PID, fails on unsuccessful
module unload, serializes load/stock with one lock, and restores stock after a
candidate load/link failure. It requires an explicit WLAN candidate path.
Old 150-second guardian budgets must be reviewed against the longer complete
failure/recovery path before any future deployment; no new radio test is enabled
by this bundle.

## Validation and remaining gates

2026-10-26: full final module build passed with CFI/LTO preserved. SHA256:
`bbb6e00602c6a0ce9c5f5f3222d536117356de957c0a6d63f900c12a7d4ade4e`.
512/512 imported symbol CRCs match the actual provider table. Clean baseline
inputs plus this patch reproduce every candidate file exactly. Host ASan/UBSan
harnesses extract the actual monitor and timer functions; they check ordering,
reference balance, early/duplicate responses, pending START, send failures,
timeout, recovery flag and deterministic timer-stop interleavings. Shell tests
cover restart delay/failure, disable/unload failure, recovery order and locks.

These harnesses do not execute the kernel object manager, firmware, real tasklet
concurrency or the whole scheduler shutdown. In particular, scheduler message
allocation/post failure while shutdown is flushing queues and recovery concurrent
with a pre-delete continuation need further ownership review. Do not describe
this as proof of all SSR races, runtime loadability or communication stability.
The old build warnings remain: shallow scmversion history, missing root
Module.symvers (provider table is supplied), no module BTF without vmlinux.

At the original offline checkpoint no module had been loaded; the update above
records subsequent normal-path validation. Remaining communication work includes
peer/action timing, data admission and scheduling, link ACK attribution, capture
window/STA coexistence and no-AP support. A successful offline lifecycle build
cannot establish stable TCP/TLS or AirDrop file transfer.
