#!/system/bin/sh
# Which receive-path switch costs the station its AP beacons?
#
# Observed: ~13 s after `marionette_awdl_capture_hold=1` the station logs
# "ROAM_TRIGGER ... BEACON MISS" and then disconnects. Beacon loss is fatal for
# a resident service, so this isolates the switch responsible by testing each
# receive-path knob in turn, with a fixed 45 s window each and an early stop on
# link loss.
set -u
MON=awdlmon
TX=wlan0
CH=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold
RAW=/sys/module/kiwi_v2/parameters/marionette_awdl_monitor_raw_rx

up() { iw dev "$TX" link 2>/dev/null | grep -q '^Connected'; }

recover() {
	echo 0 > "$CH" 2>/dev/null
	ip link set "$MON" down 2>/dev/null
	iw dev "$MON" del 2>/dev/null
	svc wifi disable >/dev/null 2>&1
	/system/bin/sleep 3
	svc wifi enable >/dev/null 2>&1
	i=0
	while [ $i -lt 120 ]; do
		up && return 0
		/system/bin/sleep 0.5
		i=$((i + 1))
	done
	return 1
}

mon_up() {
	ip link set "$TX" up 2>/dev/null
	W=$(iw dev "$TX" info | sed -n 's/.*wiphy \([0-9]*\).*/\1/p' | head -1)
	iw dev "$MON" info >/dev/null 2>&1 || iw phy "phy${W:-0}" interface add "$MON" type monitor
	ip link set "$MON" up
}

# watch <seconds> -> prints the first second at which the link is gone
watch() {
	i=0
	while [ $i -lt "$1" ]; do
		/system/bin/sleep 5
		i=$((i + 5))
		up || { echo "  LOST at ${i}s"; return 1; }
	done
	echo "  survived ${1}s"
	return 0
}

run_phase() {
	label=$1
	raw=$2
	hold=$3
	echo "=== ${label} (raw_rx=${raw} capture_hold=${hold})"
	recover || { echo "  SKIP: could not restore link"; return 1; }
	[ -w "$RAW" ] && echo "$raw" > "$RAW" 2>/dev/null
	echo "  raw_rx=$(cat "$RAW" 2>/dev/null)"
	mon_up
	echo "  monitor up, link=$(up && echo UP || echo DOWN)"
	[ "$hold" = 1 ] && echo 1 > "$CH"
	watch 45
}

run_phase "A: monitor only" 1 0
run_phase "B: capture_hold, raw_rx on" 1 1
run_phase "C: capture_hold, raw_rx off" 0 1

echo 0 > "$CH" 2>/dev/null
[ -w "$RAW" ] && echo 1 > "$RAW" 2>/dev/null
recover && echo "restored: $(iw dev "$TX" link | head -1)"
