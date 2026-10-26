#!/system/bin/sh
# Verify that filin transmits on the station's real channel.
#
# The failure this exists to catch: NL80211_CMD_FRAME carries an explicit
# WIPHY_FREQ. If that frequency is not the one the radio is sitting on,
# cfg80211 accepts the request (submitted counter climbs, zero errors) and
# nobody on the station's channel ever hears the frame. Everything looks
# healthy and the phone stays invisible.
#
# Usage: channel-probe.sh [seconds]
set -u
SECS=${1:-12}
MON=awdlmon
TX=wlan0
BACKEND=/data/local/tmp/filin
LOG=/data/local/tmp/channel-probe.log
CAPTURE_HOLD=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold

[ -x "$BACKEND" ] || { echo "FAIL: backend missing"; exit 2; }
iw dev "$TX" link 2>/dev/null | grep -q '^Connected' || { echo "FAIL: not associated"; exit 3; }

echo "station: $(iw dev "$TX" info | sed -n 's/.*channel \([0-9]*\) (\([0-9]*\) MHz).*/ch\1 = \2 MHz/p')"

ip link set "$TX" up 2>/dev/null
if ! iw dev "$MON" info >/dev/null 2>&1; then
	WIPHY=$(iw dev "$TX" info 2>/dev/null | sed -n 's/.*wiphy \([0-9]*\).*/\1/p' | head -1)
	iw phy "phy${WIPHY:-0}" interface add "$MON" type monitor || { echo "FAIL: no monitor vdev"; exit 4; }
fi
ip link set "$MON" up || { echo "FAIL: monitor up"; exit 4; }
echo 1 > "$CAPTURE_HOLD" 2>/dev/null || { echo "FAIL: capture filter"; exit 5; }
[ -w /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx ] && \
	echo Y > /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx

rm -f "$LOG"
FILIN_TX_IFACE="$TX" "$BACKEND" -i "$MON" -N -f -c 6 --park \
	--http-addr 127.0.0.1:9931 >"$LOG" 2>&1 &
CHILD=$!
/system/bin/sleep "$SECS"
kill -9 "$CHILD" 2>/dev/null
wait "$CHILD" 2>/dev/null

echo "--- channel decision ---"
grep -E "transmitting on the station|transmitting via NL80211_CMD_FRAME|falling back to the anchor" "$LOG"
echo "--- transmit counters ---"
grep -E "injected awdl announce|send failed|MgmtTx" "$LOG" | tail -6
echo "--- errors ---"
grep -icE "error|failed" "$LOG" | sed 's/^/lines matching error|failed: /'

echo 0 > "$CAPTURE_HOLD" 2>/dev/null
ip link set "$MON" down 2>/dev/null
iw dev "$MON" del 2>/dev/null
echo "station after: $(iw dev "$TX" link 2>/dev/null | head -1)"
