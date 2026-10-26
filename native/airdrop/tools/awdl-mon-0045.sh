#!/system/bin/sh
# Can candidate-0045 (the capture-slot build) pin the station to an AWDL social
# channel with marionette_awdl_sta_monitor=1, and stand a monitor vdev up there?
#
# The channel-44 association regression was bisected on candidate-0046 only
# (assoc-gate-bisect.sh).  Patch 0009's concurrency allowance is what lets a
# monitor vdev be created at all, so if 0045 pins cleanly the whole direct
# AWDL measurement comes back: monitor vdev + capture slot + tcpdump, which is
# the only receive path that has ever actually seen AWDL frames
# (marionette_mon_post_awdl=1020, 50 287-frame pcap).
#
# Usage: awdl-mon-0045.sh <bssid> <channel> [hold-seconds]
set -u

PIN=${1:-70:3a:73:a2:28:14}
CH=${2:-44}
FREQ=$((5000 + 5 * CH))
HOLD=${3:-25}
SSID="LIMO-5G ceshi"
PW=limo@202509
CFG=/data/local/tmp/marionette-cfg80211-0043.ko
CAND=/data/local/tmp/marionette-candidate-0045.ko
P=/sys/module/kiwi_v2/parameters

say() { echo "== $*"; }
freq() { iw dev wlan0 info 2>/dev/null | sed -n 's/.*channel [0-9]* (\([0-9]*\) MHz).*/\1/p'; }

say "loading candidate-0045 with sta_monitor=1"
input keyevent 224 >/dev/null 2>&1
svc wifi disable >/dev/null 2>&1
/system/bin/sleep 4
for m in kiwi_v2 mac80211 cfg80211; do
	[ -d "/sys/module/$m" ] && rmmod "$m" 2>/dev/null
done
/system/bin/sleep 1
[ -d /sys/module/mac80211 ] || insmod /vendor_dlkm/lib/modules/mac80211.ko 2>/dev/null
insmod "$CFG" || { say "FAIL: cfg80211 insmod"; svc wifi enable >/dev/null 2>&1; exit 2; }
# shellcheck disable=SC2086
insmod "$CAND" marionette_awdl_mgmt_rx=1 marionette_awdl_roc_rx=1 \
	marionette_awdl_sta_monitor=1 marionette_awdl_monitor_raw_rx=1 || {
	say "FAIL: candidate-0045 insmod"
	svc wifi enable >/dev/null 2>&1
	exit 2
}
say "params: sta_monitor=$(cat $P/marionette_awdl_sta_monitor 2>/dev/null) mgmt_rx=$(cat $P/marionette_awdl_mgmt_rx 2>/dev/null) capture_ms=$(cat $P/marionette_awdl_capture_ms 2>/dev/null) (mode $(stat -c %a $P/marionette_awdl_capture_ms 2>/dev/null))"
setprop ctl.restart wificond
svc wifi enable >/dev/null 2>&1
/system/bin/sleep 6

say "attempting to pin the station to $PIN (channel $CH)"
NID=$(cmd wifi list-networks 2>/dev/null | awk '/LIMO-5G ceshi/ {print $1; exit}')
[ -n "$NID" ] && cmd wifi forget-network "$NID" >/dev/null 2>&1
logcat -c 2>/dev/null
cmd wifi connect-network "$SSID" wpa2 "$PW" -b "$PIN" -r none >/dev/null 2>&1
i=0
while [ $i -lt 30 ]; do
	[ "$(freq)" = "$FREQ" ] && break
	/system/bin/sleep 1
	i=$((i + 1))
done
say "pinned: freq=$(freq) $(iw dev wlan0 link 2>/dev/null | head -1)"
say "supplicant tail:"
logcat -d 2>/dev/null | grep -i wpa_supplicant | grep -iE "Trying|Associated with|ASSOC-REJECT" | tail -3 | sed 's/^/  /'
say "driver tail:"
dmesg | grep -E "Connect with $PIN" | tail -2 | sed 's/^/  /'

if [ "$(freq)" != "$FREQ" ]; then
	say "RESULT: PIN_FAILED - candidate-0045 cannot put the station on channel $CH either"
	exit 1
fi

say "creating the monitor vdev (station is associated, as patch 0009 requires)"
iw phy phy0 interface add awdlmon type monitor 2>&1 | sed 's/^/  /'
if ! iw dev 2>/dev/null | grep -q 'Interface awdlmon'; then
	say "RESULT: MONITOR_CREATE_FAILED"
	exit 4
fi
ip link set awdlmon up || { iw dev awdlmon del; say "RESULT: MONITOR_UP_FAILED"; exit 4; }
say "monitor: $(iw dev awdlmon info 2>/dev/null | sed -n 's/.*channel \([0-9]*\).*/\1/p' | head -1)"

say "triggering the capture slot (${HOLD}s) so monitor_refresh_filters() runs"
echo "$((HOLD * 1000))" >"$P/marionette_awdl_capture_ms" 2>/dev/null ||
	say "note: could not write capture_ms"
/system/bin/sleep 2
say "capture_ms=$(cat "$P/marionette_awdl_capture_ms" 2>/dev/null) hold=$(cat "$P/marionette_awdl_capture_hold" 2>/dev/null)"
dmesg | grep -i MARIONETTE_CAPTURE | tail -3 | sed 's/^/  /'

say "monitor RX counters after the slot:"
for c in marionette_mon_desc_total marionette_mon_post_total marionette_mon_post_awdl \
	 marionette_mon_hdr_awdl marionette_awdl_rx_wmi marionette_mgmt_rx_wmi; do
	[ -f "$P/$c" ] && echo "  $c=$(cat "$P/$c")"
done

say "capturing ${HOLD}s on awdlmon"
rm -f /data/local/tmp/mon0045.pcap
/system/bin/timeout -k 3 "$HOLD" tcpdump -i awdlmon -c 200000 -w /data/local/tmp/mon0045.pcap 2>&1 | tail -2
say "pcap=$(stat -c %s /data/local/tmp/mon0045.pcap 2>/dev/null) bytes"

say "link still up? $(iw dev wlan0 link 2>/dev/null | head -1)"
ip link set awdlmon down 2>/dev/null
iw dev awdlmon del 2>/dev/null
say "RESULT: DONE"
