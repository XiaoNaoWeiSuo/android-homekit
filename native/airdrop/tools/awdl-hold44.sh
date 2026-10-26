#!/system/bin/sh
# Park the station on channel 44 (5220 MHz) - the only AWDL social channel
# reachable in this environment - and keep it there.
#
# Two environment facts make this necessary:
#   1. "LIMO-5G ceshi" broadcasts on 5220/5260/5765.  Only 5220 (channel 44)
#      is an AWDL social channel (6/44/149); the 5260 BSSID the phone prefers
#      is 33 dB stronger, so the framework's network selector roams to it
#      ~40 s after every connect.
#   2. An explicit `cmd wifi connect-network -b <bssid>` takes the
#      blind-association path, which candidate-0046 rejects locally (2 ms,
#      status 1) while stock succeeds.  Only the auto-join path (which scans
#      first) works, and a wifi toggle is the way to trigger it.
#
# So: toggle Wi-Fi to force a fresh auto-join that honours the config BSSID
# hint, then disable framework scanning so the selector cannot roam away.
#
# Usage: awdl-hold44.sh [hold-seconds]
set -u

TARGET_FREQ=5220
PIN=70:3a:73:a2:28:14
HOLD=${1:-90}

freq() {
	iw dev wlan0 info 2>/dev/null |
		sed -n 's/.*channel [0-9]* (\([0-9]*\) MHz).*/\1/p'
}

cur=$(freq)
if [ "$cur" = "$TARGET_FREQ" ]; then
	echo "already on $TARGET_FREQ"
else
	echo "parking: current freq=${cur:-none}"
	svc wifi disable >/dev/null 2>&1
	/system/bin/sleep 4
	svc wifi enable >/dev/null 2>&1
	i=0
	while [ $i -lt 60 ]; do
		f=$(freq)
		[ "$f" = "$TARGET_FREQ" ] && break
		/system/bin/sleep 1
		i=$((i + 1))
	done
	echo "after toggle: freq=$(freq) $(iw dev wlan0 link 2>/dev/null | head -1)"
fi

cmd wifi enable-scanning disabled >/dev/null 2>&1
echo "framework scanning disabled"

i=0
while [ $i -lt "$HOLD" ]; do
	/system/bin/sleep 5
	i=$((i + 1))
	echo "t=$((i * 5))s freq=$(freq) $(iw dev wlan0 link 2>/dev/null | sed -n 's/^Connected to \([^ ]*\).*/\1/p')"
done

cmd wifi enable-scanning enabled >/dev/null 2>&1
echo "framework scanning restored"
