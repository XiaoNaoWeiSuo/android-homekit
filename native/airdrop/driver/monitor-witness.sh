#!/system/bin/sh
# Receive-only channel-44 witness to distinguish missing traffic from STA RX.
# Usage: sh monitor-witness.sh ROLLBACK
set -eu
[ "$(id -u)" = 0 ] || exit 2
[ "$(getprop ro.product.device)" = fuxi ] || exit 3
[ "$(uname -r)" = 5.15.74-android13-8-00049-g8945ccdb2203-ab9500309 ] || exit 3
[ "$(cat /sys/module/kiwi_v2/scmversion)" = g939d76a86982 ] || exit 4
[ ! -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ] || exit 4
[ "$(sha256sum /vendor_dlkm/lib/modules/qca_cld3_kiwi_v2.ko | cut -d ' ' -f 1)" = 75a937c19a6f0461086ef9ebd3c69191a4531a5a5013fde9d4603440a99f9bb8 ] || exit 4
[ "$(cat /sys/module/kiwi_v2/parameters/con_mode)" = 0 ] || exit 4
[ "$(cat /sys/class/udc/a600000.dwc3/state)" = configured ] || exit 5
[ -f "$1" ] || exit 6
[ -x /data/local/tmp/marionette-awdl-witness ] || exit 6
LOCK=/data/local/tmp/marionette-driver-test.lock
mkdir "$LOCK" || exit 7
STATE="/data/local/tmp/marionette-monitor-witness-$(cut -d. -f1 /proc/uptime)-$$"
umask 077
mkdir "$STATE"
settings get global wifi_on > "$STATE/wifi-was-on"
iw dev wlan0 link > "$STATE/link-before"
echo "state=$STATE"
cleanup() {
  result=$?
  trap - EXIT
  trap '' HUP INT TERM
  touch "$STATE/stop"
  attempts=0
  while [ ! -f "$STATE/result" ] && [ "$attempts" -lt 25 ]; do
    /system/bin/sleep 1
    attempts=$((attempts+1))
  done
  cat "$STATE/result" || result=8
  [ "$(cat "$STATE/result")" = restored ] || result=8
  [ "$result" = 8 ] || rmdir "$LOCK"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 9' HUP INT TERM
setsid /system/bin/sh "$1" "$STATE" "$$" </dev/null > "$STATE/guardian.log" 2>&1 &
attempts=0
while [ ! -f "$STATE/guardian" ] && [ "$attempts" -lt 5 ]; do
  /system/bin/sleep 1
  attempts=$((attempts+1))
done
[ -f "$STATE/guardian" ] || exit 10
/system/bin/timeout -k 2 6 svc wifi disable
/system/bin/sleep 2
ip link set wlan0 down
echo 4 > /sys/module/kiwi_v2/parameters/con_mode
/system/bin/sleep 2
ip link set wlan0 up
for channel in 44 149 6; do
  iw dev wlan0 set channel "$channel"
  echo "PASSIVE_WITNESS_CHANNEL=$channel"
  /system/bin/timeout -k 2 8 /data/local/tmp/marionette-awdl-witness wlan0 5 \
    > "$STATE/capture-$channel.txt" 2>&1
  cat "$STATE/capture-$channel.txt"
done
