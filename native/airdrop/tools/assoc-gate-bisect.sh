#!/system/bin/sh
# Bisect which AWDL gate param breaks the channel-44 association.
#
# Established by assoc-regress-probe.sh: candidate-0046 with every gate off
# associates to the 5220 MHz BSSID fine, while the normal load (roc_rx=1,
# sta_monitor=1, monitor_raw_rx=1) fails it with
#   "Connect with 70:3a:73:a2:28:14 ... is FAILURE cm_reason 0 status_code 0"
# So one of the gates registers a channel-44 requirement with policy_mgr (or
# otherwise makes the firmware refuse a STA connect there) once the driver has
# been running.
#
# This script takes the gate settings as arguments, parks the STA on channel
# 52 (the realistic prior state), settles, then attempts the channel-44
# association.
#
# Usage: assoc-gate-bisect.sh <param=value> ...
set -u

CAND=/data/local/tmp/marionette-candidate-0046.ko
CFG=/data/local/tmp/marionette-cfg80211-0043.ko
PIN=70:3a:73:a2:28:14
SSID="LIMO-5G ceshi"
PW=limo@202509
SETTLE=${SETTLE:-60}

say() { echo "$*"; }
freq() { iw dev wlan0 info 2>/dev/null | sed -n 's/.*channel [0-9]* (\([0-9]*\) MHz).*/\1/p'; }

say "=== gate set: $* ==="
input keyevent 224 >/dev/null 2>&1

NID=$(cmd wifi list-networks 2>/dev/null | awk '/LIMO-5G ceshi/ {print $1; exit}')
[ -n "$NID" ] && cmd wifi forget-network "$NID" >/dev/null 2>&1

svc wifi disable >/dev/null 2>&1
/system/bin/sleep 3
rmmod kiwi_v2 2>/dev/null
rmmod cfg80211 2>/dev/null
insmod "$CFG" || { say "FAIL: cfg80211 insmod"; exit 2; }
# shellcheck disable=SC2086
insmod "$CAND" marionette_awdl_mgmt_rx=1 "$@" || { say "FAIL: candidate insmod"; exit 2; }
setprop ctl.restart wificond
svc wifi enable >/dev/null 2>&1

# Park on channel 52 explicitly (the strongest BSSID); the auto-join is not
# reliable when the screen has just been off.
cmd wifi connect-network "$SSID" wpa2 "$PW" -r none >/dev/null 2>&1
i=0
while [ $i -lt 40 ]; do
	[ "$(freq)" = "5260" ] && break
	/system/bin/sleep 1
	i=$((i + 1))
done
say "parked on: freq=$(freq) $(iw dev wlan0 link 2>/dev/null | head -1)"

say "=== settling ${SETTLE}s ==="
/system/bin/sleep "$SETTLE"
say "still on: freq=$(freq)"

say "=== channel-44 attempt ==="
NID=$(cmd wifi list-networks 2>/dev/null | awk '/LIMO-5G ceshi/ {print $1; exit}')
[ -n "$NID" ] && cmd wifi forget-network "$NID" >/dev/null 2>&1
logcat -c 2>/dev/null
cmd wifi connect-network "$SSID" wpa2 "$PW" -b "$PIN" -r none >/dev/null 2>&1
/system/bin/sleep 12
say "result: freq=$(freq) $(iw dev wlan0 link 2>/dev/null | head -1)"
logcat -d 2>/dev/null | grep -i wpa_supplicant | grep -i -E "Trying|Associated with|ASSOC-REJECT" | tail -3
dmesg | grep -E "Connect with $PIN" | tail -1
