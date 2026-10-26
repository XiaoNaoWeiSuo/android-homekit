#!/system/bin/sh
# awdl-tx-in-slot.sh - transmit the replay plan only while the capture slot has
# the radio parked on the social channel, and record what the monitor hears.
#
# Why this exists: on this target NL80211_ATTR_WIPHY_FREQ on CMD_FRAME is
# ignored, so an injected frame leaves on the station's current channel no
# matter what the caller asks for.  A remain-on-channel reservation does not
# fix it either - measured, a 2 s reservation cost 0% ping loss and only pushed
# latency from 14 ms to 52 ms, i.e. policy manager time shares the radio with
# the station instead of yielding it.  The capture slot is the one mechanism
# that deterministically parks the radio on 2437 for a bounded dwell, so the
# transmitter has to fire inside it.
#
# Usage: awdl-tx-in-slot.sh [dwell_ms] [gap_ms] [freq] [runs] [plan]
# Prints a slot-by-slot account of what the monitor saw, split into frames
# carrying our address as transmitter (our own emission, if the monitor loops
# back) and frames addressed *to* us (a peer answering, the decisive signal).
set -u

DWELL=${1:-200}
GAP=${2:-400}
FREQ=${3:-2437}
RUNS=${4:-3}
PLAN=${5:-/data/local/tmp/plan2.txt}
PLAN_MS=${6:-4600}
SLOT=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_ms
TX=/data/local/tmp/marionette-awdl-mgmt-tx
CAP=/data/local/tmp/marionette-awdl-capture
OUT=/data/local/tmp/tx-in-slot
SELF=$(cat /sys/class/net/wlan0/address | tr -d ':')

mkdir -p "$OUT"
rm -f "$OUT"/tx.log "$OUT"/cap.log

if ! iw dev wlan0 link 2>/dev/null | grep -q '^Connected'; then
	echo "wlan0 not associated: the slot refuses with -ENOTCONN" >&2
	exit 3
fi
if ! iw dev awdlmon info >/dev/null 2>&1; then
	# The monitor vdev is what carries AWDL RX; without it there is nothing to
	# capture.  It disappears whenever the station drops, so recreate it rather
	# than making the caller remember the ordering.
	iw phy phy0 interface add awdlmon type monitor || exit 3
	ip link set awdlmon up || exit 3
	# __hdd_mon_open finishes about a second after `ip link set up`; capturing
	# before it does returns total=0 and looks like a dead RX path.
	/system/bin/sleep 2
fi
# A dwell longer than the AP's beacon interval guarantees a missed beacon, and
# policy manager never registers the monitor vdev (dmesg:
# "can't find vdev_id N in pm_conc_connection_list"), so it does not hand the
# station a beacon window inside the dwell.  Ten back-to-back 200 ms slots are
# enough to lose the link to BEACON MISS.
if [ "$DWELL" -ge 100 ]; then
	echo "warning: dwell ${DWELL}ms >= beacon interval; expect beacon misses" >&2
fi

# The plan spans ~4.4 s; cover it plus start-up slack.
SLOTS=$(( (RUNS * PLAN_MS + 400) / (DWELL + GAP) + 2 ))
# Each slot costs the dwell plus roughly 280 ms of channel-switch completion
# waits (measured: six 200 ms slots in a 600 ms loop landed 870-910 ms apart).
# Underestimating that here truncates the capture before the plan ends.
CAP_MS=$(( SLOTS * (DWELL + GAP + 300) + 2500 ))

$CAP awdlmon "$CAP_MS" > "$OUT"/cap.log 2>&1 &
CAP_PID=$!
# __hdd_mon_open finishes about a second after `ip link set up`; capturing
# before it does returns total=0 and looks like a dead RX path.
/system/bin/sleep 1.5

(
	i=0
	while [ $i -lt "$SLOTS" ]; do
		echo "$FREQ:$DWELL" > "$SLOT"
		/system/bin/sleep "0.$(printf '%03d' "$GAP")"
		i=$((i + 1))
	done
) &
SLOT_PID=$!
/system/bin/sleep 0.3

for r in $(seq 1 "$RUNS"); do
	$TX wlan0 "$FREQ" 1 --plan "$PLAN" >> "$OUT"/tx.log 2>&1
done

wait $SLOT_PID
wait $CAP_PID

echo "=== tx ==="
grep -E 'plan_frames|FELL_BEHIND' "$OUT"/tx.log || echo "no tx summary"
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
