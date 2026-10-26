#!/system/bin/sh
# Isolate which backend costs the station its link: start only filin (RX+TX),
# only luftlift (mDNS/HTTPS), or both, then report the link after N seconds.
# Usage: isolate.sh filin|luftlift|both [seconds]
set -u
MON=awdlmon
TX=wlan0
SECS=${2:-40}
STATE=/data/local/tmp/airdrop-session
CH=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold
MT=/sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx

cleanup() {
	pkill -9 -f '/data/local/tmp/filin ' 2>/dev/null
	pkill -9 -f '/data/local/tmp/luftlift ' 2>/dev/null
	for pid in $(ss -lntp 2>/dev/null | sed -n 's/.*:8771 .*pid=\([0-9]*\).*/\1/p'); do
		kill -9 "$pid" 2>/dev/null
	done
	echo 0 > "$CH" 2>/dev/null
	ip link set "$MON" down 2>/dev/null
	iw dev "$MON" del 2>/dev/null
}

cleanup
mkdir -p "$STATE"
/system/bin/sleep 1

i=0
while [ $i -lt 60 ]; do
	iw dev "$TX" link 2>/dev/null | grep -q '^Connected' && break
	/system/bin/sleep 0.5
	i=$((i + 1))
done
iw dev "$TX" link 2>/dev/null | grep -q '^Connected' || { echo "SKIP: not associated"; exit 3; }
echo "t0: $(iw dev "$TX" link | head -1)"

MODE=${1:-both}
if [ "$MODE" = filin ] || [ "$MODE" = both ]; then
	ip link set "$TX" up 2>/dev/null
	WIPHY=$(iw dev "$TX" info | sed -n 's/.*wiphy \([0-9]*\).*/\1/p' | head -1)
	iw dev "$MON" info >/dev/null 2>&1 || iw phy "phy${WIPHY:-0}" interface add "$MON" type monitor
	ip link set "$MON" up
	echo 1 > "$CH"
	[ -w "$MT" ] && echo Y > "$MT"
	FILIN_TX_IFACE="$TX" /data/local/tmp/filin -i "$MON" -N -f \
		-c "$(iw dev "$TX" info | sed -n 's/.*channel \([0-9]*\) .*/\1/p' | head -1)" \
		--park --no-force-master \
		--pcap /data/local/tmp/awdl.pcap --http-addr 127.0.0.1:9932 \
		>"$STATE/filin.log" 2>&1 &
	echo "filin started pid=$!"
fi
if [ "$MODE" = luftlift ] || [ "$MODE" = both ]; then
	/data/local/tmp/luftlift -i awdl0 receive --progress \
		--output /data/local/tmp/airdrop-in --name 'Marionette 2211133C' \
		--http-addr 127.0.0.1:9933 >"$STATE/luftlift.log" 2>&1 &
	echo "luftlift started pid=$!"
fi

i=0
while [ $i -lt "$SECS" ]; do
	/system/bin/sleep 5
	i=$((i + 5))
	if iw dev "$TX" link 2>/dev/null | grep -q '^Connected'; then
		echo "t${i}: UP"
	else
		echo "t${i}: DOWN  <-- link lost after ${i}s"
		break
	fi
done
echo "final: $(iw dev "$TX" link 2>/dev/null | head -1)"
