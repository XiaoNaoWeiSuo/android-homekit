#!/system/bin/sh
# Decide, with one measurement, whether the firmware will hand AWDL frames to
# the host when WMI_PDEV_SET_RX_FILTER_PROMISCUOUS is enabled.
#
# The whole question of a resident AirDrop receive path reduces to this: the
# firmware delivers 1713 management frames to the STA vdev and not one is AWDL,
# while the monitor vdev receives 1020 of the same frames.  If enabling the
# promiscuous filter makes marionette_awdl_rx_wmi move, the host side (already
# wired by patch 0006) becomes reachable and the monitor vdev can be dropped.
#
# Usage: fw-promisc-probe.sh [seconds]
set -u

DUR=${1:-30}
case "$DUR" in ''|*[!0-9]*) echo 'duration must be 1..60 seconds'; exit 2;; esac
[ "$DUR" -ge 1 ] && [ "$DUR" -le 60 ] || exit 2
P=/sys/module/kiwi_v2/parameters
GATE=$P/marionette_awdl_fw_promisc
WMI=$P/marionette_awdl_rx_wmi
WMI_ALL=$P/marionette_mgmt_rx_wmi
LOG=/data/local/tmp/fw-promisc.log

say() { echo "$*"; echo "$*" >>"$LOG"; }

: >"$LOG"

say "=== driver params present? ==="
if [ ! -f "$GATE" ]; then
  say "FAIL: $GATE missing — the loaded kiwi_v2 does not contain patch 0046"
  exit 2
fi
say "gate=$(cat "$GATE") (mode $(stat -c %a "$GATE" 2>/dev/null))"

say "=== RX counter gate (patch 0006) ==="
# marionette_awdl_rx_observe() early-returns on !marionette_awdl_mgmt_rx, so
# BOTH the AWDL counter and the all-management control counter are dead unless
# this gate is on.  Without enabling it here the probe would report NO_EFFECT
# even when the firmware obeyed.  wma_mgmt.c:3872 is the WMI-stage call site.
MGMT_RX=$P/marionette_awdl_mgmt_rx
if [ ! -f "$MGMT_RX" ]; then
  say "FAIL: $MGMT_RX missing — patch 0006 is not in the loaded module"
  exit 5
fi
# bool module params print Y/N, not 1/0.  This one is 0400 (set at insmod by
# awdl-stack-load.sh), so it cannot be written here — only verified.
case "$(cat "$MGMT_RX")" in
  Y|y|1) say "marionette_awdl_mgmt_rx=Y (counter gate open)";;
  *) say "FAIL: $MGMT_RX is $(cat "$MGMT_RX"), not Y — reload with marionette_awdl_mgmt_rx=1"
     exit 5;;
esac

say "=== station state ==="
if ! iw dev wlan0 link 2>/dev/null | grep -q '^Connected'; then
  say "FAIL: wlan0 not associated — the experiment needs a live station"
  exit 3
fi
say "$(iw dev wlan0 link | head -1)"
say "channel=$(iw dev wlan0 info | sed -n 's/.*channel \([0-9]*\) .*/\1/p' | head -1)"
START_LINK=$(iw dev wlan0 link | head -1)
START_CHANNEL=$(iw dev wlan0 info | sed -n 's/.*channel \([0-9]*\) .*/\1/p' | head -1)
ORIGINAL_GATE=$(cat "$GATE")
LINK_CHANGED=0
restore() { echo "$ORIGINAL_GATE" >"$GATE" 2>/dev/null; }
trap restore EXIT
trap 'exit 130' INT TERM HUP

say "=== baseline (filter off) ==="
echo 0 >"$GATE" 2>/dev/null
B_WMI=$(cat "$WMI" 2>/dev/null)
B_ALL=$(cat "$WMI_ALL" 2>/dev/null)
say "awdl_rx_wmi=$B_WMI mgmt_rx_wmi=$B_ALL"

say "=== enabling firmware promiscuous filter ==="
if ! echo 1 >"$GATE" 2>/dev/null; then
  say "FAIL: could not write 1 to $GATE"
  exit 4
fi
say "gate=$(cat "$GATE")"

say "=== observing for ${DUR}s (a peer must be transmitting) ==="
i=0
while [ "$i" -lt "$DUR" ]; do
  /system/bin/sleep 1
  i=$((i + 1))
  if [ $((i % 5)) -eq 0 ]; then
    CHANNEL=$(iw dev wlan0 info | sed -n 's/.*channel \([0-9]*\) .*/\1/p' | head -1)
    [ "$(iw dev wlan0 link | head -1)" = "$START_LINK" ] && [ "$CHANNEL" = "$START_CHANNEL" ] || LINK_CHANGED=1
    say "  t=${i}s awdl_rx_wmi=$(cat "$WMI" 2>/dev/null) mgmt_rx_wmi=$(cat "$WMI_ALL" 2>/dev/null)"
  fi
done

A_WMI=$(cat "$WMI" 2>/dev/null)
A_ALL=$(cat "$WMI_ALL" 2>/dev/null)

say "=== restoring ==="
restore
say "gate=$(cat "$GATE")"

say "=== verdict ==="
say "awdl_rx_wmi: $B_WMI -> $A_WMI   (delta $((A_WMI - B_WMI)))"
say "mgmt_rx_wmi: $B_ALL -> $A_ALL   (delta $((A_ALL - B_ALL)))"
say "channel=$START_CHANNEL link_changed=$LINK_CHANGED"
if [ "$LINK_CHANGED" = 1 ]; then
  say 'RESULT: INCONCLUSIVE_LINK_CHANGED — repeat with a stable channel and independent peer evidence.'
  exit 1
fi
if [ "$A_WMI" -gt "$B_WMI" ]; then
  say "RESULT: FIRMWARE_OBEYED — AWDL frames now reach the host through the STA"
  say "         management path. This does not prove AWDL data/IPv6 reception."
  exit 0
fi
if [ "$A_ALL" -gt "$B_ALL" ]; then
  say "RESULT: INCONCLUSIVE_NO_AWDL — other management frames arrived, but"
  say "         this alone proves neither a filter effect nor AWDL rejection."
  say "         Scan responses can move this counter; require an independent"
  say "         capture of an active peer on the same channel and time window."
  exit 1
fi
say "RESULT: NO_EFFECT — neither counter moved. Either no peer was transmitting"
say "         during the window, or the firmware ignores the command. Re-run"
say "         with a known-active peer before concluding."
exit 1
