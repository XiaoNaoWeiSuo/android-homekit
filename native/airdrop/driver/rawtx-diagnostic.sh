#!/system/bin/sh
# Diagnostic: does the monitor netdev actually carry our ndo_start_xmit?
# Sets up the experimental stack exactly like volatile-stack-test.sh, but keeps
# the monitor interface up and prints netdev state before and after injection.
set -eu
[ "$(id -u)" = 0 ] || exit 2
CFG=/data/local/tmp/marionette-cfg80211-awdl.ko
WLAN=/data/local/tmp/marionette-candidate-0034.ko
RAW=/data/local/tmp/marionette-awdl-raw-tx
STATE=/data/local/tmp/marionette-diagnostic
rm -rf "$STATE"; mkdir -p "$STATE"; umask 077
GATEWAY="$(ip -4 route show table all dev wlan0 | awk '$1 == "default" && $2 == "via" { print $3; exit }')"

echo "== load =="
# Tolerate any starting point: reload the experimental stack only when the
# candidate module is not already loaded.
if [ ! -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ]; then
  /system/bin/timeout -k 2 6 svc wifi disable
  /system/bin/sleep 3
  [ -d /sys/module/awdlmon ] || /system/bin/timeout -k 2 8 iw dev awdlmon del 2>/dev/null || true
  iw dev 2>/dev/null | awk '/Interface/ {print $2}' | while read -r d; do
    case "$d" in awdlmon|p2p*|wlan1) /system/bin/timeout -k 2 8 iw dev "$d" del 2>/dev/null || true ;; esac
  done
  /system/bin/timeout -k 2 12 rmmod kiwi_v2 2>/dev/null || true
  /system/bin/timeout -k 2 8 rmmod mac80211 2>/dev/null || true
  /system/bin/timeout -k 2 8 rmmod cfg80211 2>/dev/null || true
fi
[ -d /sys/module/cfg80211 ] || /system/bin/timeout -k 2 12 insmod "$CFG"
[ -d /sys/module/kiwi_v2 ] || /system/bin/timeout -k 2 20 insmod "$WLAN" marionette_awdl_mgmt_rx=1 marionette_awdl_roc_rx=1 marionette_awdl_sta_monitor=1 marionette_awdl_monitor_raw_rx=1 marionette_awdl_raw_tx=1
echo "raw_tx=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx) sta_monitor=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_sta_monitor)"
setprop ctl.restart wificond
/system/bin/sleep 3
/system/bin/timeout -k 2 6 svc wifi enable
attempts=0
while [ "$attempts" -lt 25 ]; do
  iw dev wlan0 link > "$STATE/link" 2>&1 || true
  grep -q '^Connected to ' "$STATE/link" && break
  /system/bin/sleep 1
  attempts=$((attempts+1))
done
cat "$STATE/link"
grep -q '^Connected to ' "$STATE/link" || { echo "NO_LINK"; exit 14; }

echo "== create monitor =="
/system/bin/timeout -k 2 8 iw dev wlan0 interface add awdlmon type monitor
/system/bin/timeout -k 2 10 ip link set awdlmon up
ip -d link show awdlmon
echo "flags=$(cat /sys/class/net/awdlmon/flags)"
echo "operstate=$(cat /sys/class/net/awdlmon/operstate)"
echo "type=$(cat /sys/class/net/awdlmon/type)"
echo "tx_queue_len=$(cat /sys/class/net/awdlmon/tx_queue_len)"
cat /proc/net/dev | grep -E 'Inter-|awdlmon|wlan0'
# The capture store returns -EINVAL when the netdev does not carry the monitor
# net_device_ops, which is exactly what hdd_mon_awdl_start_xmit needs.
echo "== netdev_ops probe =="
if echo "5220:30" > /sys/module/kiwi_v2/parameters/marionette_awdl_capture_ms 2>"$STATE/store.err"; then
  echo "capture_store=accepted (monitor net_device_ops present)"
else
  echo "capture_store=rejected msg=$(cat "$STATE/store.err")"
fi
echo "counter param names present:"
ls /sys/module/kiwi_v2/parameters/ | grep -E 'raw_tx|submitted|rejected|capture_ms|sta_monitor' || true

echo "== inject =="
echo "before: submitted=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_submitted) rejected=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_rejected)"
"$RAW" awdlmon 5 ff:ff:ff:ff:ff:ff 300
/system/bin/sleep 1
echo "after:  submitted=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_submitted) rejected=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_rejected)"
echo "awdlmon tx_packets=$(cat /sys/class/net/awdlmon/statistics/tx_packets) tx_dropped=$(cat /sys/class/net/awdlmon/statistics/tx_dropped) tx_errors=$(cat /sys/class/net/awdlmon/statistics/tx_errors) tx_carrier_errors=$(cat /sys/class/net/awdlmon/statistics/tx_carrier_errors)"
cat /proc/net/dev | grep -E 'Inter-|awdlmon'
echo "== inject via packet store (SOCK_DGRAM / ethernet-header path) =="
"$RAW" awdlmon 2 ff:ff:ff:ff:ff:ff 400
/system/bin/sleep 1
echo "after2: submitted=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_submitted) rejected=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_rejected)"
echo "== recent kernel log (wlan + packet related) =="
dmesg | grep -iE 'awdlmon|packet|dev_hard|Queue|txq|xmit|marionette' | tail -30

echo "== restore =="
/system/bin/timeout -k 2 8 iw dev awdlmon del 2>/dev/null || true
if [ -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ]; then
  /system/bin/timeout -k 2 6 svc wifi disable
  /system/bin/sleep 2
  echo N > /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx 2>/dev/null || true
  /system/bin/timeout -k 2 12 rmmod kiwi_v2 2>/dev/null || true
  /system/bin/timeout -k 2 8 rmmod mac80211 2>/dev/null || true
  /system/bin/timeout -k 2 8 rmmod cfg80211 2>/dev/null || true
  [ -d /sys/module/cfg80211 ] || /system/bin/timeout -k 2 12 insmod /vendor_dlkm/lib/modules/cfg80211.ko
  [ -d /sys/module/mac80211 ] || /system/bin/timeout -k 2 12 insmod /vendor_dlkm/lib/modules/mac80211.ko
  [ -d /sys/module/kiwi_v2 ] || /system/bin/timeout -k 2 20 insmod /vendor_dlkm/lib/modules/qca_cld3_kiwi_v2.ko
  /system/bin/sleep 2
  setprop ctl.restart wificond
  /system/bin/sleep 3
  /system/bin/timeout -k 2 6 svc wifi enable
  /system/bin/sleep 5
}
iw dev wlan0 link
/system/bin/timeout -k 2 12 ping -I wlan0 -c 3 -W 2 "$GATEWAY" || true
echo "stock scmversion=$(cat /sys/module/kiwi_v2/scmversion) raw_tx_present=$( [ -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ] && echo yes || echo no)"
