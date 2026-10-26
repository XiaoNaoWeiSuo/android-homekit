#!/system/bin/sh
# Single-kprobe decisive test: is hdd_mon_awdl_start_xmit ever entered?
# hdd_hard_start_xmit (the normal STA path) is the control that a kiwi_v2
# module symbol really can be probed and hit.
set -eu
[ "$(id -u)" = 0 ] || exit 2
CFG=/data/local/tmp/marionette-cfg80211-awdl.ko
WLAN=/data/local/tmp/marionette-candidate-0034.ko
RAW=/data/local/tmp/marionette-awdl-raw-tx
STATE=/data/local/tmp/marionette-trace3
rm -rf "$STATE"; mkdir -p "$STATE"; umask 077
GATEWAY="$(ip -4 route show table all dev wlan0 | awk '$1 == "default" && $2 == "via" { print $3; exit }')"
T=/sys/kernel/tracing

cleanup() {
  echo 0 > "$T/tracing_on" 2>/dev/null || true
  for n in mp_mon mp_sta; do
    [ -f "$T/events/kprobes/$n/enable" ] && echo 0 > "$T/events/kprobes/$n/enable"
    echo "-:kprobes/$n" > "$T/kprobe_events" 2>/dev/null || true
  done
  echo > "$T/trace" 2>/dev/null || true
  echo 1 > "$T/tracing_on" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

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

echo "== register kprobes (all first, then enable) =="
cleanup
echo "p:mp_mon hdd_mon_awdl_start_xmit" > "$T/kprobe_events"
echo "p:mp_sta hdd_hard_start_xmit" > "$T/kprobe_events"
echo "registered:"
cat "$T/kprobe_events"
echo 1 > "$T/events/kprobes/mp_mon/enable"
echo 1 > "$T/events/kprobes/mp_sta/enable"
echo 1 > "$T/tracing_on"
echo "tracing_on=$(cat "$T/tracing_on")"

echo "== stage 1: 4 well-formed frames on awdlmon =="
"$RAW" awdlmon 4 ff:ff:ff:ff:ff:ff 800 | sed -n '1,2p'
/system/bin/sleep 1
echo "mon_hits=$(grep -c 'mp_mon:' "$T/trace" || true) sta_hits=$(grep -c 'mp_sta:' "$T/trace" || true)"
echo "counters: submitted=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_submitted) rejected=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_rejected)"

echo "== stage 2: control, 2 packets on wlan0 (STA path) =="
/system/bin/timeout -k 2 6 ping -I wlan0 -c 2 -W 2 "$GATEWAY" >/dev/null 2>&1 || true
/system/bin/sleep 1
echo "mon_hits=$(grep -c 'mp_mon:' "$T/trace" || true) sta_hits=$(grep -c 'mp_sta:' "$T/trace" || true)"
echo 0 > "$T/tracing_on"
echo "== trace tail =="
grep -E 'mp_mon:|mp_sta:' "$T/trace" | tail -12
echo "== which devices were seen =="
grep -c 'mp_sta:' "$T/trace" > "$STATE/sta" ; grep -c 'mp_mon:' "$T/trace" > "$STATE/mon"
echo "sta_hits=$(cat "$STATE/sta") mon_hits=$(cat "$STATE/mon")"

echo "== restore =="
cleanup
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
echo "stock scmversion=$(cat /sys/module/kiwi_v2/scmversion)"
