#!/system/bin/sh
# Does the driver's bounded capture window (marionette_awdl_capture_ms) let the
# station keep its beacons while the monitor vdev is up?
#
# Observed without it: a monitor vdev alone costs the station its AP within
# 20-35 s ("ROAM_TRIGGER ... BEACON MISS", then "NO AP FOUND"). The parameter
# is read-only at runtime, so it has to be set when the module is loaded.
#
# Usage: capture-ms-probe.sh <ms> [watch-seconds]
set -u
MS=${1:-50}
SECS=${2:-90}
MON=awdlmon
TX=wlan0
P=/sys/module/kiwi_v2/parameters

echo "=== loading stack with capture_ms=${MS}"
MARIONETTE_EXTRA_PARAMS="marionette_awdl_capture_ms=${MS}" \
	sh /data/local/tmp/awdl-stack-load.sh load \
	/data/local/tmp/marionette-cfg80211-0043.ko \
	/data/local/tmp/marionette-candidate-0045.ko
echo "capture_ms=$(cat "$P/marionette_awdl_capture_ms" 2>/dev/null)"

ip link set "$TX" up 2>/dev/null
W=$(iw dev "$TX" info | sed -n 's/.*wiphy \([0-9]*\).*/\1/p' | head -1)
iw dev "$MON" info >/dev/null 2>&1 || iw phy "phy${W:-0}" interface add "$MON" type monitor
ip link set "$MON" up
echo 1 > "$P/marionette_awdl_capture_hold"
echo "monitor up, hold=$(cat "$P/marionette_awdl_capture_hold"), link=$(iw dev "$TX" link | head -1)"

i=0
while [ $i -lt "$SECS" ]; do
	/system/bin/sleep 5
	i=$((i + 5))
	if ! iw dev "$TX" link 2>/dev/null | grep -q '^Connected'; then
		echo "LOST at ${i}s"
		break
	fi
done
[ $i -ge "$SECS" ] && echo "survived ${SECS}s with the monitor up"
echo "mon_post_total=$(cat "$P/marionette_mon_post_total" 2>/dev/null)"
