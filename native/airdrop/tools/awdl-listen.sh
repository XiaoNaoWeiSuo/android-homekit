#!/system/bin/sh
# Listen on one channel with a short-lived monitor vdev and count AWDL action
# frames.  Answers "is there any AWDL peer on this channel at all?" without
# needing the station to move.
#
# The monitor vdev is deleted immediately after, because leaving it up pulls
# the station's beacon watchdog into BEACON MISS and drops the link in 20-35s.
#
# Usage: awdl-listen.sh <channel> [seconds]
set -u

CH=${1:-6}
SEC=${2:-8}
OUT=/data/local/tmp/awdl-listen.pcap

WIPHY=$(iw dev wlan0 info 2>/dev/null | sed -n 's/.*wiphy \([0-9]*\).*/\1/p' | head -1)
STATION=$(iw dev wlan0 link 2>/dev/null | sed -n 's/^Connected to \([^ ]*\).*/\1/p')

echo "station=${STATION:-none} wiphy=${WIPHY:-?} listening_on_channel=$CH for ${SEC}s"

# `iw phy ... interface add` prints "command failed: Invalid argument (-22)"
# on this driver yet still creates the interface (a later nl80211 step in the
# same invocation fails).  Judge by whether the interface exists, not by the
# exit status.
iw phy "phy${WIPHY:-0}" interface add awdlmon type monitor 2>/dev/null
if ! iw dev 2>/dev/null | grep -q 'Interface awdlmon'; then
	echo "FAIL: monitor vdev"
	exit 4
fi
ip link set awdlmon up || { iw dev awdlmon del; echo "FAIL: monitor up"; exit 4; }
iw dev awdlmon set channel "$CH" 2>/dev/null ||
	iw dev awdlmon set freq "$CH" 2>/dev/null ||
	echo "note: could not pin channel $CH (DFS/NO-IR?) — capturing on the default"

rm -f "$OUT"
timeout "$SEC" tcpdump -i awdlmon -c 2000 -w "$OUT" 2>&1 | tail -2

ip link set awdlmon down
iw dev awdlmon del

echo "link_after=$(iw dev wlan0 link 2>/dev/null | head -1)"

# AWDL action frames carry the AWDL BSSID 00:25:00:ff:94:73 in addr3/addr4.
# Count them with tcpdump's own filter rather than a full pcap parse.
timeout 60 tcpdump -r "$OUT" -c 200 \
	'wlan[4:4] == 0x002500ff && wlan host 00:25:00:ff:94:73' 2>/dev/null |
	tail -3
echo "awdl_frames=$(timeout 60 tcpdump -r "$OUT" 2>/dev/null | grep -c '00:25:00:ff:94:73')"
