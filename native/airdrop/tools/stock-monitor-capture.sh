#!/system/bin/sh
# What is actually on an AWDL social channel?  Measure it with the STOCK
# driver - no marionette patch code in the path at all.
#
# Why this exists: awdl-peer-check.sh assumed the WLAN driver survives
# `svc wifi disable`.  On fuxi it does not - the module is unloaded with Wi-Fi,
# so the script could never create a monitor vdev and its "FAIL: monitor vdev"
# was a harness bug, not an air result.  Its captures were 24-byte pcaps
# (header only, zero packets), which is a broken capture, NOT a negative
# measurement.  The 25 MB / 86 619-frame capture from 12:20 proves monitor RX
# does work on this device.
#
# This script therefore drives the full stack itself:
#     svc wifi disable -> unload kiwi_v2/mac80211/cfg80211
#     -> insmod the STOCK cfg80211 + qca_cld3_kiwi_v2 from /vendor_dlkm
#     -> create a monitor vdev, pin it to <channel>
#     -> tcpdump <seconds>
#     -> restore Wi-Fi
#
# Usage: stock-monitor-capture.sh [channel] [seconds] [outfile]
set -u

CH=${1:-44}
SEC=${2:-30}
OUT=${3:-/data/local/tmp/stockmon.pcap}
AWDL_BSSID='00:25:00:ff:94:73'
# AWDL action frames carry the OUI 00:17:f2 type 08 subtype 10 right after the
# category byte; the BSSID check above is the cheaper, equally specific proxy.
MODDIR=/vendor_dlkm/lib/modules

say() { echo "== $*"; }

say "station down"
svc wifi disable >/dev/null 2>&1
/system/bin/sleep 4

say "unloading wlan stack"
for m in kiwi_v2 mac80211 cfg80211; do
	if [ -d "/sys/module/$m" ]; then
		rmmod "$m" 2>/dev/null && echo "  rmmod $m ok" || echo "  rmmod $m FAILED"
	fi
done
/system/bin/sleep 1

say "insmod stock modules"
[ -d /sys/module/mac80211 ] || insmod "$MODDIR/mac80211.ko" 2>/dev/null
insmod "$MODDIR/cfg80211.ko" 2>/dev/null || echo "  cfg80211 already loaded"
insmod "$MODDIR/qca_cld3_kiwi_v2.ko" || { echo "FAIL: stock kiwi insmod"; svc wifi enable >/dev/null 2>&1; exit 2; }
/system/bin/sleep 3
if [ ! -d /sys/module/kiwi_v2 ]; then
	echo "FAIL: kiwi_v2 not loaded"
	svc wifi enable >/dev/null 2>&1
	exit 2
fi
echo "  kiwi_v2 loaded (stock, no marionette params: $(ls /sys/module/kiwi_v2/parameters 2>/dev/null | grep -c marionette))"

say "creating monitor vdev on channel $CH"
# NOTE: `iw interface add` prints "command failed: Invalid argument (-22)"
# yet still creates the interface.  Judge success by `iw dev`, not the text.
iw phy phy0 interface add awdlmon type monitor 2>&1 | sed 's/^/  /'
if ! iw dev 2>/dev/null | grep -q 'Interface awdlmon'; then
	echo "FAIL: monitor vdev not created"
	svc wifi enable >/dev/null 2>&1
	exit 4
fi
ip link set awdlmon up || { iw dev awdlmon del; svc wifi enable >/dev/null 2>&1; echo "FAIL: monitor up"; exit 4; }
if ! iw dev awdlmon set channel "$CH" 2>/dev/null; then
	iw dev awdlmon set freq "$CH" 2>/dev/null || echo "  note: could not pin channel $CH"
fi
echo "  monitor: $(iw dev awdlmon info 2>/dev/null | sed -n 's/.*channel \([0-9]*\).*/\1/p' | head -1)"

say "capturing ${SEC}s on channel $CH -> $OUT"
rm -f "$OUT"
/system/bin/timeout -k 3 "$SEC" tcpdump -i awdlmon -c 100000 -w "$OUT" 2>&1 | tail -3
echo "  pcap size: $(stat -c %s "$OUT" 2>/dev/null) bytes"

say "quick on-device tally"
N=$(timeout 180 tcpdump -r "$OUT" 2>/dev/null | grep -c "$AWDL_BSSID")
echo "  awdl_bssid_frames=$N"
A=$(timeout 180 tcpdump -r "$OUT" 2>/dev/null | grep -c 'DA:Broadcast')
echo "  broadcast_frames=$A"
timeout 180 tcpdump -r "$OUT" 2>/dev/null | grep "$AWDL_BSSID" | head -3 | sed 's/^/  /'

say "teardown"
ip link set awdlmon down 2>/dev/null
iw dev awdlmon del 2>/dev/null
svc wifi enable >/dev/null 2>&1
/system/bin/sleep 5
echo "link: $(iw dev wlan0 link 2>/dev/null | head -1)"
echo "DONE $OUT"
