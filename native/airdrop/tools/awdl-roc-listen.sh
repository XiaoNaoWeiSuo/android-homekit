#!/system/bin/sh
# awdl-roc-listen.sh - listen on the social channel for N seconds without
# transmitting, using the coexistence-safe mechanism from ROADMAP §14.
#
# Same pairing as awdl-roc-tx.sh minus the transmitter: the capture filters are
# held on with marionette_awdl_capture_hold, and cfg80211 remain-on-channel
# slices of 200 ms keep the radio on the social channel under policy manager's
# supervision, so the station's beeps are protected and the link survives.
#
# Usage: awdl-roc-listen.sh [freq] [seconds]
set -u

FREQ=${1:-2437}
SECS=${2:-10}
HOLD=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold
TX=/data/local/tmp/marionette-awdl-mgmt-tx
CAP=/data/local/tmp/marionette-awdl-capture
OUT=/data/local/tmp/roc-listen

mkdir -p "$OUT"
rm -f "$OUT"/listen.log

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
	# __hdd_mon_open finishes about a second after `ip link set up`.
	/system/bin/sleep 2
fi

CAP_MS=$(( SECS * 1000 + 1200 ))
$CAP awdlmon "$CAP_MS" > "$OUT"/listen.log 2>&1 &
CAP_PID=$!
/system/bin/sleep 0.4

if ! echo 1 > "$HOLD"; then
	echo "capture_hold=1 refused" >&2
	kill $CAP_PID 2>/dev/null
	exit 5
fi

# One process per slice: the slice ceiling is 200 ms while a monitor vdev
# exists, so continuous coverage is a chain of short reservations.  A short
# local slice of that chain is enough to measure peer periodicity.
i=0
SLICES=$(( SECS * 5 + 2 ))
while [ $i -lt "$SLICES" ]; do
	$TX wlan0 "$FREQ" 1 --roc-only 200 >/dev/null 2>&1
	/system/bin/sleep 0.01
	i=$((i + 1))
done

echo 0 > "$HOLD"
wait $CAP_PID
echo "captured:"; grep CAPTURE_SUMMARY "$OUT"/listen.log
