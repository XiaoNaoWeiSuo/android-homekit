#!/system/bin/sh
# awdl-roc-tx.sh - hold the capture filters on and transmit inside a normal
# remain-on-channel reservation on the station.
#
# This is the coexistence-safe alternative to the bounded capture slot.  The
# slot moved the monitor vdev with wlan_hdd_set_mon_chan, which policy manager
# never sees, so the station's beacon watchdog kept running and the link died
# after a run of missed beacons - every following transmission then failed with
# -ENOTCONN.  A reservation on the station is scheduled by policy manager, which
# yields beacon windows, so the link survives; and patch 0045 holds the
# monitor's full RX filters on for the duration instead of moving the channel.
#
# Usage: awdl-roc-tx.sh [freq] [plan] [plan_ms] [capture_extra_ms]
set -u

FREQ=${1:-2437}
PLAN=${2:-/data/local/tmp/plan-dense.txt}
PLAN_MS=${3:-1600}
EXTRA=${4:-2500}
HOLD=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold
TX=/data/local/tmp/marionette-awdl-mgmt-tx
CAP=/data/local/tmp/marionette-awdl-capture
OUT=/data/local/tmp/roc-tx
SELF=$(cat /sys/class/net/wlan0/address | tr -d ':')

mkdir -p "$OUT"
rm -f "$OUT"/tx.log "$OUT"/cap.log

if [ ! -f "$HOLD" ]; then
	echo "capture_hold missing: load a driver with patch 0045" >&2
	exit 4
fi
if ! iw dev wlan0 link 2>/dev/null | grep -q '^Connected'; then
	echo "wlan0 not associated" >&2
	exit 3
fi
if ! iw dev awdlmon info >/dev/null 2>&1; then
	iw phy phy0 interface add awdlmon type monitor || exit 3
	ip link set awdlmon up || exit 3
	/system/bin/sleep 2
fi

CAP_MS=$(( PLAN_MS + EXTRA ))
$CAP awdlmon "$CAP_MS" > "$OUT"/cap.log 2>&1 &
CAP_PID=$!
/system/bin/sleep 0.4

if ! echo 1 > "$HOLD"; then
	echo "capture_hold=1 refused" >&2
	kill $CAP_PID 2>/dev/null
	exit 5
fi

$TX wlan0 "$FREQ" 1 --plan "$PLAN" --roc > "$OUT"/tx.log 2>&1
echo 0 > "$HOLD"

wait $CAP_PID

echo "=== tx ==="
grep -E 'plan_frames|REJECTED|err=' "$OUT"/tx.log | head -4
echo "=== capture ==="
grep CAPTURE_SUMMARY "$OUT"/cap.log
echo "=== received freq histogram ==="
grep -o 'freq=[0-9]*' "$OUT"/cap.log | sort | uniq -c | sort -rn
echo "=== AWDL frames by transmitter ==="
awk -v self="$SELF" '/^AWDL / {
	h = $5;
	tx = substr(h, 21, 12);
	rx = substr(h, 9, 12);
	n[tx]++
	if (tx == self) ours++
	if (rx == self) to_us++
}
END {
	for (k in n) printf "  tx=%s count=%d%s\n", k, n[k], (k == self ? "  <== us" : "");
	printf "our_tx_seen=%d frames_addressed_to_us=%d\n", ours, to_us;
}' "$OUT"/cap.log
echo "=== link after ==="
iw dev wlan0 link 2>/dev/null | head -1
