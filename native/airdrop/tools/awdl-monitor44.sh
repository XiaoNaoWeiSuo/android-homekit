#!/system/bin/sh
# Capture channel 44 (5220 MHz) with a monitor vdev and NO station at all.
#
# Two things the earlier attempts got wrong:
#
#  1. awdl-peer-check.sh ran `svc wifi disable` and then tried to create a
#     monitor vdev.  On fuxi the WLAN driver is unloaded *with* Wi-Fi, so the
#     vdev could never be created - "FAIL: monitor vdev" was a harness bug.
#     Its pcaps were 24 bytes (header only, zero packets): a broken capture,
#     not a negative measurement.
#
#  2. The stock driver refuses monitor vdevs outright
#     (`iw interface add ... type monitor` -> -22, interface absent), while the
#     marionette candidate accepts them (86 619 frames captured 12:20).  So the
#     candidate has to be loaded for any monitor measurement to work.
#
# With Wi-Fi off there is no station, so there is no beacon watchdog to trip
# and no framework roaming to move the radio off 5220 MHz mid-capture.  The
# only frames on the channel are the AP's beacons and whatever AWDL peers are
# actually transmitting.
#
# Usage: awdl-monitor44.sh [seconds] [channel]
set -u

SEC=${1:-90}
CH=${2:-44}
FREQ=$((5000 + 5 * CH))
CAND=/data/local/tmp/marionette-candidate-0046.ko
CFG=/data/local/tmp/marionette-cfg80211-0043.ko
OUT=/data/local/tmp/mon44.pcap
MODDIR=/vendor_dlkm/lib/modules

say() { echo "== $*"; }

say "wifi off, swapping in the candidate with no station"
svc wifi disable >/dev/null 2>&1
/system/bin/sleep 4
for m in kiwi_v2 mac80211 cfg80211; do
	[ -d "/sys/module/$m" ] && rmmod "$m" 2>/dev/null
done
/system/bin/sleep 1
[ -d /sys/module/mac80211 ] || insmod "$MODDIR/mac80211.ko" 2>/dev/null
insmod "$CFG" || { say "FAIL: cfg80211 insmod"; svc wifi enable >/dev/null 2>&1; exit 2; }
# shellcheck disable=SC2086
insmod "$CAND" marionette_awdl_mgmt_rx=1 marionette_awdl_roc_rx=1 \
	marionette_awdl_monitor_raw_rx=1 || {
	say "FAIL: candidate insmod"
	svc wifi enable >/dev/null 2>&1
	exit 2
}
/system/bin/sleep 3
say "interfaces: $(iw dev 2>/dev/null | sed -n 's/^\s*Interface /&/p' | tr -d '\n' | sed 's/Interface //g')"

say "monitor vdev on channel $CH (${FREQ} MHz)"
# `iw interface add` prints "-22" yet still creates the interface on this
# driver.  Judge by `iw dev`, never by the exit status.
iw phy phy0 interface add awdlmon type monitor 2>&1 | sed 's/^/  /'
if ! iw dev 2>/dev/null | grep -q 'Interface awdlmon'; then
	say "FAIL: monitor vdev"
	svc wifi enable >/dev/null 2>&1
	exit 4
fi
ip link set awdlmon up || { iw dev awdlmon del; svc wifi enable >/dev/null 2>&1; exit 4; }
iw dev awdlmon set channel "$CH" 2>/dev/null || iw dev awdlmon set freq "$FREQ" 2>/dev/null ||
	say "note: could not pin channel $CH"
say "monitor info: $(iw dev awdlmon info 2>/dev/null | sed -n 's/.*channel \([0-9]*\).*/\1/p' | head -1)"

say "capturing ${SEC}s -> $OUT"
rm -f "$OUT"
/system/bin/timeout -k 3 "$SEC" tcpdump -i awdlmon -c 300000 -w "$OUT" 2>&1 | tail -3
say "pcap size: $(stat -c %s "$OUT" 2>/dev/null) bytes"

say "teardown"
ip link set awdlmon down 2>/dev/null
iw dev awdlmon del 2>/dev/null
say "DONE $OUT"
