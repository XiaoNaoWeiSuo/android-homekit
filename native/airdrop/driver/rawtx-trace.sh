#!/system/bin/sh
# Trace the TX path of a frame injected on the monitor interface, to find the
# exact stage where it disappears. Read-only: kprobes with no handlers.
set -eu
[ "$(id -u)" = 0 ] || exit 2
CFG=/data/local/tmp/marionette-cfg80211-awdl.ko
WLAN=/data/local/tmp/marionette-candidate-0034.ko
RAW=/data/local/tmp/marionette-awdl-raw-tx
STATE=/data/local/tmp/marionette-trace
rm -rf "$STATE"; mkdir -p "$STATE"; umask 077
GATEWAY="$(ip -4 route show table all dev wlan0 | awk '$1 == "default" && $2 == "via" { print $3; exit }')"
T=/sys/kernel/tracing
FNS="packet_snd dev_queue_xmit dev_hard_start_xmit hdd_mon_awdl_start_xmit dp_tx_send_exc"

cleanup_trace() {
  echo 0 > "$T/tracing_on" 2>/dev/null || true
  for fn in $FNS; do
    [ -f "$T/events/kprobes/mp_$fn/enable" ] && echo 0 > "$T/events/kprobes/mp_$fn/enable"
    echo "-:kprobes/mp_$fn" > "$T/kprobe_events" 2>/dev/null || true
  done
  echo > "$T/trace" 2>/dev/null || true
  echo 1 > "$T/tracing_on" 2>/dev/null || true
}
trap cleanup_trace EXIT INT TERM

if [ ! -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ]; then
  /system/bin/timeout -k 2 6 svc wifi disable
  /system/bin/sleep 3
  iw dev 2>/dev/null | awk '/Interface/ {print $2}' | while read -r d; do
    case "$d" in awdlmon|p2p*|wlan1) /system/bin/timeout -k 2 8 iw dev "$d" del 2>/dev/null || true ;; esac
  done
  /system/bin/timeout -k 2 12 rmmod kiwi_v2 2>/dev/null || true
  /system/bin/timeout -k 2 8 rmmod mac80211 2>/dev/null || true
  /system/bin/timeout -k 2 8 rmmod cfg80211 2>/dev/null || true
fi
[ -d /sys/module/cfg80211 ] || /system/bin/timeout -k 2 12 insmod "$CFG"
[ -d /sys/module/kiwi_v2 ] || /system/bin/timeout -k 2 20 insmod "$WLAN" \
  marionette_awdl_mgmt_rx=1 marionette_awdl_roc_rx=1 \
  marionette_awdl_sta_monitor=1 marionette_awdl_monitor_raw_rx=1 \
  marionette_awdl_raw_tx=1
setprop ctl.restart wificond
/system/bin/sleep 3
/system/bin/timeout -k 2 6 svc wifi enable
attempts=0
while [ "$attempts" -lt 25 ]; do
  iw dev wlan0 link > "$STATE/link" 2>&1 || true
  grep -q '^Connected to ' "$STATE/link" && break
  /system/bin/sleep 1; attempts=$((attempts+1))
done
grep -q '^Connected to ' "$STATE/link" || { echo NO_LINK; exit 14; }
/system/bin/timeout -k 2 8 iw dev wlan0 interface add awdlmon type monitor
/system/bin/timeout -k 2 10 ip link set awdlmon up
echo "monitor up freq=$(iw dev wlan0 info | awk '$1=="channel"{gsub(/[()]/,"",$3);print $3;exit}')"

echo "== register kprobes =="
cleanup_trace
for fn in $FNS; do
  if echo "p:mp_$fn $fn" > "$T/kprobe_events" 2>"$STATE/kp.err"; then
    echo 1 > "$T/events/kprobes/mp_$fn/enable"
    echo "kprobe ok: $fn (enabled=$(cat "$T/events/kprobes/mp_$fn/enable"))"
  else
    echo "kprobe FAILED: $fn ($(cat "$STATE/kp.err"))"
  fi
done
echo 1 > "$T/tracing_on"
echo "tracing_on=$(cat "$T/tracing_on")"

echo "== sanity: does the dev_queue_xmit probe fire at all? =="
/system/bin/timeout -k 2 5 ping -I wlan0 -c 1 -W 2 "$GATEWAY" >/dev/null 2>&1 || true
/system/bin/sleep 1
echo "after one ping: trace entries=$(head -1 "$T/trace" | grep -o 'entries-in-buffer/entries-written: [0-9]*/[0-9]*' || echo unknown)"
echo > "$T/trace"
echo "== inject 4 well-formed frames =="
"$RAW" awdlmon 4 ff:ff:ff:ff:ff:ff 500
/system/bin/sleep 2
echo "counters: submitted=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_submitted) rejected=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_rejected)"
echo 0 > "$T/tracing_on"

echo "== hits per probe =="
for fn in $FNS; do
  hits=$(grep -c "mp_$fn:" "$T/trace" || true)
  echo "$fn hits=$hits"
done
echo "total trace entries: $(grep -c 'mp_' "$T/trace" || true)"
echo "== trace tail =="
grep -E 'mp_|task' "$T/trace" | tail -25

echo "== restore =="
cleanup_trace
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
fi
iw dev wlan0 link | head -2
/system/bin/timeout -k 2 12 ping -I wlan0 -c 3 -W 2 "$GATEWAY" || true
echo "stock scmversion=$(cat /sys/module/kiwi_v2/scmversion)"
