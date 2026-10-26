#!/system/bin/sh
# One short normal-Wi-Fi test; experimental RAW TX stays disabled.
# Usage: sh volatile-test.sh check|run CANDIDATE CANDIDATE_SHA ROLLBACK_SCRIPT
set -eu
ACTION="$1"
CANDIDATE="$2"
EXPECTED="$3"
ROLLBACK="$4"
STOCK=/vendor_dlkm/lib/modules/qca_cld3_kiwi_v2.ko
STOCK_HASH=75a937c19a6f0461086ef9ebd3c69191a4531a5a5013fde9d4603440a99f9bb8
[ "$(id -u)" = 0 ] || exit 2
[ "$(getprop ro.product.device)" = fuxi ] || exit 3
[ "$(uname -r)" = 5.15.74-android13-8-00049-g8945ccdb2203-ab9500309 ] || exit 3
[ -f "$CANDIDATE" ] && [ -f "$ROLLBACK" ] || exit 4
[ "$(sha256sum "$CANDIDATE" | cut -d ' ' -f 1)" = "$EXPECTED" ] || exit 4
case "$EXPECTED" in
  920f555cb45bd84441575f47cf5a743acbcf456c8de3536dfe7017024f464e3c)
    echo CANDIDATE_QUARANTINED_AFTER_MONITOR_TEARDOWN_PANIC; exit 18 ;;
  ef8ce23399e5504b81a6dc51cdfce58075c07d0a63d69fa6d62af5ff4e1c5c68)
    echo CANDIDATE_QUARANTINED_AFTER_NORMAL_RX_FAILURE; exit 18 ;;
  e0c27d4beef8a1e57534979043a2176e8e0d6e0f0202aa71525c003d5227fa22)
    echo CANDIDATE_QUARANTINED_AFTER_ALLOCATION_FAILURE; exit 18 ;;
  3f9155a7df922b0bc7e31c59fc4f1e940b74d8552a0ae746fa7c109996e2714f|fd515fdd8cb40a8dbb2100aad82e878d34a201055cc0d97e54bb41d1d4fcf74d)
    echo CANDIDATE_QUARANTINED_AFTER_REBOOT; exit 18 ;;
  c9a7fb7a286a8fae8bcef0e6f9d2f510b1874fc75cf797c8081902a01f68cb52)
    # A failed candidate cannot be used normally. One diagnostic reproduction
    # requires the independently self-tested exception observer and live reader.
    [ "$ACTION" = diagnostic-repro ] || { echo CANDIDATE_QUARANTINED_AFTER_REBOOT; exit 18; }
    [ "$(cat /sys/module/marionette_die_logger/parameters/delay_ms)" = 500 ] || exit 20
    [ "$(cat /sys/module/marionette_die_logger/parameters/selftest)" = N ] || exit 20
    [ "$(cat /sys/module/marionette_die_logger/parameters/panic_probe_registered)" = Y ] || exit 20
    pidof marionette-kmsg-reader >/dev/null || exit 20
    [ "$(cat /data/local/tmp/marionette-driver-diagnostic-once)" = "$EXPECTED" ] || exit 20
    ;;
esac
[ "$(sha256sum "$STOCK" | cut -d ' ' -f 1)" = "$STOCK_HASH" ] || exit 4
[ -d /sys/module/kiwi_v2 ] && [ ! -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ] || exit 5
[ "$(cat /sys/module/kiwi_v2/parameters/con_mode)" = 0 ] || exit 5
[ "$(cat /sys/module/kiwi_v2/refcnt)" = 0 ] || exit 5
[ "$(cat /sys/class/udc/a600000.dwc3/state)" = configured ] || exit 6
command -v setsid >/dev/null
# Check the original Wi-Fi data path before taking the test lock.
ROUTES_BEFORE="$(ip -4 route show table all dev wlan0)"
GATEWAY="$(echo "$ROUTES_BEFORE" | awk '$1 == "default" && $2 == "via" { print $3; exit }')"
[ -n "$GATEWAY" ] || { echo NO_WIFI_GATEWAY; exit 21; }
PING_BEFORE="$(/system/bin/timeout -k 2 8 ping -I wlan0 -c 2 -W 2 "$GATEWAY" 2>&1)" || {
  echo STOCK_GATEWAY_UNREACHABLE; exit 21;
}
echo PREFLIGHT_OK
[ "$ACTION" = check ] && exit 0
case "$ACTION" in
  run) [ "$EXPECTED" != "$STOCK_HASH" ] || exit 2 ;;
  run-stock-control) [ "$EXPECTED" = "$STOCK_HASH" ] || exit 2 ;;
  diagnostic-repro)
    [ "$EXPECTED" = c9a7fb7a286a8fae8bcef0e6f9d2f510b1874fc75cf797c8081902a01f68cb52 ] || exit 2
    rm /data/local/tmp/marionette-driver-diagnostic-once
    ;;
  *) exit 2 ;;
esac

LOCK=/data/local/tmp/marionette-driver-test.lock
mkdir "$LOCK" || exit 7
STATE="/data/local/tmp/marionette-driver-test-$(cut -d. -f1 /proc/uptime)-$$"
umask 077
mkdir "$STATE"
settings get global wifi_on > "$STATE/wifi-was-on"
cp "$STOCK" "$STATE/stock.ko"
[ "$(sha256sum "$STATE/stock.ko" | cut -d ' ' -f 1)" = "$STOCK_HASH" ] || exit 8
iw dev wlan0 link > "$STATE/link-before"
# Android uses per-interface routing tables. Keep the original reachable
# gateway as the data-path target, independent of DNS or cellular fallback.
echo "$ROUTES_BEFORE" > "$STATE/routes-before"
echo "$GATEWAY" > "$STATE/gateway-before"
echo "$PING_BEFORE" > "$STATE/ping-before"
echo "state=$STATE"
cleanup() {
  task_status=$?
  trap - EXIT
  trap '' HUP INT TERM
  touch "$STATE/stop"
  # The guardian owns recovery even if this shell or its ADB session dies.
  attempts=0
  while [ ! -f "$STATE/result" ] && [ "$attempts" -lt 45 ]; do
    /system/bin/sleep 1
    attempts=$((attempts + 1))
  done
  recovery="$(cat "$STATE/result" 2>/dev/null || echo restore_pending)"
  echo "$recovery"
  if [ "$recovery" = restored ]; then
    rmdir "$LOCK" || task_status=17
  else
    task_status=16
  fi
  exit "$task_status"
}
trap cleanup EXIT
trap 'exit 9' HUP INT TERM
setsid /system/bin/sh "$ROLLBACK" "$STATE" "$$" </dev/null > "$STATE/guardian.log" 2>&1 &
attempts=0
while [ ! -f "$STATE/guardian" ] && [ "$attempts" -lt 5 ]; do
  /system/bin/sleep 1
  attempts=$((attempts + 1))
done
[ -f "$STATE/guardian" ] || exit 10
/system/bin/timeout -k 2 6 svc wifi disable
/system/bin/sleep 3
[ ! -f "$STATE/result" ] || exit 11
/system/bin/timeout -k 2 12 rmmod kiwi_v2
[ ! -d /sys/module/kiwi_v2 ] || exit 12
[ ! -f "$STATE/result" ] || exit 11
/system/bin/timeout -k 2 20 insmod "$CANDIDATE"
if [ "$ACTION" = run-stock-control ]; then
  [ ! -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ] || exit 13
  [ "$(cat /sys/module/kiwi_v2/scmversion)" = g939d76a86982 ] || exit 13
  echo STOCK_MODULE_RELOADED
else
  [ -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ] || exit 13
  [ "$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx)" = N ] || exit 14
  echo EXPERIMENTAL_MODULE_LOADED_RAW_TX_DISABLED
fi
[ "$(cat /sys/module/kiwi_v2/parameters/con_mode)" = 0 ] || exit 14
# Android's legacy Wi-Fi HAL uses this state-control endpoint. Do not enable
# Wi-Fi if a candidate omitted the device-specific WLAN_CTRL_NAME build flag.
attempts=0
while { [ ! -c /dev/wlan ] || [ ! -d /sys/class/wlan/wlan ]; } && [ "$attempts" -lt 5 ]; do
  /system/bin/sleep 1
  attempts=$((attempts + 1))
done
[ -c /dev/wlan ] && [ -d /sys/class/wlan/wlan ] || exit 19
echo ANDROID_WIFI_STATE_CONTROL_PRESENT
case "$(cat "$STATE/wifi-was-on")" in
  1|2) /system/bin/timeout -k 2 6 svc wifi enable ;;
esac
# Allow association to recover, then record behavior before restoring stock.
attempts=0
while [ "$attempts" -lt 25 ] && [ ! -f "$STATE/result" ]; do
  iw dev wlan0 link > "$STATE/link-experimental" 2>&1 || true
  grep -q '^Connected to ' "$STATE/link-experimental" && break
  /system/bin/sleep 1
  attempts=$((attempts + 1))
done
cat "$STATE/link-experimental"
grep -q '^Connected to ' "$STATE/link-experimental" || exit 15
echo NORMAL_WIFI_ASSOCIATION_OBSERVED
# Association can precede DHCP. Allow a short, bounded IP-route recovery.
attempts=0
while [ "$attempts" -lt 8 ] && [ ! -f "$STATE/result" ]; do
  ip -4 route show table all dev wlan0 > "$STATE/routes-experimental"
  awk -v gateway="$GATEWAY" '$1 == "default" && $2 == "via" && $3 == gateway { found=1 } END { exit !found }' \
    "$STATE/routes-experimental" && break
  /system/bin/sleep 1
  attempts=$((attempts + 1))
done
awk -v gateway="$GATEWAY" '$1 == "default" && $2 == "via" && $3 == gateway { found=1 } END { exit !found }' \
  "$STATE/routes-experimental" || exit 22
/system/bin/timeout -k 2 10 ping -I wlan0 -c 3 -W 2 "$GATEWAY" \
  > "$STATE/ping-experimental" 2>&1 || exit 23
cat "$STATE/ping-experimental"
echo NORMAL_WIFI_GATEWAY_ROUNDTRIP_OBSERVED
/system/bin/sleep 5
