#!/system/bin/sh
# AWDL announce + unicast probe sweep, with positive and negative controls.
#
# What this measures: whether a unicast 802.11 data frame addressed to a real
# Apple peer is ACKed at the MAC layer. An ACK needs no peer-table entry and no
# AWDL synchronisation -- the receiver's hardware answers any frame addressed to
# its MAC while it is awake on that channel -- so an ACK is proof that the frame
# was radiated, arrived, and was heard by that peer. Broadcast announces are
# never ACKed, so they can only ever report "submitted".
#
# Two controls run first, on the associated channel, with no channel change:
#   positive  -> the AP's own BSSID must be ACKed
#   negative  -> an unused random MAC must NOT be ACKed
# If the positive control fails the transmit path is broken; if the negative
# control is ACKed the TX-status signal is meaningless. Only then is the sweep
# to the Apple peer worth reading.
#
# ACK outcomes come from the injector itself (it joins the nl80211 "mlme" group
# and matches NL80211_CMD_FRAME_TX_STATUS). Do NOT read them from `iw event`:
# its stdout is block-buffered, so killing it truncates the tail of the log and
# silently undercounts.
#
# ROC is capped at 2000 ms by this wiphy: 2000 is accepted, 2048 returns -EINVAL.
set -u

PEER="${1:-02:57:dc:2d:32:26}"
GHOST="${2:-02:00:00:00:00:99}"
ROC_MS="${3:-2000}"
ANNOUNCES="${4:-16}"
PROBES="${5:-38}"
WORK=/data/local/tmp
EMIT="$WORK/awdl-emit"
TX="$WORK/marionette-awdl-mgmt-tx"
IW=/system/bin/iw

MAC="$(cat /sys/class/net/wlan0/address)"
LINK="$($IW dev wlan0 link 2>/dev/null)"
AP="$(echo "$LINK" | sed -n 's/^Connected to \([0-9a-f:]*\).*/\1/p')"
HOME_FREQ="$(echo "$LINK" | sed -n 's/.*freq: \([0-9]*\).*/\1/p')"
[ -n "$HOME_FREQ" ] || HOME_FREQ=5220
[ -n "$AP" ] || AP="$GHOST"

echo "iface_mac=$MAC peer=$PEER ap=$AP ghost=$GHOST home_freq=$HOME_FREQ roc_ms=$ROC_MS"

summary() { sed -n 's/^plan_frames=.*unicast_acked=\([0-9]*\) unicast_noack=\([0-9]*\) unicast_no_status=\([0-9]*\).*/acked=\1 noack=\2 no_status=\3/p' "$1"; }

run_plan() { # <freq> <plan> <log>
	$TX wlan0 "$1" --plan "$2" > "$3" 2>&1
	printf '  %s\n' "$(summary "$3")"
}

# --- controls ----------------------------------------------------------------
echo
echo "=== control: positive (AP $AP, freq $HOME_FREQ, no ROC) ==="
$EMIT --mac "$MAC" --name marionette --channel 44 --announces 2 \
	--dst "$AP" --data 10 --data-interval-ms 40 > "$WORK/plan-pos.txt" 2>/dev/null
run_plan "$HOME_FREQ" "$WORK/plan-pos.txt" "$WORK/tx-pos.log"

echo
echo "=== control: negative (ghost $GHOST, freq $HOME_FREQ, no ROC) ==="
$EMIT --mac "$MAC" --name marionette --channel 44 --announces 2 \
	--dst "$GHOST" --data 10 --data-interval-ms 40 > "$WORK/plan-neg.txt" 2>/dev/null
run_plan "$HOME_FREQ" "$WORK/plan-neg.txt" "$WORK/tx-neg.log"

# --- sweep -------------------------------------------------------------------
echo
echo "=== sweep: $PEER across the three social channels ==="
for pair in "2437:6" "5220:44" "5745:149"; do
	freq="${pair%%:*}"
	chan="${pair##*:}"
	plan="$WORK/plan-$freq.txt"

	$EMIT --mac "$MAC" --name marionette --channel "$chan" \
		--announces "$ANNOUNCES" --mif --dst "$PEER" \
		--data "$PROBES" > "$plan" 2>"$WORK/emit-$freq.err"

	: > "$WORK/roc-$freq.log"
	$IW dev wlan0 roc start "$freq" "$ROC_MS" > "$WORK/roc-$freq.log" 2>&1 &
	ROC_PID=$!
	sleep 0.25

	if [ -s "$WORK/roc-$freq.log" ]; then
		echo "freq=$freq ROC REFUSED: $(cat "$WORK/roc-$freq.log")"
		wait "$ROC_PID" 2>/dev/null
		continue
	fi

	echo "freq=$freq chan=$chan frames=$(grep -c '^[0-9]' "$plan")"
	run_plan "$freq" "$plan" "$WORK/tx-$freq.log"
	wait "$ROC_PID" 2>/dev/null
	sleep 0.5
done

echo
echo "=== health ==="
$IW dev wlan0 link 2>&1 | head -3
GW="$(ip route | sed -n 's/^default via \([0-9.]*\).*/\1/p' | head -1)"
ping -c 3 -W 1 "$GW" 2>&1 | tail -2
