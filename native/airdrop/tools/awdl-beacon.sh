#!/system/bin/sh
# awdl-beacon.sh - broadcast AWDL announces continuously for N seconds and
# report whether any Apple peer starts acknowledging us in its own frames.
#
# Why a long run: a 1.5 s burst gets ACKed, but Apple peer discovery is built on
# persistence.  One node has to be seen over many availability windows before a
# neighbour is recorded, so the question "will the Mac list us" cannot be
# answered by a short plan.
#
# Why the plan is rebuilt for every burst: every announce carries its own
# timestamp (phy_tx/target_tx) and an availability-window counter.  A plan
# emitted with a fixed origin stamps those from zero, so each burst restarts the
# advertised phase and a peer that compares successive bursts concludes the
# sender re-clustered -- it never records us.  `--epoch-now` stamps the plan
# from CLOCK_MONOTONIC at emit time, so burst n and burst n+1 describe one
# continuous schedule.  This is the ROADMAP §14.5 blocker; the harness used to
# replay one pre-built plan file instead.
#
# Usage: awdl-beacon.sh [freq] [seconds] [plan|live]
#   plan   a pre-built plan file to replay unchanged (old behaviour)
#   live   regenerate the plan per burst with a live epoch (default)
set -u

FREQ=${1:-2437}
SECS=${2:-20}
PLAN=${3:-live}
EMIT=${EMIT:-/data/local/tmp/awdl-emit}
TX=${TX:-/data/local/tmp/marionette-awdl-mgmt-tx}
CAP=${CAP:-/data/local/tmp/marionette-awdl-capture}
NAME=${NAME:-marionette}
# Annonces per burst: one PSF per 110 TU (112.64 ms), so ~14 fills a 1.6 s plan.
ANNOUNCES=${ANNOUNCES:-14}
# Startup allowance for the replayer.  The first frame is due this far after the
# plan is generated; generation takes a few ms, so the residual error is well
# inside one availability window (16 TU = 16.4 ms).
LEAD_US=${LEAD_US:-400000}
HOLD=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold
OUT=/data/local/tmp/awdl-beacon
SELF=$(cat /sys/class/net/wlan0/address)
SELFNOCOLON=$(echo "$SELF" | tr -d ':')

# The announce's channel sequence must match the channel we hop to, or the peer
# and we disagree about which slot carries which channel.
case "$FREQ" in
	2437) CH=6 ;;
	5220) CH=44 ;;
	5745) CH=149 ;;
	*) echo "unsupported freq $FREQ (want 2437/5220/5745)" >&2; exit 2 ;;
esac

mkdir -p "$OUT"
rm -f "$OUT"/listen.log "$OUT"/tx.log "$OUT"/emit.log

if ! iw dev wlan0 link 2>/dev/null | grep -q '^Connected'; then
	echo "wlan0 not associated" >&2
	exit 3
fi
if ! iw dev awdlmon info >/dev/null 2>&1; then
	iw phy phy0 interface add awdlmon type monitor || exit 3
	ip link set awdlmon up || exit 3
	/system/bin/sleep 2
fi

# Rebuild the plan from the current clock.  Also re-reads the interface MAC, so
# a reconnect between bursts cannot leave a stale addr2 in the plan (cfg80211
# rejects the whole frame when addr2 differs from the sending interface).
make_plan() {
	if [ "$PLAN" != live ]; then
		cat "$PLAN" > "$OUT"/plan.txt
		return $?
	fi
	"$EMIT" --mac "$SELF" --name "$NAME" --channel "$CH" \
		--announces "$ANNOUNCES" --mif \
		--epoch-now --lead-us "$LEAD_US" > "$OUT"/plan.txt 2>> "$OUT"/emit.log
}

CAP_MS=$(( SECS * 1000 + 2500 ))
$CAP awdlmon "$CAP_MS" > "$OUT"/listen.log 2>&1 &
CAP_PID=$!
/system/bin/sleep 0.4

echo 1 > "$HOLD" || { echo "capture_hold refused" >&2; exit 5; }

# The gap between bursts is not a courtesy.  A continuous ROC chain leaves the
# radio off the home channel for the whole run; measured, that ends with
# "ROAM_TRIGGER BEACON MISS -> ROAM_SCAN -> ROAM_RESULT FAILED NO AP FOUND ->
# BEACON_MISS_FAILURE", because the roam scan cannot hear the AP while the radio
# is parked on 2437.  One plan already lasts ~1.6 s and survives, so the pattern
# below is: transmit for as long as one plan spans, then hand the radio back.
BURST_MS=${4:-1600}
GAP_MS=${5:-2500}
sent_total=0
rejected_total=0
bursten_total=0
plan_fail=0
deadline=$(( $(date +%s) + SECS ))
while [ "$(date +%s)" -lt "$deadline" ]; do
	if ! make_plan; then
		echo "plan generation failed" >&2
		plan_fail=$((plan_fail + 1))
		break
	fi
	$TX wlan0 "$FREQ" 1 --plan "$OUT"/plan.txt --roc >> "$OUT"/tx.log 2>&1
	line=$(grep -E '^plan_frames' "$OUT"/tx.log | tail -1 || true)
	if [ -n "$line" ]; then
		s=$(printf '%s\n' "$line" | sed -n 's/.*plan_frames=\([0-9]*\).*/\1/p')
		r=$(printf '%s\n' "$line" | sed -n 's/.*rejected=\([0-9]*\).*/\1/p')
		[ -n "$s" ] && sent_total=$(( sent_total + s ))
		[ -n "$r" ] && rejected_total=$(( rejected_total + r ))
	fi
	bursten_total=$(( bursten_total + 1 ))
	# The interface MAC flips between the random and the hardware address
	# across a reconnect, which invalidates every frame in the plan (cfg80211
	# requires addr2 to equal the sending interface).  Stop rather than
	# silently measuring a run that cannot have radiated.
	now=$(cat /sys/class/net/wlan0/address | tr -d ':')
	if [ "$now" != "$SELFNOCOLON" ]; then
		echo "MAC CHANGED ${SELFNOCOLON} -> ${now}; results unreliable" >&2
		break
	fi
	if ! iw dev wlan0 link 2>/dev/null | grep -q '^Connected'; then
		echo "LINK LOST mid-run; results unreliable" >&2
		break
	fi
	# Hold with capture_hold off so the monitor goes back to normal RX and the
	# station keeps its link during the gap.
	echo 0 > "$HOLD"
	/system/bin/sleep "0.$(printf '%d' "$GAP_MS")"
	echo 1 > "$HOLD" || break
done

echo 0 > "$HOLD"
wait $CAP_PID

echo "=== tx ==="
echo "plan_frames_total=$sent_total rejected_total=$rejected_total bursts=$bursten_total plan_fail=$plan_fail"
grep -cE '^plan_frames' "$OUT"/tx.log | sed 's/^/plan_runs=/'
grep -E 'FELL_BEHIND' "$OUT"/tx.log | tail -1 || true
echo "=== capture ==="
grep CAPTURE_SUMMARY "$OUT"/listen.log
grep -o 'freq=[0-9]*' "$OUT"/listen.log | sort | uniq -c | sort -rn
echo "=== distinct transmitters ==="
awk -v self="$SELFNOCOLON" '/^AWDL / {
	h = $6;
	tx = substr(h, 21, 12);
	n[tx]++
	if (index(h, self) > 0) mentions++
}
END {
	cnt = 0
	for (k in n) { if (k != self) cnt++ }
	printf "peers=%d frames_mentioning_us=%d\n", cnt, mentions + 0
}' "$OUT"/listen.log
echo "=== who mentions us ==="
awk -v self="$SELFNOCOLON" '/^AWDL / {
	h = $6;
	if (index(h, self) > 0) print $2, substr(h, 21, 12)
}' "$OUT"/listen.log | head -10
echo "=== link after ==="
iw dev wlan0 link 2>/dev/null | head -1
