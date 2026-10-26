#!/system/bin/sh
# Which step of the AirDrop bring-up/teardown costs the station its link?
# Runs each step in isolation and prints the link state after it, so the
# answer is measured rather than inferred.
set -u
MON=awdlmon
TX=wlan0
HOLD=${1:-20}
CH=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold

link() {
	if iw dev "$TX" link 2>/dev/null | grep -q '^Connected'; then
		echo "link=UP ($(iw dev "$TX" link | sed -n 's/.*freq: //p' | head -1) MHz)"
	else
		echo "link=DOWN"
	fi
}

wait_link() {
	i=0
	while [ $i -lt 60 ]; do
		iw dev "$TX" link 2>/dev/null | grep -q '^Connected' && return 0
		/system/bin/sleep 0.5
		i=$((i + 1))
	done
	return 1
}

recover() {
	svc wifi disable >/dev/null 2>&1
	/system/bin/sleep 2
	svc wifi enable >/dev/null 2>&1
	wait_link
}

echo "start: $(link)"

echo "--- step 1: create monitor vdev"
iw dev "$MON" info >/dev/null 2>&1 || {
	WIPHY=$(iw dev "$TX" info | sed -n 's/.*wiphy \([0-9]*\).*/\1/p' | head -1)
	iw phy "phy${WIPHY:-0}" interface add "$MON" type monitor
}
echo "after create: $(link)"

echo "--- step 2: monitor up"
ip link set "$MON" up
/system/bin/sleep 1
echo "after up: $(link)"

echo "--- step 3: capture_hold=1"
echo 1 > "$CH"
/system/bin/sleep 3
echo "after capture_hold: $(link)"

echo "--- step 4: hold ${HOLD}s (what a live session does)"
i=0
while [ $i -lt "$HOLD" ]; do
	/system/bin/sleep 5
	i=$((i + 5))
	iw dev "$TX" link 2>/dev/null | grep -q '^Connected' || {
		echo "after ${i}s: link=DOWN  <-- capture_hold cost the link"
		break
	}
done
echo "after ${HOLD}s: $(link)"

echo "--- step 5: capture_hold=0"
echo 0 > "$CH"
/system/bin/sleep 2
echo "after release: $(link)"

echo "--- step 6: monitor down"
ip link set "$MON" down
/system/bin/sleep 1
echo "after down: $(link)"

echo "--- step 7: delete monitor vdev"
iw dev "$MON" del
/system/bin/sleep 2
echo "after delete: $(link)"

if ! iw dev "$TX" link 2>/dev/null | grep -q '^Connected'; then
	echo "recovering…"
	recover
	echo "after recover: $(link)"
fi
