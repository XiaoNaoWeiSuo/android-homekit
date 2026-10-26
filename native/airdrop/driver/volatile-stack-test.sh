#!/system/bin/sh
# One bounded ordinary-Wi-Fi + gated AWDL management-path test on pinned fuxi.
# Usage: check|run CFG CFG_SHA WLAN WLAN_SHA ROLLBACK
set -eu
ACTION="$1"
CFG="$2"
CFG_HASH="$3"
WLAN="$4"
WLAN_HASH="$5"
ROLLBACK="$6"
PROBE=/data/local/tmp/marionette-coexist-probe
[ "$(id -u)" = 0 ] || exit 2
[ "$(getprop ro.product.device)" = fuxi ] || exit 3
[ "$(uname -r)" = 5.15.74-android13-8-00049-g8945ccdb2203-ab9500309 ] || exit 3
# Only the reviewed, import/export-audited pair may run this experiment.
[ "$CFG_HASH" = 8b7740aa50814a251ea5a151c02ce66103ada6233e9b6d6cdcf6b6fc06fd20a6 ] || exit 4
[ "$WLAN_HASH" != 920f555cb45bd84441575f47cf5a743acbcf456c8de3536dfe7017024f464e3c ] || { echo CANDIDATE_QUARANTINED_AFTER_MONITOR_TEARDOWN_PANIC; exit 18; }
case "$WLAN_HASH" in
  920f555cb45bd84441575f47cf5a743acbcf456c8de3536dfe7017024f464e3c|398179abeddf085c7f56de450d1a91c154d0f64c48d91bff1bff3e78591c10b8|84b495eca0cac529d6a73a47379d8f5639638e2f2c02524a69cc937e34bfd2a6|4c6793de90cdd5741d6473b8f00255a5d70cd315b91259ad982ca1119e965f95)
    echo CANDIDATE_QUARANTINED_AFTER_MONITOR_TEARDOWN_FAILURE; exit 18 ;;
esac
# 0027 is the teardown-safe base. 0033 adds the HIF-closed guard to the
# shutdown path that faulted in 0031 (hdd_hif_close releases the HAL SRNG
# table, then hdd_check_for_leaks force-drops the leaked monitor vdev).
# 0034 is 0033 plus the monitor-vdev TX lifecycle: it attaches the DP TX
# resources for a monitor vdev and lets the raw exception path submit while a
# normal STA association is up.
# 0035 is 0034 plus the monitor-netdev carrier: without carrier the kernel keeps
# txq->qdisc == &noop_qdisc, so __dev_xmit_skb() drops every injected frame in
# noop_enqueue() and ndo_start_xmit is never reached at all.
# 0036 is 0035 plus the monitor-netdev TX queues: hdd_open_adapter() already
# stopped them at creation with WLAN_STOP_ALL_NETIF_QUEUE_N_CARRIER /
# WLAN_CONTROL_PATH and __hdd_mon_open() never reversed it, so every TX queue
# stayed in __QUEUE_STATE_DRV_XOFF and sch_direct_xmit() requeued forever.
# 0037 is 0036 plus the monitor vdev's TX descriptor pool: under
# QCA_LL_TX_FLOW_CONTROL_V2 the pool for a vdev id is created by the firmware's
# HTT flow-pool-map event, which a monitor vdev never gets, so dp_tx_desc_alloc()
# refused FLOW_POOL_INACTIVE and the frame died in dp_tx_prepare_desc_single().
# 0038 is 0037 plus the exception-frame contract: the firmware went into RDDM as
# soon as one frame reached it as a vdev-based TCL MSDU, so is_tx_sniffer now
# makes dp_tx_add_tx_sniffer_meta_data() build a SEND_AS_STANDALONE HTT MSDU
# extension header and set exception_fw / VALID_HTT instead.
# 0039 is 0038 plus the transmit parameters for that contract: the firmware
# answered 0037 and 0038 with the identical SFR assert
# (cmnos_thread_patch.c:1170 ... line#2834), so it is not objecting to the host
# path, it is objecting to the frame.  A TQM-bypass MSDU still has to become a
# PPDU and a monitor vdev has no rate control to fall back on, so the ext2
# header now carries power/mcs/nss/preamble/retries/bw the way the mesh
# exception path fills them, plus host_tx_desc_pool, which the TQM-bypass
# contract requires, and deliberately NOT learning_frame, which would make the
# firmware drop the frame instead of transmitting it.
# All are import/export audited.
# 0040 adds a selectable carrier vdev (marionette_awdl_raw_tx_vdev).  It is the
# first probe of the P2P/GO-carrier direction: the firmware refused the frame on
# the monitor vdev three times in a row with an assert whose PC did not move, so
# the question is now whether the refusal is about the frame or about the vdev.
# vdev 0 is the station, which transmits data frames all day.
# 0041 adds drop-site tracing on top of 0040: one marionette_awdl_drop_site()
# report per gate in hdd_mon_awdl_start_xmit() plus dp_err()/dp_info() prints in
# dp_tx_send_exception() and dp_tx_send_msdu_single().  No behaviour change; the
# 0040 single-frame run produced no DP message at all in the whole window, so
# the rejection site has to be named before another 24-frame run is attempted.
# 0042 is 0041 with the 13-byte radiotap header left inline instead of pulled.
# QCACLD-2's monitor injection never stripped it: the firmware there parsed
# it_present itself to recover rate/retries, and the host only had to hand the
# bytes over.  Everything tried so far handed the firmware a bare 802.11 frame
# and it asserted; 0042 hands it the radiotap-prefixed body instead, so the
# assert PC either moves or the frame is finally accepted.
case "$WLAN_HASH" in
  de9cebdb64ad58b821f89286828e0443cb77055838d3b624faa8154df8df9b3d|\
  8ec1e5eb4a76e99dc0bdcb26ff179580c3a9f4e7461da106617fbc87dd423352|\
  726d5b7a38fa8cda1709c522f9e33685be65508fa545e5ec27ff61a91a2ddea8|\
  2c9fbc80bedb2cebf64cbfeccb15879c57240d1844811b908962ab6f9a2d4c65|\
  464378e1f3d5251864e05258a395521f8d882be762c9835907981dd18c80a199|\
  d49f4d42c26de2e0a66da2bc2c5040d8a9518638b21ebaae9b3866c7034d3157|\
  6796ec6af813b5b84178948f44ec47f5b068700140b751d9548d3306bd361637|\
  498e5010ea00c105cfef6c5bc841ebbddbaabdd387b41e73896d9465b60c008b|\
  249cfc84fde6ec8d21de3e3cc2effb8cddc629857c831717bd99cff0f910abc5|\
  900b6b3f58f899b7ba894b91bb42a1d37f0e8013479c44a3b4809450d78143dd|\
  649e5a6e9f8fe15e637c960a504d212ca34976e7c3220b063aa34ef910ba549b) ;;
  *) exit 4 ;;
esac
[ "$(sha256sum "$CFG" | cut -d ' ' -f 1)" = "$CFG_HASH" ] || exit 4
[ "$(sha256sum "$WLAN" | cut -d ' ' -f 1)" = "$WLAN_HASH" ] || exit 4
[ -f "$ROLLBACK" ] && [ -x "$PROBE" ] || exit 4
verify_stock() { [ "$(sha256sum "/vendor_dlkm/lib/modules/$1" | cut -d ' ' -f 1)" = "$2" ]; }
verify_stock qca_cld3_kiwi_v2.ko 75a937c19a6f0461086ef9ebd3c69191a4531a5a5013fde9d4603440a99f9bb8 || exit 4
verify_stock cfg80211.ko dda06de18d4fab8a068668d6d70024dee53eb7f8b378ab4dcb09843333bb51c2 || exit 4
verify_stock mac80211.ko cda1f3dd8c4f9236b8d533c6ffbb541889eaca84c2fd859fc50d9971a29a9bd6 || exit 4
[ ! -f /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx ] || exit 5
[ ! -f /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx ] || exit 5
[ "$(cat /sys/module/kiwi_v2/scmversion)" = g939d76a86982 ] || exit 5
[ "$(cat /sys/module/kiwi_v2/parameters/con_mode)" = 0 ] || exit 5
[ "$(cat /sys/module/kiwi_v2/refcnt)" = 0 ] || exit 5
[ "$(cat /sys/module/mac80211/refcnt)" = 0 ] || exit 5
[ "$(cat /sys/module/cfg80211/refcnt)" = 2 ] || exit 5
[ "$(cat /sys/class/udc/a600000.dwc3/state)" = configured ] || exit 6
command -v setsid >/dev/null
GATEWAY="$(ip -4 route show table all dev wlan0 | awk '$1 == "default" && $2 == "via" { print $3; exit }')"
[ -n "$GATEWAY" ] || exit 7
/system/bin/timeout -k 2 8 ping -I wlan0 -c 2 -W 2 "$GATEWAY" >/dev/null || exit 7
echo PREFLIGHT_OK
[ "$ACTION" = check ] && exit 0
case "$ACTION" in run|run-monitor|run-rawtx|run-rawtx-sta-vdev) ;; *) exit 2 ;; esac
[ "$(cat /sys/module/marionette_die_logger/parameters/panic_probe_registered)" = Y ] || exit 8
pidof marionette-kmsg-reader >/dev/null || exit 8
LOCK=/data/local/tmp/marionette-driver-test.lock
mkdir "$LOCK" || exit 9
STATE="/data/local/tmp/marionette-stack-test-$(cut -d. -f1 /proc/uptime)-$$"
umask 077
mkdir "$STATE"
settings get global wifi_on > "$STATE/wifi-was-on"
cp /vendor_dlkm/lib/modules/qca_cld3_kiwi_v2.ko "$STATE/kiwi_v2.ko"
cp /vendor_dlkm/lib/modules/qca_cld3_kiwi_v2.ko "$STATE/stock.ko"
cp /vendor_dlkm/lib/modules/cfg80211.ko "$STATE/cfg80211.ko"
cp /vendor_dlkm/lib/modules/mac80211.ko "$STATE/mac80211.ko"
iw dev wlan0 link > "$STATE/link-before"
echo "$GATEWAY" > "$STATE/gateway-before"
pidof wificond > "$STATE/wificond-before"
echo "state=$STATE"
cleanup() {
  task_status=$?
  trap - EXIT
  trap '' HUP INT TERM
  [ -z "${PING_PID:-}" ] || kill "$PING_PID" 2>/dev/null || true
  [ -z "${WITNESS_PID:-}" ] || kill "$WITNESS_PID" 2>/dev/null || true
  touch "$STATE/stop"
  attempts=0
  while [ ! -f "$STATE/result" ] && [ "$attempts" -lt 120 ]; do
    /system/bin/sleep 1
    attempts=$((attempts + 1))
  done
  recovery="$(cat "$STATE/result" 2>/dev/null || echo restore_pending)"
  echo "$recovery"
  if [ "$recovery" = restored ]; then rmdir "$LOCK" || task_status=19; else task_status=18; fi
  exit "$task_status"
}
trap cleanup EXIT
trap 'exit 10' HUP INT TERM
setsid /system/bin/sh "$ROLLBACK" "$STATE" "$$" </dev/null > "$STATE/guardian.log" 2>&1 &
attempts=0
while [ ! -f "$STATE/guardian" ] && [ "$attempts" -lt 5 ]; do
  /system/bin/sleep 1
  attempts=$((attempts + 1))
done
[ -f "$STATE/guardian" ] || exit 11
/system/bin/timeout -k 2 6 svc wifi disable
/system/bin/sleep 3
/system/bin/timeout -k 2 12 rmmod kiwi_v2
/system/bin/timeout -k 2 8 rmmod mac80211
/system/bin/timeout -k 2 8 rmmod cfg80211
[ ! -f "$STATE/result" ] || exit 12
/system/bin/timeout -k 2 12 insmod "$CFG"
[ "$(cat /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx)" = N ] || exit 13
# 0003 keeps marionette_awdl_raw_tx off until the module ABI and firmware
# behaviour are proven, so it is only armed for run-rawtx.
# 0040 adds marionette_awdl_raw_tx_vdev so the same frame can be carried by
# another vdev.  run-rawtx-sta-vdev pins it to vdev 0, the station: 0037 to
# 0039 all asserted on the monitor vdev, and this is the cheapest way to tell
# "the firmware will not transmit this frame" from "the firmware will not
# transmit on a monitor vdev".
case "$ACTION" in
  run-rawtx) TX_PARAMS="marionette_awdl_raw_tx=1" ;;
  run-rawtx-sta-vdev) TX_PARAMS="marionette_awdl_raw_tx=1 marionette_awdl_raw_tx_vdev=0" ;;
  *) TX_PARAMS="" ;;
esac
/system/bin/timeout -k 2 20 insmod "$WLAN" marionette_awdl_mgmt_rx=1 marionette_awdl_roc_rx=1 marionette_awdl_sta_monitor=1 marionette_awdl_monitor_raw_rx=1 $TX_PARAMS
case "$ACTION" in
  run-rawtx|run-rawtx-sta-vdev)
    [ "$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx)" = Y ] || exit 13 ;;
  *)
    [ "$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx)" = N ] || exit 13 ;;
esac
[ "$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_mgmt_rx)" = Y ] || exit 13
[ "$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_roc_rx)" = Y ] || exit 13
[ "$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_fw_promisc)" = N ] || exit 13
[ "$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_monitor_raw_rx)" = Y ] || exit 13
[ "$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_raw_resources)" = N ] || exit 13
[ -c /dev/wlan ] || exit 13
echo EXPERIMENTAL_STACK_LOADED_BOTH_TX_GATES_DISABLED
# Reloading cfg80211 changes its dynamically allocated generic-netlink
# family identity. Recreate wificond's cached sockets before requesting Wi-Fi.
OLD_WIFICOND="$(pidof wificond)"
setprop ctl.restart wificond
attempts=0
while [ "$attempts" -lt 5 ]; do
  NEW_WIFICOND="$(pidof wificond || true)"
  [ -n "$NEW_WIFICOND" ] && [ "$NEW_WIFICOND" != "$OLD_WIFICOND" ] && break
  /system/bin/sleep 1
  attempts=$((attempts + 1))
done
[ -n "$NEW_WIFICOND" ] && [ "$NEW_WIFICOND" != "$OLD_WIFICOND" ] || exit 20
echo "$NEW_WIFICOND" > "$STATE/wificond-experimental"
echo WIFICOND_RESTARTED_AFTER_CFG80211_RELOAD
/system/bin/timeout -k 2 6 svc wifi enable
attempts=0
while [ "$attempts" -lt 25 ] && [ ! -f "$STATE/result" ]; do
  iw dev wlan0 link > "$STATE/link-experimental" 2>&1 || true
  grep -q '^Connected to ' "$STATE/link-experimental" && break
  /system/bin/sleep 1
  attempts=$((attempts + 1))
done
cat "$STATE/link-experimental"
grep -q '^Connected to ' "$STATE/link-experimental" || exit 14
# 0040: "Connected to" only means L2.  A driver reload plus the wificond restart
# below re-triggers DHCP, and the address routinely arrives a few seconds after
# association -- a ping at that point fails with "from 0.0.0.0" and aborts the
# run before anything is injected.  Wait for the address too, then ping.
addr_attempts=0
while [ "$addr_attempts" -lt 20 ] && [ ! -f "$STATE/result" ]; do
  ip -4 -br addr show dev wlan0 2>/dev/null | grep -q ' 10\.\| 172\.\| 192\.168\.' && break
  /system/bin/sleep 1
  addr_attempts=$((addr_attempts + 1))
done
ip -4 -br addr show dev wlan0 > "$STATE/addr-experimental" 2>&1 || true
cat "$STATE/addr-experimental"
/system/bin/timeout -k 2 12 ping -I wlan0 -c 3 -W 2 "$GATEWAY" > "$STATE/ping-before-mgmt" 2>&1 || exit 14
echo NORMAL_WIFI_GATEWAY_ROUNDTRIP_OBSERVED
rx_snapshot() {
  for stage in wmi lim hdd; do
    for kind in mgmt awdl; do
      parameter="marionette_${kind}_rx_${stage}"
      if [ -f "/sys/module/kiwi_v2/parameters/$parameter" ]; then
        echo "$parameter=$(cat "/sys/module/kiwi_v2/parameters/$parameter")"
      fi
    done
  done
  for counter in desc_total desc_valid_fc desc_mgmt desc_action desc_data desc_control post_total post_action post_awdl missing_hal_ops hdr_fc_match hdr_action hdr_awdl tlv_awdl hdr_unavailable; do
    parameter="marionette_mon_$counter"
    if [ -f "/sys/module/kiwi_v2/parameters/$parameter" ]; then
      echo "$parameter=$(cat "/sys/module/kiwi_v2/parameters/$parameter")"
    fi
  done
}
if [ "$ACTION" = run-monitor ]; then
  [ "$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_sta_monitor)" = Y ] || exit 22
  iw dev wlan0 info > "$STATE/sta-info"
  FREQ="$(awk '$1 == "channel" {gsub(/[()]/,"",$3); print $3; exit}' "$STATE/sta-info")"
  WIDTH="$(awk '$1 == "channel" {for(i=1;i<=NF;i++) if($i == "width:") {print $(i+1); exit}}' "$STATE/sta-info")"
  CENTER="$(awk '$1 == "channel" {for(i=1;i<=NF;i++) if($i == "center1:") {print $(i+1); exit}}' "$STATE/sta-info")"
  case "$WIDTH" in 20|40|80|160) ;; *) exit 22 ;; esac
  [ -n "$FREQ" ] && [ -n "$CENTER" ] || exit 22
  /system/bin/timeout -k 2 30 ping -I wlan0 -c 48 -i 0.5 -W 2 "$GATEWAY" > "$STATE/ping-during-monitor" 2>&1 &
  PING_PID=$!
  /system/bin/timeout -k 2 8 iw dev wlan0 interface add awdlmon type monitor
  echo MONITOR_INTERFACE_CREATED
  /system/bin/timeout -k 2 10 ip link set awdlmon up
  echo MONITOR_INTERFACE_UP
  iw dev wlan0 link > "$STATE/link-monitor-up"
  cat "$STATE/link-monitor-up"
  grep -q '^Connected to ' "$STATE/link-monitor-up" || exit 23
  # The associated STA already owns the radio channel. Observe the DP monitor
  # without an explicit SET_CHANNEL (cfg80211 may reject a retune while busy).
  echo "MONITOR_FOLLOWS_STA_NO_RETUNE_REQUESTED freq=$FREQ width=$WIDTH center=$CENTER"
  iw dev awdlmon info > "$STATE/monitor-info"
  cat "$STATE/monitor-info"
  iw dev wlan0 link > "$STATE/link-monitor-created"
  cat "$STATE/link-monitor-created"
  grep -q '^Connected to ' "$STATE/link-monitor-created" || exit 23
  echo MONITOR_CREATED_STA_ASSOCIATION_RETAINED
  /system/bin/timeout -k 2 18 /data/local/tmp/marionette-awdl-witness awdlmon > "$STATE/monitor-witness" 2>&1 &
  WITNESS_PID=$!
  /system/bin/sleep 1
  SLOT=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_ms
  if [ -f "$SLOT" ]; then
    for invalid_duration in 0 201 2412:120 5745:201 5745:0 5745:120:1; do
      if echo "$invalid_duration" > "$SLOT" 2> "$STATE/invalid-slot-$invalid_duration"; then
        echo INVALID_CAPTURE_DURATION_ACCEPTED
        exit 25
      fi
    done
    echo INVALID_CAPTURE_DURATIONS_REJECTED
    capture_probe_status=0
    /system/bin/timeout -k 2 24 "$PROBE" wlan0 --direct-capture-sweep \
      > "$STATE/probe-capture-sweep" 2>&1 || capture_probe_status=$?
    cat "$STATE/probe-capture-sweep"
    echo "CAPTURE_SWEEP_EXIT=$capture_probe_status"
    [ "$capture_probe_status" = 0 ] || exit 26
  fi
  witness_status=0
  wait "$WITNESS_PID" || witness_status=$?
  WITNESS_PID=
  cat "$STATE/monitor-witness"
  [ "$witness_status" = 0 ] && grep -q '^WITNESS_SUMMARY ' "$STATE/monitor-witness" || exit 27
  rx_snapshot > "$STATE/rx-stages-after-monitor"
  cat "$STATE/rx-stages-after-monitor"
  /system/bin/timeout -k 2 8 iw dev awdlmon del
  # Exercise reuse after the asynchronous STOP/DELETE sequence, so a retained
  # object reference or stale per-vdev state cannot hide behind one teardown.
  /system/bin/timeout -k 2 8 iw dev wlan0 interface add awdlmon type monitor
  /system/bin/timeout -k 2 10 ip link set awdlmon up
  iw dev wlan0 link > "$STATE/link-monitor-recreated"
  grep -q '^Connected to ' "$STATE/link-monitor-recreated" || exit 24
  /system/bin/timeout -k 2 8 iw dev awdlmon del
  echo MONITOR_RECREATE_AND_SECOND_TEARDOWN_COMPLETED
  wait "$PING_PID" || true
  PING_PID=
  cat "$STATE/ping-during-monitor"
  iw dev wlan0 link > "$STATE/link-after-monitor"
  cat "$STATE/link-after-monitor"
  grep -q '^Connected to ' "$STATE/link-after-monitor" || exit 24
  /system/bin/timeout -k 2 12 ping -I wlan0 -c 3 -W 2 "$GATEWAY" > "$STATE/ping-after-monitor" 2>&1 || exit 24
  cat "$STATE/ping-after-monitor"
  rx_snapshot > "$STATE/rx-stages-final"
  cat "$STATE/rx-stages-final"
  echo STA_MONITOR_EXPERIMENT_FINISHED_DATA_TX_DISABLED
  exit 0
fi
if [ "$ACTION" = run-rawtx ] || [ "$ACTION" = run-rawtx-sta-vdev ]; then
  # 0034: the monitor vdev now has a TX lifecycle, and the exception path is
  # reachable while the normal STA is still associated (con_mode = 0). Submit
  # through it and read the driver's own submission counters. Host submission
  # is not over-the-air delivery; nothing here claims it is.
  [ "$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx)" = Y ] || exit 22
  iw dev wlan0 info > "$STATE/sta-info"
  cat "$STATE/sta-info"
  FREQ="$(awk '$1 == "channel" {gsub(/[()]/,"",$3); print $3; exit}' "$STATE/sta-info")"
  [ -n "$FREQ" ] || exit 22
  RAW_TX=/data/local/tmp/marionette-awdl-raw-tx
  [ -x "$RAW_TX" ] || exit 4
  counter() { cat "/sys/module/kiwi_v2/parameters/$1" 2>/dev/null || echo missing; }
  iface_stat() { cat "/sys/class/net/$1/statistics/$2" 2>/dev/null || echo missing; }
  # Overridable so a hard reset mid-run can be bisected down to a single frame
  # without editing the script: MARIONETTE_INJECT_COUNTS=1 sh volatile-stack-test.sh ...
  COUNTS="${MARIONETTE_INJECT_COUNTS:-24}"
  # Normal Wi-Fi before touching the radio at all.
  /system/bin/timeout -k 2 12 ping -I wlan0 -c 3 -W 2 "$GATEWAY" > "$STATE/ping-before-rawtx" 2>&1 || exit 14
  echo NORMAL_WIFI_GATEWAY_ROUNDTRIP_BEFORE_RAW_TX
  {
    echo "submitted=$(counter marionette_awdl_submitted)"
    echo "rejected=$(counter marionette_awdl_rejected)"
    echo "submitted_bytes=$(counter marionette_awdl_submitted_bytes)"
  } > "$STATE/counters-before"
  cat "$STATE/counters-before"
  /system/bin/timeout -k 2 8 iw dev wlan0 interface add awdlmon type monitor
  echo MONITOR_INTERFACE_CREATED
  /system/bin/timeout -k 2 10 ip link set awdlmon up
  echo MONITOR_INTERFACE_UP
  iw dev wlan0 link > "$STATE/link-monitor-up"
  cat "$STATE/link-monitor-up"
  grep -q '^Connected to ' "$STATE/link-monitor-up" || exit 23
  echo MONITOR_CREATED_STA_ASSOCIATION_RETAINED
  # The monitor follows the STA channel, which is where the frames are emitted.
  echo "RAW_TX_ON_STA_CHANNEL freq=$FREQ"
  /system/bin/timeout -k 2 20 ping -I wlan0 -c 12 -i 0.25 -W 2 "$GATEWAY" > "$STATE/ping-during-rawtx" 2>&1 &
  PING_PID=$!
  # Negative control first: one byte of the gated SNAP header is corrupted, so
  # a rejection is observable rather than indistinguishable from a frame that
  # never reached the driver.
  inject_status=0
  /system/bin/timeout -k 2 20 "$RAW_TX" awdlmon 2 ff:ff:ff:ff:ff:ff 100 1 > "$STATE/inject-negative" 2>&1 || inject_status=$?
  cat "$STATE/inject-negative"
  [ "$inject_status" = 0 ] || exit 28
  sleep 1
  {
    echo "submitted=$(counter marionette_awdl_submitted)"
    echo "rejected=$(counter marionette_awdl_rejected)"
    echo "submitted_bytes=$(counter marionette_awdl_submitted_bytes)"
  } > "$STATE/counters-negative"
  cat "$STATE/counters-negative"
  negative_rejected=$(awk -F= '/^rejected=/ {print $2}' "$STATE/counters-negative")
  negative_submitted=$(awk -F= '/^submitted=/ {print $2}' "$STATE/counters-negative")
  # Now the well-formed frames.
  inject_status=0
  /system/bin/timeout -k 2 20 "$RAW_TX" awdlmon "$COUNTS" ff:ff:ff:ff:ff:ff 200 > "$STATE/inject-well-formed" 2>&1 || inject_status=$?
  cat "$STATE/inject-well-formed"
  [ "$inject_status" = 0 ] || exit 28
  /system/bin/sleep 2
  /system/bin/timeout -k 2 8 iw dev wlan0 link > "$STATE/link-during-rawtx" 2>&1
  cat "$STATE/link-during-rawtx"
  {
    echo "submitted=$(counter marionette_awdl_submitted)"
    echo "rejected=$(counter marionette_awdl_rejected)"
    echo "submitted_bytes=$(counter marionette_awdl_submitted_bytes)"
  } > "$STATE/counters-after"
  cat "$STATE/counters-after"
  echo "awdlmon tx_packets=$(iface_stat awdlmon tx_packets) tx_dropped=$(iface_stat awdlmon tx_dropped) tx_bytes=$(iface_stat awdlmon tx_bytes)"
  echo "awdlmon tx_packets=$(iface_stat awdlmon tx_packets) tx_dropped=$(iface_stat awdlmon tx_dropped) tx_bytes=$(iface_stat awdlmon tx_bytes)" >> "$STATE/counters-after"
  /system/bin/timeout -k 2 8 iw dev awdlmon del
  echo MONITOR_INTERFACE_REMOVED
  wait "$PING_PID" || true
  PING_PID=
  cat "$STATE/ping-during-rawtx"
  iw dev wlan0 link > "$STATE/link-after-rawtx"
  cat "$STATE/link-after-rawtx"
  grep -q '^Connected to ' "$STATE/link-after-rawtx" || exit 24
  /system/bin/timeout -k 2 12 ping -I wlan0 -c 3 -W 2 "$GATEWAY" > "$STATE/ping-after-rawtx" 2>&1 || exit 24
  cat "$STATE/ping-after-rawtx"
  echo WIFI_ASSOCIATION_AND_GATEWAY_PRESERVED_AFTER_RAW_TX
  # Operator-visible pass/fail summary. The reasoning is recorded in the
  # README; the numbers below are what the decision rests on.
  negative_submitted=$(awk -F= '/^submitted=/ {print $2}' "$STATE/counters-negative")
  negative_rejected=$(awk -F= '/^rejected=/ {print $2}' "$STATE/counters-negative")
  after_submitted=$(awk -F= '/^submitted=/ {print $2}' "$STATE/counters-after")
  after_rejected=$(awk -F= '/^rejected=/ {print $2}' "$STATE/counters-after")
  after_bytes=$(awk -F= '/^submitted_bytes=/ {print $2}' "$STATE/counters-after")
  echo "SUMMARY negative_control submitted=$negative_submitted rejected=$negative_rejected"
  echo "SUMMARY after_well_formed submitted=$after_submitted rejected=$after_rejected"
  echo "SUMMARY frames_sent=$COUNTS submitted_delta=$((after_submitted - negative_submitted)) rejected_delta=$((after_rejected - negative_rejected))"
  echo "SUMMARY submitted_bytes=$after_bytes"
  [ "$negative_submitted" != missing ] && [ "$after_submitted" != missing ] || exit 29
  [ "$negative_rejected" -gt 0 ] || exit 30
  [ "$((after_submitted - negative_submitted))" = "$COUNTS" ] || exit 31
  [ "$((after_rejected - negative_rejected))" = 0 ] || exit 32
  echo RAW_TX_HOST_SUBMISSION_ACCEPTED_REJECTION_COUNTERS_CONSISTENT
  echo RAW_TX_OTA_DELIVERY_UNVERIFIED
  # Which vdev carried the frames.  0037 to 0039 all used the monitor
  # adapter's own vdev; run-rawtx-sta-vdev (0040) pins them to the station.
  case "$ACTION" in
    run-rawtx-sta-vdev)
      echo "SUMMARY carrier_vdev=0 station (marionette_awdl_raw_tx_vdev=0)"
      echo "SUMMARY carrier_param=$(cat /sys/module/kiwi_v2/parameters/marionette_awdl_raw_tx_vdev 2>/dev/null || echo missing)" ;;
    *) echo "SUMMARY carrier_vdev=monitor_adapter_own default" ;;
  esac
  exit 0
fi
rx_snapshot > "$STATE/rx-stages-before"
/system/bin/timeout -k 2 24 "$PROBE" wlan0 --sweep > "$STATE/probe-rx-sweep" 2>&1 || exit 21
cat "$STATE/probe-rx-sweep"
rx_snapshot > "$STATE/rx-stages-after-sweep"
cat "$STATE/rx-stages-after-sweep"
probe_status=0
/system/bin/timeout -k 2 10 "$PROBE" wlan0 --transmit 5220 > "$STATE/probe-disabled" 2>&1 || probe_status=$?
cat "$STATE/probe-disabled"
[ "$probe_status" = 3 ] && grep -q 'AWDL_MGMT_TX result=-107 ' "$STATE/probe-disabled" || exit 15
echo DISABLED_GATE_REJECTED_AWDL_AS_EXPECTED
echo Y > /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx
/system/bin/timeout -k 2 20 ping -I wlan0 -c 24 -i 0.5 -W 2 "$GATEWAY" \
  > "$STATE/ping-during-mgmt" 2>&1 &
PING_PID=$!
probe_status=0
/system/bin/timeout -k 2 10 "$PROBE" wlan0 --transmit 5220 > "$STATE/probe-enabled" 2>&1 || probe_status=$?
cat "$STATE/probe-enabled"
offchannel_status=0
/system/bin/timeout -k 2 10 "$PROBE" wlan0 --transmit 2437 > "$STATE/probe-enabled-2437" 2>&1 || offchannel_status=$?
cat "$STATE/probe-enabled-2437"
echo N > /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx
echo "ENABLED_GATE_PROBE_EXIT=$probe_status"
echo "ENABLED_GATE_2437_PROBE_EXIT=$offchannel_status"
wait "$PING_PID" || true
PING_PID=
cat "$STATE/ping-during-mgmt"
iw dev wlan0 link > "$STATE/link-after-mgmt"
cat "$STATE/link-after-mgmt"
grep -q '^Connected to ' "$STATE/link-after-mgmt" || exit 16
/system/bin/timeout -k 2 12 ping -I wlan0 -c 3 -W 2 "$GATEWAY" > "$STATE/ping-after-mgmt" 2>&1 || exit 16
cat "$STATE/ping-after-mgmt"
echo WIFI_ASSOCIATION_AND_GATEWAY_PRESERVED_AFTER_MGMT
rx_snapshot > "$STATE/rx-stages-final"
cat "$STATE/rx-stages-final"
# Host submission is a separate result from firmware/OTA receipt.
[ "$probe_status" = 0 ] && [ "$offchannel_status" = 0 ] || exit 17
echo AWDL_MANAGEMENT_HOST_SUBMISSION_ACCEPTED_OTA_UNVERIFIED
