#!/system/bin/sh
# Measure what is actually on an AWDL social channel, using the bounded
# direct monitor capture slot (patch 0018) on candidate-0045.
#
# Why this and nothing else:
#   * The STA vdev is blind.  Associated on channel 44 for 75 s with
#     WMI_PDEV_SET_RX_FILTER_PROMISCUOUS enabled, the firmware delivered
#     *zero* management frames to the host (mgmt_rx_wmi frozen at 510).
#   * Scans and ROCs only ever deliver beacons and probe responses, never
#     broadcast action frames, so they cannot see AWDL either (20 targeted
#     scans over 2437/5220/5745: awdl_rx_wmi stayed 0 in both arms).
#   * The monitor vdev is the only path that has ever seen AWDL frames
#     (marionette_mon_post_awdl=1020, 50 287-frame pcap, ROADMAP 22.2).
#
# The slot syntax is "<duration_ms>:<target_freq>", duration 1..200, target
# 2437/5220/5745 only (patch 0018).  Inside the slot the driver retunes the
# monitor to the social channel at 20 MHz and runs
# marionette_monitor_refresh_filters(); on return it restores the STA home
# channel and normal RX.  The station therefore never has to leave its own
# channel, which is what makes this measurable without pinning it.
#
# The die-logger is loaded first: two monitor-capture sessions have already
# rebooted the phone, and ROADMAP 21.4 requires the panic to be captured
# before reproducing rather than guessing.
#
# Usage: awdl-slot-sweep.sh <freq> <slots> [slot-ms] [gap-ms]
set -u

FREQ=${1:-5220}
SLOTS=${2:-120}
SLOT_MS=${3:-200}
GAP_MS=${4:-60}
CFG=/data/local/tmp/marionette-cfg80211-0043.ko
CAND=/data/local/tmp/marionette-candidate-0045.ko
P=/sys/module/kiwi_v2/parameters
OUT=/data/local/tmp/slotsweep.pcap

say() { echo "== $*"; }

say "die-logger first (ROADMAP 21.4: capture the panic, do not guess)"
lsmod | grep -q marionette_die_logger ||
	insmod /data/local/tmp/marionette_die_logger.ko 2>/dev/null
say "die_logger=$(lsmod | grep -c marionette_die_logger)"

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
setprop ctl.restart wificond
svc wifi enable >/dev/null 2>&1
i=0
while [ $i -lt 40 ]; do
	if ! iw dev wlan0 link 2>/dev/null | grep -q '^Connected'; then
		# Screen-off scan throttling is the usual reason the auto-join
		# never fires; wake the device and ask for the network directly.
		if [ $((i % 10)) -eq 9 ]; then
			input keyevent 224 >/dev/null 2>&1
			cmd wifi connect-network "LIMO-5G ceshi" wpa2 "limo@202509" -r none >/dev/null 2>&1
		fi
		/system/bin/sleep 1
		i=$((i + 1))
		continue
	fi
	break
done
say "station: $(iw dev wlan0 link 2>/dev/null | head -1) freq=$(iw dev wlan0 info 2>/dev/null | sed -n 's/.*channel [0-9]* (\([0-9]*\) MHz).*/\1/p')"
iw dev wlan0 link 2>/dev/null | grep -q '^Connected' ||
	{ say "FAIL: station not associated (patch 0009 needs it)"; exit 3; }

say "monitor vdev"
iw phy phy0 interface add awdlmon type monitor 2>&1 | sed 's/^/  /'
iw dev 2>/dev/null | grep -q 'Interface awdlmon' || { say "FAIL: vdev"; exit 4; }
ip link set awdlmon up || { iw dev awdlmon del; say "FAIL: vdev up"; exit 4; }

say "baseline counters"
for c in marionette_awdl_rx_wmi marionette_mgmt_rx_wmi marionette_mon_post_total \
	 marionette_mon_post_awdl marionette_mon_hdr_awdl; do
	echo "  $c=$(cat "$P/$c" 2>/dev/null)"
done

say "starting tcpdump, then ${SLOTS} x ${SLOT_MS}ms slots on ${FREQ}"
rm -f "$OUT"
/system/bin/timeout -k 5 300 tcpdump -i awdlmon -c 500000 -w "$OUT" >/dev/null 2>&1 &
TCPD=$!
/system/bin/sleep 1

n=0
while [ "$n" -lt "$SLOTS" ]; do
# Patch 0018 parses "<target_freq>:<duration_ms>" - frequency FIRST.
	echo "${FREQ}:${SLOT_MS}" >"$P/marionette_awdl_capture_ms" 2>/dev/null || {
		say "FAIL: slot write rejected at slot $n"
		break
	}
	n=$((n + 1))
	[ $((n % 20)) -eq 0 ] && say "  slot $n done"
	[ "$GAP_MS" -gt 0 ] && /system/bin/sleep 0.$(printf '%02d' "$GAP_MS") 2>/dev/null
done
say "slots written: $n"

/system/bin/sleep 1
kill "$TCPD" 2>/dev/null
/system/bin/sleep 1
say "pcap size: $(stat -c %s "$OUT" 2>/dev/null) bytes"

say "counters after the sweep"
for c in marionette_awdl_rx_wmi marionette_mgmt_rx_wmi marionette_mon_post_total \
	 marionette_mon_post_awdl marionette_mon_hdr_awdl; do
	echo "  $c=$(cat "$P/$c" 2>/dev/null)"
done
dmesg | grep -i MARIONETTE_CAPTURE_ONCE | tail -2 | sed 's/^/  /'

say "frequency distribution in the capture"
timeout 200 tcpdump -r "$OUT" 2>/dev/null | grep -oE '[0-9]+ MHz' | sort | uniq -c | sort -rn | head -8 | sed 's/^/  /'

say "AWDL BSSID 00:25:00:ff:94:73"
say "  addr3=$(timeout 200 tcpdump -r "$OUT" 'wlan[4:4] == 0x002500ff' 2>/dev/null | wc -l)  any=$(timeout 200 tcpdump -r "$OUT" 'wlan host 00:25:00:ff:94:73' 2>/dev/null | wc -l)"
timeout 200 tcpdump -r "$OUT" 'wlan host 00:25:00:ff:94:73' 2>/dev/null | head -5 | sed 's/^/  /'

say "broadcast action frames: $(timeout 200 tcpdump -r "$OUT" 2>/dev/null | grep -c 'DA:Broadcast.*RA:')"

say "teardown"
ip link set awdlmon down 2>/dev/null
iw dev awdlmon del 2>/dev/null
say "link after: $(iw dev wlan0 link 2>/dev/null | head -1)"
say "RESULT: DONE $OUT"
