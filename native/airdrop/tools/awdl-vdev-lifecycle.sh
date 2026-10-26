#!/system/bin/sh
# Reproduce and time the monitor-vdev teardown, then dump the logs that
# explain it.  Run on device as root.
#
# What we know: creating awdlmon while the STA is associated works, frames
# flow, but after ~20-40 s the driver deletes the vdev and the STA drops.
# This script timestamps exactly when each of those happens and snapshots
# dmesg/logcat at the transition.
#
# Usage: awdl-vdev-lifecycle.sh [timeout-seconds]
set -u

TIMEOUT=${1:-60}
P=/sys/module/kiwi_v2/parameters
SSID="LIMO-5G ceshi"
PW=limo@202509
PIN=70:3a:73:a2:26:68
LOG=/data/local/tmp/vdev-lifecycle.log

say() { echo "== $*"; }
now_ms() { cut -d' ' -f1 /proc/uptime | tr -d '.'; }
vdev() { iw dev 2>/dev/null | grep -c 'Interface awdlmon'; }
sta() { iw dev wlan0 link 2>/dev/null | sed -n 's/^Connected to \([^ ]*\).*/\1/p'; }
ctr() { cat "$P/marionette_mon_post_awdl" 2>/dev/null; }

: >"$LOG"
say "phase 0: clean start" | tee -a "$LOG"
ip link set awdlmon down 2>/dev/null
iw dev awdlmon del 2>/dev/null
/system/bin/sleep 1
cmd wifi enable-scanning enabled >/dev/null 2>&1
if [ -z "$(sta)" ]; then
	cmd wifi connect-network "$SSID" wpa2 "$PW" -b "$PIN" -r none >/dev/null 2>&1
	i=0
	while [ $i -lt 25 ]; do
		[ -n "$(sta)" ] && break
		/system/bin/sleep 1
		i=$((i + 1))
	done
fi
say "sta=$(sta) vdev=$(vdev) mon_post_awdl=$(ctr)" | tee -a "$LOG"
[ -z "$(sta)" ] && { say "ABORT: station not associated"; exit 2; }

say "phase 1: create the monitor vdev" | tee -a "$LOG"
T0=$(now_ms)
dmesg -c >/dev/null 2>&1
logcat -c 2>/dev/null
iw phy phy0 interface add awdlmon type monitor 2>&1 | tee -a "$LOG"
ip link set awdlmon up 2>&1 | tee -a "$LOG"
say "created: vdev=$(vdev) t=0ms" | tee -a "$LOG"

say "phase 2: poll every 1 s" | tee -a "$LOG"
elapsed=0
vdev_gone_at=""
sta_gone_at=""
while [ "$elapsed" -lt "$TIMEOUT" ]; do
	/system/bin/sleep 1
	elapsed=$((elapsed + 1))
	v=$(vdev)
	s=$(sta)
	echo "t=${elapsed}s vdev=$v sta=${s:-none} mon_post_awdl=$(ctr)" | tee -a "$LOG"
	if [ "$v" -eq 0 ] && [ -z "$vdev_gone_at" ]; then
		vdev_gone_at=$elapsed
		say "VDEV GONE at t=${elapsed}s" | tee -a "$LOG"
		say "--- dmesg since creation ---" | tee -a "$LOG"
		dmesg | tail -60 | tee -a "$LOG"
	fi
	if [ -z "$s" ] && [ -z "$sta_gone_at" ]; then
		sta_gone_at=$elapsed
		say "STA LOST at t=${elapsed}s" | tee -a "$LOG"
		say "--- dmesg tail ---" | tee -a "$LOG"
		dmesg | tail -40 | tee -a "$LOG"
	fi
	# Keep polling until both events or timeout.
	[ -n "$vdev_gone_at" ] && [ -n "$sta_gone_at" ] && break
done

say "phase 3: summary" | tee -a "$LOG"
say "vdev_gone_at=${vdev_gone_at:-never} sta_gone_at=${sta_gone_at:-never}" | tee -a "$LOG"
say "--- logcat wifi (filtered) ---" | tee -a "$LOG"
logcat -d 2>/dev/null | grep -iE "awdlmon|monitor|hdd_add|vdev" | tail -30 | tee -a "$LOG"
say "done; full log at $LOG"
