#!/system/bin/sh
# Start a full AirDrop session for hands-on testing: RX monitor + filin TX +
# luftlift mDNS/HTTP receiver.  Stops whatever is already running first so the
# monitor vdev can be recreated cleanly.
#
# Usage: session.sh start [seconds]   |   session.sh stop
set -u
MON=awdlmon
TX=wlan0
STATE=/data/local/tmp/airdrop-session
CAPTURE_HOLD=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold
MGMT_TX=/sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx

stop_all() {
	pkill -9 -f '/data/local/tmp/filin ' 2>/dev/null
	pkill -9 -f '/data/local/tmp/luftlift ' 2>/dev/null
	# The app ships the same binaries as libmarionette_awdl.so /
	# libmarionette_airdrop.so, so a path-based pkill misses an instance left
	# running by the UI. The HTTPS port is the unambiguous handle on it.
	for pid in $(ss -lntp 2>/dev/null | sed -n 's/.*:8771 .*pid=\([0-9]*\).*/\1/p'); do
		kill -9 "$pid" 2>/dev/null
	done
	echo 0 > "$CAPTURE_HOLD" 2>/dev/null
	ip link set "$MON" down 2>/dev/null
	iw dev "$MON" del 2>/dev/null
}

case "${1:-start}" in
stop)
	stop_all
	echo stopped
	;;
start)
	mkdir -p "$STATE"
	stop_all
	/system/bin/sleep 1

	# The station blips offline for a second or two when Xiaomi's Wi-Fi service
	# cycles, so wait for the link instead of sampling it once.
	tick=0
	while [ "$tick" -lt 100 ]; do
		iw dev "$TX" link 2>/dev/null | grep -q '^Connected' && break
		/system/bin/sleep 0.2
		tick=$((tick + 1))
	done
	iw dev "$TX" link 2>/dev/null | grep -q '^Connected' || {
		echo "FAIL: wlan0 not associated"
		exit 3
	}
	CHANNEL=$(iw dev "$TX" info | sed -n 's/.*channel \([0-9]*\) .*/\1/p' | head -1)
	ip link set "$TX" up 2>/dev/null
	WIPHY=$(iw dev "$TX" info 2>/dev/null | sed -n 's/.*wiphy \([0-9]*\).*/\1/p' | head -1)
	iw phy "phy${WIPHY:-0}" interface add "$MON" type monitor || {
		echo "FAIL: monitor vdev"
		exit 4
	}
	ip link set "$MON" up || { echo "FAIL: monitor up"; exit 4; }
	echo 1 > "$CAPTURE_HOLD" || { echo "FAIL: capture filter"; exit 5; }
	[ -w "$MGMT_TX" ] && echo Y > "$MGMT_TX"

	# filin first: it creates the awdl0 TAP that the receiver announces on.
	# The pcap records what the monitor hears — the only way to see the peer's
	# side of the conversation without a second radio.
	PCAP=${MARIONETTE_AWDL_PCAP:-/data/local/tmp/awdl.pcap}
	rm -f "$PCAP"
	# `--no-force-master` makes us a guest that inherits the real master's
	# availability windows, but the multicast re-broadcast path is gated on
	# `can_send_in`, and with an adopted master that gate never opened in
	# testing (rebroadcast=0 over a whole session). Overridable so the two
	# election modes can be compared without editing this file.
	FM=${MARIONETTE_FORCE_MASTER:---no-force-master}
	FILIN_TX_IFACE="$TX" RUST_LOG=${MARIONETTE_LOG_LEVEL:-info} /data/local/tmp/filin \
		-i "$MON" -N -f -c "$CHANNEL" --park "$FM" \
		--pcap "$PCAP" \
		--http-addr 127.0.0.1:9932 \
		>"$STATE/filin.log" 2>&1 &
	echo $! > "$STATE/filin.pid"

	tick=0
	while [ "$tick" -lt 60 ]; do
		ip -6 addr show awdl0 2>/dev/null | grep -q 'scope link' && break
		/system/bin/sleep 0.2
		tick=$((tick + 1))
	done

	# Receiver: owns the mDNS record and the HTTPS endpoint (8771) the peer
	# connects to. filin caches whatever lands on awdl0 and re-broadcasts it
	# over the air. `--http-addr` is only the loopback introspection API.
	/data/local/tmp/luftlift -i awdl0 receive --progress \
		--output /data/local/tmp/airdrop-in \
		--name 'Marionette 2211133C' \
		--http-addr 127.0.0.1:9933 \
		>"$STATE/luftlift.log" 2>&1 &
	echo $! > "$STATE/luftlift.pid"

	/system/bin/sleep 2
	echo "luftlift pid=$(cat "$STATE/luftlift.pid") filin pid=$(cat "$STATE/filin.pid")"
	echo "station: $(iw dev "$TX" link | head -1)"
	echo "awdl0: $(ip -6 addr show awdl0 2>/dev/null | sed -n 's/.*inet6 \(fe80[^/]*\).*/\1/p' | head -1)"
	grep -E "transmitting (on the station|via NL80211)" "$STATE/filin.log"
	echo "--- luftlift ---"
	head -8 "$STATE/luftlift.log"
	;;
*)
	echo "usage: $0 start|stop"
	exit 2
	;;
esac
