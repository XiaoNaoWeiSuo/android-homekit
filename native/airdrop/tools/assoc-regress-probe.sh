#!/system/bin/sh
# Isolate the candidate-0046 association regression.
#
# Symptom: with the patch stack loaded, associating the STA to the 5220 MHz
# BSSID (channel 44) fails locally - the driver logs
#   "Connect with 70:3a:73:a2:28:14 ... is FAILURE cm_reason 0 status_code 0"
# ~2-20 ms after "Trying to associate", i.e. before any over-the-air exchange.
# Stock modules perform the identical association in ~260 ms.
#
# The failure only appears once the driver has been running for a while: a
# fresh module load associates to channel 44 fine (observed at 17:07:40), and
# every attempt after that fails.  That points at something the driver
# *registers* at runtime rather than at the connect code itself.
#
# Hypothesis: one of the AWDL gate params - marionette_awdl_roc_rx (0008),
# marionette_awdl_sta_monitor (0009) or marionette_awdl_monitor_raw_rx (0021) -
# registers a channel/ROC requirement with policy_mgr, which then refuses a
# STA connect on that channel.  This script loads the candidate with every
# gate off except the RX counter, parks the STA on channel 52, waits, and
# then attempts the channel-44 association.
#
# Usage: assoc-regress-probe.sh
set -u

CAND=/data/local/tmp/marionette-candidate-0046.ko
CFG=/data/local/tmp/marionette-cfg80211-0043.ko
PIN=70:3a:73:a2:28:14
SSID="LIMO-5G ceshi"
PW=limo@202509

say() { echo "$*"; }

freq() { iw dev wlan0 info 2>/dev/null | sed -n 's/.*channel [0-9]* (\([0-9]*\) MHz).*/\1/p'; }

input keyevent 224 >/dev/null 2>&1

# Drop the saved config so the auto-join picks the strongest BSSID (channel 52)
# instead of the channel-44 hint, and so the later pinned connect creates a
# fresh config that actually carries the -b hint.
NID=$(cmd wifi list-networks 2>/dev/null | awk '/LIMO-5G ceshi/ {print $1; exit}')
[ -n "$NID" ] && cmd wifi forget-network "$NID" >/dev/null 2>&1
say "forgot net ${NID:-none}"

say "=== swapping in candidate-0046 with all AWDL gates off ==="
svc wifi disable >/dev/null 2>&1
/system/bin/sleep 3
rmmod kiwi_v2 2>/dev/null
rmmod cfg80211 2>/dev/null
insmod "$CFG" || { say "FAIL: cfg80211 insmod"; exit 2; }
insmod "$CAND" marionette_awdl_mgmt_rx=1 || { say "FAIL: candidate insmod"; exit 2; }
setprop ctl.restart wificond
svc wifi enable >/dev/null 2>&1

i=0
while [ $i -lt 60 ]; do
	[ -n "$(freq)" ] && break
	/system/bin/sleep 1
	i=$((i + 1))
done
say "auto-join: freq=$(freq) $(iw dev wlan0 link 2>/dev/null | head -1)"
say "interfaces: $(iw dev 2>/dev/null | grep -c Interface) -> $(iw dev 2>/dev/null | awk '/Interface/{printf "%s ", $2}')"
say "params: roc_rx=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_roc_rx 2>/dev/null) sta_monitor=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_sta_monitor 2>/dev/null) raw_rx=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_monitor_raw_rx 2>/dev/null) fw_promisc=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_fw_promisc 2>/dev/null)"

say "=== settling 60s on channel 52 ==="
/system/bin/sleep 60
say "after settle: freq=$(freq) interfaces: $(iw dev 2>/dev/null | awk '/Interface/{printf "%s ", $2}')"

say "=== attempting channel-44 association ==="
NID=$(cmd wifi list-networks 2>/dev/null | awk '/LIMO-5G ceshi/ {print $1; exit}')
[ -n "$NID" ] && cmd wifi forget-network "$NID" >/dev/null 2>&1
logcat -c 2>/dev/null
cmd wifi connect-network "$SSID" wpa2 "$PW" -b "$PIN" -r none
/system/bin/sleep 12
say "result: freq=$(freq) $(iw dev wlan0 link 2>/dev/null | head -1)"
logcat -d 2>/dev/null | grep -i wpa_supplicant | grep -i -E "Trying|ASSOC|Associated|CTRL-EVENT|reject" | tail -6
dmesg | grep -E "Connect with $PIN" | tail -3
