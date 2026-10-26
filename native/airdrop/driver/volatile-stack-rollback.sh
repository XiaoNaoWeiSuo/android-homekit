#!/system/bin/sh
# Guardian for the pinned fuxi WLAN/cfg80211 experiment. RAM-only.
set -u
STATE="$1"
PARENT="$2"
case "$STATE" in /data/local/tmp/marionette-stack-test-[0-9]*) ;; *) exit 2 ;; esac
[ "$(id -u)" = 0 ] && [ -d "$STATE" ] && [ ! -L "$STATE" ] || exit 2
verify() { [ "$(sha256sum "$STATE/$1.ko" | cut -d ' ' -f 1)" = "$2" ]; }
verify kiwi_v2 75a937c19a6f0461086ef9ebd3c69191a4531a5a5013fde9d4603440a99f9bb8 || exit 3
verify cfg80211 dda06de18d4fab8a068668d6d70024dee53eb7f8b378ab4dcb09843333bb51c2 || exit 3
verify mac80211 cda1f3dd8c4f9236b8d533c6ffbb541889eaca84c2fd859fc50d9971a29a9bd6 || exit 3
trap '' HUP
trap 'touch "$STATE/stop"' INT TERM
uptime_seconds() { cut -d. -f1 /proc/uptime; }
# The monitor path (create -> capture sweep -> teardown -> recreate -> teardown)
# needs more than the load/unload harness: association, the bounded capture slot
# and the witness run all sit inside this window. Still bounded, still forced.
deadline=$(( $(uptime_seconds) + 180 ))
echo armed > "$STATE/guardian"
while [ ! -f "$STATE/stop" ] && [ "$(uptime_seconds)" -lt "$deadline" ]; do
  kill -0 "$PARENT" 2>/dev/null || break
  /system/bin/sleep 1
done
exec >> "$STATE/rollback.log" 2>&1
echo "RESTORE_STARTED uptime=$(uptime_seconds)"
if [ -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ] &&
   [ -d /sys/class/net/awdlmon ]; then
  /system/bin/timeout -k 2 8 iw dev awdlmon del || true
fi
/system/bin/timeout -k 2 6 svc wifi disable || true
/system/bin/sleep 2
if [ -f /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx ]; then
  echo N > /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx || true
  [ ! -d /sys/module/kiwi_v2 ] || /system/bin/timeout -k 2 12 rmmod kiwi_v2 || true
  [ ! -d /sys/module/mac80211 ] || /system/bin/timeout -k 2 8 rmmod mac80211 || true
  /system/bin/timeout -k 2 8 rmmod cfg80211 || true
fi
[ -d /sys/module/cfg80211 ] || /system/bin/timeout -k 2 12 insmod "$STATE/cfg80211.ko" || true
[ -d /sys/module/mac80211 ] || /system/bin/timeout -k 2 12 insmod "$STATE/mac80211.ko" || true
[ -d /sys/module/kiwi_v2 ] || /system/bin/timeout -k 2 20 insmod "$STATE/kiwi_v2.ko" || true
if [ -d /sys/module/cfg80211 ] && [ -d /sys/module/mac80211 ] &&
   [ -d /sys/module/kiwi_v2 ] &&
   [ ! -f /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx ] &&
   [ ! -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ] &&
   [ "$(cat /sys/module/kiwi_v2/scmversion)" = g939d76a86982 ]; then
  # The restored cfg80211 has another netlink identity; discard stale sockets.
  setprop ctl.restart wificond || true
  /system/bin/sleep 2
  case "$(cat "$STATE/wifi-was-on")" in
    1|2) /system/bin/timeout -k 2 6 svc wifi enable || true ;;
  esac
  echo "STOCK_STACK_RESTORED uptime=$(uptime_seconds)"
  echo restored > "$STATE/result.tmp"
else
  echo RESTORE_FAILED
  echo restore_failed > "$STATE/result.tmp"
fi
mv "$STATE/result.tmp" "$STATE/result"
