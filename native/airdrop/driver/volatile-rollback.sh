#!/system/bin/sh
# Device-side guardian for a bounded RAM-only WLAN module test.
# Never writes partitions, vendor modules, init files, or boot images.
set -u
STATE="$1"
PARENT="$2"
STOCK_HASH=75a937c19a6f0461086ef9ebd3c69191a4531a5a5013fde9d4603440a99f9bb8
case "$STATE" in /data/local/tmp/marionette-stack-test-[0-9]*) ;; *) exit 2 ;; esac
[ "$(id -u)" = 0 ] && [ -d "$STATE" ] && [ ! -L "$STATE" ] || exit 2
[ "$(sha256sum "$STATE/stock.ko" | cut -d ' ' -f 1)" = "$STOCK_HASH" ] || exit 3
trap '' HUP
trap 'touch "$STATE/stop"' INT TERM
uptime_seconds() { cut -d. -f1 /proc/uptime; }
deadline=$(( $(uptime_seconds) + 90 ))
echo "$$" > "$STATE/guardian.pid"
echo armed > "$STATE/guardian"
while [ ! -f "$STATE/stop" ] && [ "$(uptime_seconds)" -lt "$deadline" ]; do
  kill -0 "$PARENT" 2>/dev/null || break
  /system/bin/sleep 1
done

echo "RESTORE_STARTED uptime=$(uptime_seconds)" >> "$STATE/rollback.log"
/system/bin/timeout -k 2 6 svc wifi disable >> "$STATE/rollback.log" 2>&1 || true
/system/bin/sleep 2
if [ -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ]; then
  if [ "$(cat /sys/module/kiwi_v2/parameters/con_mode)" != 0 ]; then
    ip link set wlan0 down >> "$STATE/rollback.log" 2>&1 || true
    echo 0 > /sys/module/kiwi_v2/parameters/con_mode
  fi
  /system/bin/timeout -k 2 12 rmmod kiwi_v2 >> "$STATE/rollback.log" 2>&1 || true
fi
if [ ! -d /sys/module/kiwi_v2 ]; then
  /system/bin/timeout -k 2 20 insmod "$STATE/stock.ko" >> "$STATE/rollback.log" 2>&1 || true
fi
if [ -d /sys/module/kiwi_v2 ] && [ ! -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ]; then
  case "$(cat "$STATE/wifi-was-on")" in
    1|2) /system/bin/timeout -k 2 6 svc wifi enable >> "$STATE/rollback.log" 2>&1 || true ;;
  esac
  echo "STOCK_RESTORED uptime=$(uptime_seconds)" >> "$STATE/rollback.log"
  echo restored > "$STATE/result.tmp"
else
  echo "RESTORE_FAILED inspect=$STATE/rollback.log" >> "$STATE/rollback.log"
  echo restore_failed > "$STATE/result.tmp"
fi
mv "$STATE/result.tmp" "$STATE/result"
