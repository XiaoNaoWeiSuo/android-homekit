#!/system/bin/sh
# Independent guardian for a receive-only stock monitor witness.
set -u
STATE="$1"
PARENT="$2"
case "$STATE" in /data/local/tmp/marionette-monitor-witness-[0-9]*) ;; *) exit 2 ;; esac
[ "$(id -u)" = 0 ] && [ -d "$STATE" ] && [ ! -L "$STATE" ] || exit 2
trap '' HUP
trap 'touch "$STATE/stop"' INT TERM
start="$(cut -d. -f1 /proc/uptime)"
echo armed > "$STATE/guardian"
while [ ! -f "$STATE/stop" ] && [ "$(cut -d. -f1 /proc/uptime)" -lt "$((start+45))" ]; do
  kill -0 "$PARENT" 2>/dev/null || break
  /system/bin/sleep 1
done
exec >> "$STATE/rollback.log" 2>&1
echo RESTORE_STARTED
if [ "$(cat /sys/module/kiwi_v2/scmversion)" = g939d76a86982 ] &&
   [ ! -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ]; then
  /system/bin/timeout -k 2 6 ip link set wlan0 down || true
  echo 0 > /sys/module/kiwi_v2/parameters/con_mode
  case "$(cat "$STATE/wifi-was-on")" in
    1|2) /system/bin/timeout -k 2 6 svc wifi enable || true ;;
  esac
fi
if [ "$(cat /sys/module/kiwi_v2/parameters/con_mode)" = 0 ]; then
  echo restored > "$STATE/result"
  echo NORMAL_RADIO_RESTORED
else
  echo restore_failed > "$STATE/result"
fi
