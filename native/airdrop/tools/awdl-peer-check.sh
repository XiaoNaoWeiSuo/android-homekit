#!/system/bin/sh
# Is there any AWDL peer at all, and does the firmware filter hide it?
#
# A monitor vdev is -EBUSY on every channel while a STA is associated (QCA
# single-channel concurrency), so this brings the station down first, pins the
# monitor to an AWDL social channel, and counts AWDL action frames there.
#
# That answers the control question the scan A/B cannot: if the monitor hears
# AWDL frames that marionette_awdl_rx_wmi never counted, the firmware is
# dropping them on purpose and WMI_PDEV_SET_RX_FILTER_PROMISCUOUS does not
# help.  If the monitor hears nothing either, the peer is simply absent.
#
# Usage: awdl-peer-check.sh [channel] [seconds]
set -u

CH=${1:-6}
SEC=${2:-20}
P=/sys/module/kiwi_v2/parameters
GATE=$P/marionette_awdl_fw_promisc
OUT=/data/local/tmp/awdl-peer.pcap
AWDL_BSSID='00:25:00:ff:94:73'

WIPHY=$(iw dev wlan0 info 2>/dev/null | sed -n 's/.*wiphy \([0-9]*\).*/\1/p' | head -1)

echo "=== bringing the station down so the monitor can move ==="
svc wifi disable >/dev/null 2>&1
/system/bin/sleep 3

if [ -d /sys/module/kiwi_v2 ]; then
	echo "driver still loaded"
else
	echo "NOTE: the WLAN driver was unloaded with Wi-Fi; results below are"
	echo "      about the air, not about this module."
fi

iw phy "phy${WIPHY:-0}" interface add awdlmon type monitor 2>/dev/null
if ! iw dev 2>/dev/null | grep -q 'Interface awdlmon'; then
	echo "FAIL: monitor vdev"
	svc wifi enable >/dev/null 2>&1
	exit 4
fi
ip link set awdlmon up || { iw dev awdlmon del; svc wifi enable >/dev/null 2>&1; echo "FAIL: monitor up"; exit 4; }
iw dev awdlmon set channel "$CH" 2>/dev/null ||
	iw dev awdlmon set freq "$CH" 2>/dev/null ||
	echo "note: could not pin channel $CH"
echo "monitor: $(iw dev awdlmon info 2>/dev/null | sed -n 's/.*channel \([0-9]*\).*/\1/p' | head -1)"

rm -f "$OUT"
timeout "$SEC" tcpdump -i awdlmon -c 5000 -w "$OUT" 2>&1 | tail -2

echo "=== AWDL action frames heard on channel $CH ==="
N=$(timeout 120 tcpdump -r "$OUT" 2>/dev/null | grep -c "$AWDL_BSSID")
echo "awdl_bssid_frames=$N"
timeout 120 tcpdump -r "$OUT" 2>/dev/null | grep "$AWDL_BSSID" | head -5

echo "=== action frames (type/subtype 0xd0) heard ==="
A=$(timeout 120 tcpdump -r "$OUT" 2>/dev/null | grep -c 'DA:Broadcast.*RA:')
echo "broadcast_action_frames=$A"

ip link set awdlmon down
iw dev awdlmon del

echo "=== restoring Wi-Fi ==="
svc wifi enable >/dev/null 2>&1
/system/bin/sleep 5
iw dev wlan0 link 2>/dev/null | head -1
