#!/system/bin/sh
# Does the firmware need a remain-on-channel before it will actually put our
# frames on air?
#
# Patch 0044's over-the-air proof is explicit: "an AWDL data frame transmitted
# DURING A REMAIN-ON-CHANNEL to 2412 was ACKed by an AP that exists only on
# that channel". `NL80211_CMD_FRAME` returns success either way, so host-side
# counters cannot tell us whether the firmware transmitted or dropped. This
# runs the session inside a ROC on the station's own frequency — a ROC to the
# channel the radio is already on should not cost the link — and then asks the
# Mac.
#
# Usage: roc-probe.sh [roc-ms] [session-seconds]
set -u
ROC_MS=${1:-120000}
SECS=${2:-30}
TX=wlan0

echo "=== station before"
iw dev "$TX" link | head -2

# Session first: creating/deleting a vdev cancels an outstanding ROC.
su -c "true" 2>/dev/null
MARIONETTE_FORCE_MASTER=--force-master sh /data/local/tmp/session.sh start >/dev/null 2>&1
echo "=== session up"
grep -E "transmitting (on the station|via NL80211)" /data/local/tmp/airdrop-session/filin.log

# ROC on the station's own frequency.
FREQ=$(iw dev "$TX" info | sed -n 's/.*channel \([0-9]*\) (\([0-9]*\) MHz).*/\2/p' | head -1)
echo "=== ROC start on ${FREQ} for ${ROC_MS}ms"
iw dev "$TX" roc start "$FREQ" "$ROC_MS" >/tmp/roc.out 2>&1 &
ROC_PID=$!
sleep 2
echo "roc: $(cat /tmp/roc.out 2>/dev/null)"
echo "station after roc: $(iw dev "$TX" link | head -1)"

echo "=== waiting ${SECS}s"
i=0
while [ $i -lt "$SECS" ]; do
	/system/bin/sleep 5
	i=$((i + 5))
	if ! iw dev "$TX" link 2>/dev/null | grep -q '^Connected'; then
		echo "  link LOST at ${i}s"
		break
	fi
	echo "  t${i}s link=UP"
done

kill -9 "$ROC_PID" 2>/dev/null
echo "=== done; cancelling roc"
iw dev "$TX" roc abort 2>/dev/null || iw dev "$TX" roc stop 2>/dev/null
echo "final: $(iw dev "$TX" link | head -1)"
