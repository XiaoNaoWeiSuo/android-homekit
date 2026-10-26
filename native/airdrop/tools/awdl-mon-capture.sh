#!/system/bin/sh
# Capture an AWDL social channel with a monitor vdev on candidate-0045.
#
# This is the only receive path that has ever actually seen AWDL frames
# (marionette_mon_post_awdl=1020, 50 287-frame pcap, §22.2 of the ROADMAP).
# Everything the STA vdev can do was measured and found blind: while
# associated the firmware delivers *zero* management frames to the host
# (75 s frozen at mgmt_rx_wmi=510 with WMI_PDEV_SET_RX_FILTER_PROMISCUOUS
# enabled), and scans/ROCs only ever deliver beacons and probe responses,
# never broadcast action frames.
#
# The recipe, all four parts required:
#   1. candidate-0045  - 0046 cannot create a monitor vdev at all
#      ("Adding monitor interface not supported") and its capture slot is
#      unreachable (capture_ms read-only, non-zero insmod value -> -ENODEV).
#   2. marionette_awdl_sta_monitor=1  - patch 0009's concurrency allowance is
#      what lets the vdev be created; without it the driver refuses.
#   3. the station associated on the SAME channel  - patch 0009 requires it
#      both to create the vdev (-ENOTCONN otherwise) and to set its channel
#      (-EBUSY unless freq/width match the STA home channel exactly).
#   4. marionette_awdl_capture_hold=1  - patch 0045; runs
#      marionette_monitor_refresh_filters(), which is what actually opens the
#      monitor RX filters.  Without it the vdev exists and receives nothing.
#
# Usage: awdl-mon-capture.sh <channel> [seconds] [bssid]
set -u

CH=${1:-44}
SEC=${2:-90}
PIN=${3:-70:3a:73:a2:28:14}
FREQ=$((5000 + 5 * CH))
SSID="LIMO-5G ceshi"
PW=limo@202509
OUT=/data/local/tmp/moncap.pcap
P=/sys/module/kiwi_v2/parameters

say() { echo "== $*"; }
ch_of() { iw dev wlan0 info 2>/dev/null | sed -n 's/.*channel [0-9]* (\([0-9]*\) MHz).*/\1/p'; }

say "station must be on channel $CH (${FREQ} MHz) - now $(ch_of)"
if [ "$(ch_of)" != "$FREQ" ]; then
	input keyevent 224 >/dev/null 2>&1
	NID=$(cmd wifi list-networks 2>/dev/null | awk '/LIMO-5G ceshi/ {print $1; exit}')
	[ -n "$NID" ] && cmd wifi forget-network "$NID" >/dev/null 2>&1
	cmd wifi connect-network "$SSID" wpa2 "$PW" -b "$PIN" -r none >/dev/null 2>&1
	i=0
	while [ $i -lt 40 ]; do
		[ "$(ch_of)" = "$FREQ" ] && break
		/system/bin/sleep 1
		i=$((i + 1))
	done
	say "re-pinned: freq=$(ch_of) $(iw dev wlan0 link 2>/dev/null | head -1)"
	[ "$(ch_of)" = "$FREQ" ] || { say "FAIL: station not on $FREQ"; exit 1; }
fi

say "monitor vdev"
iw dev awdlmon del 2>/dev/null
iw phy phy0 interface add awdlmon type monitor 2>&1 | sed 's/^/  /'
iw dev 2>/dev/null | grep -q 'Interface awdlmon' || { say "FAIL: vdev not created"; exit 4; }
ip link set awdlmon up || { iw dev awdlmon del; say "FAIL: vdev up"; exit 4; }

say "pinning the monitor to channel $CH (the STA is on $(ch_of), so 0009 allows it)"
iw dev awdlmon set channel "$CH" 2>&1 | sed 's/^/  /'
iw dev awdlmon set freq "$FREQ" 2>/dev/null
say "monitor info: $(iw dev awdlmon info 2>/dev/null | tr '\n' ' ' | sed 's/  */ /g')"

say "opening the monitor RX filters (capture_hold)"
echo 1 >"$P/marionette_awdl_capture_hold" 2>/dev/null || say "note: capture_hold write failed"
say "hold=$(cat "$P/marionette_awdl_capture_hold" 2>/dev/null)"
dmesg | grep -i MARIONETTE_CAPTURE | tail -2 | sed 's/^/  /'

say "capturing ${SEC}s -> $OUT"
rm -f "$OUT"
/system/bin/timeout -k 3 "$SEC" tcpdump -i awdlmon -c 400000 -w "$OUT" 2>&1 | tail -2
say "pcap size: $(stat -c %s "$OUT" 2>/dev/null) bytes"

say "=== frequency distribution ==="
timeout 200 tcpdump -r "$OUT" 2>/dev/null | grep -oE '[0-9]+ MHz' | sort | uniq -c | sort -rn | head -8 | sed 's/^/  /'

say "=== AWDL BSSID 00:25:00:ff:94:73 (addr3 / any) ==="
N3=$(timeout 200 tcpdump -r "$OUT" 'wlan[4:4] == 0x002500ff' 2>/dev/null | wc -l)
NH=$(timeout 200 tcpdump -r "$OUT" 'wlan host 00:25:00:ff:94:73' 2>/dev/null | wc -l)
say "addr3_awdl_bssid=$N3  any_addr_awdl_bssid=$NH"
timeout 200 tcpdump -r "$OUT" 'wlan host 00:25:00:ff:94:73' 2>/dev/null | head -5 | sed 's/^/  /'

say "=== broadcast action frames ==="
NA=$(timeout 200 tcpdump -r "$OUT" 2>/dev/null | grep -c 'DA:Broadcast.*RA:')
say "broadcast_action_frames=$NA"

say "=== monitor RX counters ==="
for c in marionette_mon_desc_total marionette_mon_post_total marionette_mon_post_awdl \
	 marionette_mon_hdr_awdl marionette_awdl_rx_wmi marionette_mgmt_rx_wmi; do
	[ -f "$P/$c" ] && echo "  $c=$(cat "$P/$c")"
done

say "teardown"
echo 0 >"$P/marionette_awdl_capture_hold" 2>/dev/null
ip link set awdlmon down 2>/dev/null
iw dev awdlmon del 2>/dev/null
say "link after: $(iw dev wlan0 link 2>/dev/null | head -1)"
say "RESULT: DONE $OUT"
