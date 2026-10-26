#!/system/bin/sh
# Duty-cycled monitor RX: open the monitor vdev for a short window, capture,
# delete it before the firmware's RDDM recovery fires.
#
# Measured 2026-10-23: leaving the vdev open crashes the firmware
# (cnss: Driver recovery is triggered with reason: RDDM(2)) after 5-25 s, but
# 2 s windows repeated 6 times kept the STA associated with zero recoveries
# and still captured real AWDL action frames on the AP channel.
#
# `iw phy ... interface add` often prints EINVAL after creating the netdev, so
# creation is judged by `iw dev` and retried.  The vdev also needs a moment to
# settle before tcpdump can see traffic on it.
#
# Usage: awdl-duty-rx.sh [cycles] [window-seconds] [gap-seconds]
set -u

CYCLES=${1:-6}
WIN=${2:-2}
GAP=${3:-3}
P=/sys/module/kiwi_v2/parameters
OUT=/data/local/tmp/duty

mkdir -p "$OUT"
rm -f "$OUT"/*.pcap

sta_ok() { iw dev wlan0 link 2>/dev/null | grep -q '^Connected'; }
vdev() { iw dev 2>/dev/null | grep -c 'Interface awdlmon'; }
mon_ch() { iw dev awdlmon info 2>/dev/null | sed -n 's/.*channel \([0-9]*\).*/\1/p' | head -1; }

# Drop anything a previous run left behind.
ip link set awdlmon down 2>/dev/null
iw dev awdlmon del 2>/dev/null

echo "start: sta=$(sta_ok && echo up || echo down) vdev=$(vdev) awdl=$(cat $P/marionette_mon_post_awdl)"
total=0
i=0
while [ "$i" -lt "$CYCLES" ]; do
	i=$((i + 1))
	before=$(cat "$P/marionette_mon_post_awdl")

	# Create with retries; the netdev sometimes appears only on the second try.
	try=0
	while [ "$try" -lt 3 ]; do
		try=$((try + 1))
		iw phy phy0 interface add awdlmon type monitor 2>/dev/null
		[ "$(vdev)" -eq 1 ] && break
		/system/bin/sleep 0.3
	done
	if [ "$(vdev)" -eq 0 ]; then
		echo "cycle $i: CREATE FAILED after $try tries"
		break
	fi

	ip link set awdlmon up 2>/dev/null
	/system/bin/sleep 0.5

	/system/bin/timeout -k 1 "$WIN" tcpdump -i awdlmon -c 200000 \
		-w "$OUT/c$i.pcap" >/dev/null 2>&1

	ch=$(mon_ch)
	ip link set awdlmon down 2>/dev/null
	iw dev awdlmon del 2>/dev/null

	after=$(cat "$P/marionette_mon_post_awdl")
	delta=$((after - before))
	total=$((total + delta))
	sz=$(stat -c %s "$OUT/c$i.pcap" 2>/dev/null || echo 0)
	echo "cycle $i: ch=$ch awdl_delta=$delta pcap=$sz sta=$(sta_ok && echo up || echo down) vdev=$(vdev)"

	if ! sta_ok; then
		echo "cycle $i: STA LOST - stopping"
		break
	fi

	t=$GAP
	while [ "$t" -gt 0 ]; do
		/system/bin/sleep 1
		t=$((t - 1))
	done
done

echo "done: cycles=$i awdl_total=$total sta=$(sta_ok && echo up || echo down) recovery=$(dmesg | grep -ci 'RDDM\|trigger_recovery')"
