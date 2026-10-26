#!/system/bin/sh
# Marionette AirDrop radio bring-up.
#
# This script deliberately does NOT switch the Wi-Fi driver into global monitor
# mode (`con_mode=4`). The kiwi_v2 firmware refuses AWDL frames injected through
# a monitor vdev -- one fixed assert, regardless of the TX metadata the host
# supplies (ROADMAP §14, patch 0040) -- so that layout can receive but can never
# make the phone visible to a peer.
#
# Layout:
#
#   RX  one resident monitor vdev (`awdlmon`), opened once and never deleted,
#       with the driver's capture filter toggled on and off; recognised AWDL
#       frames are delivered by patch 0047 to the `awdlsink` netdev, which
#       filin reads continuously
#   TX  NL80211_CMD_FRAME on the station (`wlan0`), which reaches the WMI
#       MGMT_TX path Apple's own ACKs were observed on
#
# Why resident instead of a window that opens and closes a vdev each cycle:
# every monitor-vdev DELETE costs exactly one
# `target_if_vdev_mgr_rsp_timer_start: DELETE requested, waiting for STOP
# response` assert, because the firmware's STOP response is never accounted
# for. Churning a vdev every 4 s accumulated those until the whole device
# rebooted (twice, measured). Patch 0049 made `capture_hold=0` really reset the
# monitor filters, and patch 0050 mutes the beacon-miss indication the capture
# diverts, so a resident vdev now keeps the station associated with zero
# asserts: 120 s and 300 s soaks with the station up the whole time and +160
# AWDL frames.
#
# This remains an experimental layout. Successful kernel submission is not
# proof of peer reception.
#
# Usage: radio.sh <backend> <state-dir> <app-pid>
#   <backend>  libmarionette_awdl.so (filin)
set -u

BACKEND="$1"
STATE="$2"
APP_PID="$3"
MON=awdlmon
# The interface filin reads. It is NOT the monitor vdev: patch 0047 makes the
# driver deliver recognised AWDL frames to a host-only netdev of this name, so
# the vdev can come and go underneath a reader that never rebinds.
SINK=awdlsink
TX_IF=wlan0
HOST_IF=awdl0
HTTP_PORT=${MARIONETTE_AWDL_HTTP_PORT:-9930}
# How long the capture filters stay on, and how long they stay off. The filters
# admit AWDL frames but divert station traffic. Short windows limit each
# blackout. Millisecond overrides allow bounded coexistence experiments;
# the 200/400ms trial did not establish improved loss, so retain the baseline.
WINDOW_MS=${MARIONETTE_AWDL_WINDOW_MS:-2000}
PERIOD_MS=${MARIONETTE_AWDL_PERIOD_MS:-4000}
P=/sys/module/kiwi_v2/parameters
CAPTURE_HOLD=$P/marionette_awdl_capture_hold
BMISS_MUTE=$P/marionette_awdl_bmiss_mute
SINK_PARAM=$P/marionette_awdl_sink
MGMT_TX=/sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx
CHILD=
MON_RESIDENT=0
TAP_OWNED=0
HOLD_WAS=
MUTE_WAS=
MGMT_WAS=
ROUTE_OWNED=0
RULE_OWNED=0
SOURCE_RULE_OWNED=0
HOST_IPV6=
ROUTE_TABLE=7400
ROUTE_PREF=10990

setup_ipv6_route() {
  # Android does not consult main for arbitrary TAP interfaces. Scope IDs alone
  # cannot select the kernel's per-interface table. Own a narrow route/rule.
  [ -z "$(ip -6 route show table "$ROUTE_TABLE" 2>/dev/null)" ] || return 1
  ip -6 rule show | grep -q "^$ROUTE_PREF:" && return 1
  ip -6 route add fe80::/64 dev "$HOST_IF" table "$ROUTE_TABLE" || return 1
  ROUTE_OWNED=1
  ip -6 rule add pref "$ROUTE_PREF" to fe80::/64 oif "$HOST_IF" lookup "$ROUTE_TABLE" || return 1
  RULE_OWNED=1
  # Replies on an accepted wildcard TCP listener need not carry oif. Route
  # this TAP's exact source address too, rather than falling back to wlan0.
  tick=0
  while [ "$tick" -lt 30 ]; do
    HOST_IPV6=$(ip -6 -o addr show dev "$HOST_IF" scope link | awk '{print $4}' | cut -d/ -f1 | head -1)
    [ -z "$HOST_IPV6" ] || break
    sleep 0.1
    tick=$((tick + 1))
  done
  [ -n "$HOST_IPV6" ] || return 1
  ip -6 rule show | grep -q "^10991:" && return 1
  ip -6 rule add pref 10991 from "$HOST_IPV6/128" to fe80::/64 lookup "$ROUTE_TABLE" || return 1
  SOURCE_RULE_OWNED=1
}


fail() {
  echo "$1" > "$STATE/state"
  echo "$1"
  exit "$2"
}

# --- resident monitor vdev --------------------------------------------------
# Open a monitor vdev once and leave it open. Every step here is load-bearing:
#   * `iw phy ... interface add` often reports EINVAL after creating the netdev,
#     so existence is judged with `iw dev`, never by its exit status.
#   * the netdev appears asynchronously (MON_INIT takes ~100 ms), so an
#     immediate `iw dev` check can miss a creation that is about to succeed.
#   * `ip link set ... up` must precede any hold write: the setter requires
#     DEVICE_IFACE_OPENED on the monitor adapter and returns -ENOTCONN without
#     it, with no dmesg line at all -- indistinguishable from a missing patch.
#   * the mute must be in place before the first hold, otherwise the firmware's
#     latched beacon-miss indication disconnects the station ~13 s later even
#     though the filters were released in time (patch 0050).
mon_open() {
  [ "$MON_RESIDENT" = 1 ] && return 0
  if ! iw dev "$MON" info >/dev/null 2>&1; then
    WIPHY=$(iw dev "$TX_IF" info 2>/dev/null | sed -n 's/.*wiphy \([0-9]*\).*/\1/p' | head -1)
    iw phy "phy${WIPHY:-0}" interface add "$MON" type monitor 2>/dev/null || true
    tick=0
    while [ "$tick" -lt 20 ]; do
      iw dev "$MON" info >/dev/null 2>&1 && break
      sleep 0.05
      tick=$((tick + 1))
    done
    iw dev "$MON" info >/dev/null 2>&1 || return 1
  fi
  # From here on the vdev is ours, so cleanup must delete it even if a later
  # step fails -- otherwise a single bad start leaks an interface that the next
  # start then refuses to run alongside.
  MON_RESIDENT=1
  ip link set "$MON" up 2>/dev/null || return 1
  echo 0 > "$CAPTURE_HOLD" 2>/dev/null || return 1
  echo 1 > "$BMISS_MUTE" 2>/dev/null || return 1
  return 0
}

mon_close() {
  [ "$MON_RESIDENT" = 1 ] || return 0
  echo 0 > "$CAPTURE_HOLD" 2>/dev/null
  echo 0 > "$BMISS_MUTE" 2>/dev/null
  ip link set "$MON" down 2>/dev/null
  iw dev "$MON" del 2>/dev/null
  MON_RESIDENT=0
}

hold_on()  { echo 1 > "$CAPTURE_HOLD" 2>/dev/null; }
hold_off() { echo 0 > "$CAPTURE_HOLD" 2>/dev/null; }

# Opening can fail for reasons that clear up on their own: a roam in progress
# makes the hold setter return -ENOTCONN, and the driver refuses a new vdev for
# a moment after the previous one was deleted. A resident service must ride
# those out instead of exiting.
mon_open_retry() {
  attempt=0
  while [ "$attempt" -lt 3 ]; do
    attempt=$((attempt + 1))
    mon_open && return 0
    mon_close
    sleep 0.4
  done
  return 1
}

cleanup() {
  trap '' HUP INT TERM
  [ "$SOURCE_RULE_OWNED" = 0 ] || ip -6 rule del pref 10991 from "$HOST_IPV6/128" to fe80::/64 lookup "$ROUTE_TABLE"
  SOURCE_RULE_OWNED=0
  [ "$RULE_OWNED" = 0 ] || ip -6 rule del pref "$ROUTE_PREF" to fe80::/64 oif "$HOST_IF" lookup "$ROUTE_TABLE"
  [ "$ROUTE_OWNED" = 0 ] || ip -6 route del fe80::/64 dev "$HOST_IF" table "$ROUTE_TABLE"
  RULE_OWNED=0
  ROUTE_OWNED=0
  if [ -n "$CHILD" ]; then
    kill -9 "$CHILD" 2>/dev/null
    # Wait for the backend to actually die before touching its TAP: `ip link
    # del` on a TUN device that a live process still holds returns EBUSY, and
    # the leftover then makes the next start fail with "could not create TAP
    # interface", which looks like a permissions problem and is not one.
    tick=0
    while [ "$tick" -lt 20 ] && kill -0 "$CHILD" 2>/dev/null; do
      sleep 0.1
      tick=$((tick + 1))
    done
  fi
  # Leave the radio exactly as we found it: filters off, mute off, monitor vdev
  # gone, TAP deleted.
  mon_close
  [ -z "$HOLD_WAS" ] || echo "$HOLD_WAS" > "$CAPTURE_HOLD" 2>/dev/null
  [ -z "$MUTE_WAS" ] || echo "$MUTE_WAS" > "$BMISS_MUTE" 2>/dev/null
  [ -z "$MGMT_WAS" ] || echo "$MGMT_WAS" > "$MGMT_TX" 2>/dev/null
  if [ "$TAP_OWNED" = 1 ]; then
    tick=0
    while [ "$tick" -lt 20 ]; do
      ip link show "$HOST_IF" >/dev/null 2>&1 || break
      ip link del "$HOST_IF" 2>/dev/null && break
      sleep 0.1
      tick=$((tick + 1))
    done
  fi
  rm -f "$STATE/active"
  echo stopped > "$STATE/state"
}

mkdir -p "$STATE" || exit 3

# --- capability gates -------------------------------------------------------
# Every check below is a *capability*, not a fingerprint. The previous version
# refused whenever the driver file's SHA256 matched stock, which was wrong on a
# device running a patched module loaded from elsewhere, and would have accepted
# a truncated file just as happily.
[ -x "$BACKEND" ] || fail "AirDrop 后端缺失，请重新安装应用" 2
command -v iw >/dev/null 2>&1 || fail "系统缺少 iw 工具" 9
[ -w "$CAPTURE_HOLD" ] && [ -w "$MGMT_TX" ] || \
  fail "当前驱动缺少此实验后端需要的收发接口，不能启动；普通 Wi-Fi 保持原状" 9
# The host-only sink (patch 0047) is what makes a resident monitor vdev
# readable by a process that never rebinds.
[ -w "$SINK_PARAM" ] || \
  fail "当前驱动没有 AirDrop 接收汇聚接口（需要补丁 0047），不能启动；普通 Wi-Fi 保持原状" 9
# Patch 0050. Without it a resident vdev disconnects the station about 13 s
# after the first capture window, so the service would look "up" while silently
# losing Wi-Fi. Refuse by name instead.
[ -w "$BMISS_MUTE" ] || \
  fail "当前驱动缺少常驻接收所需的掉线抑制（需要补丁 0050），不能启动；普通 Wi-Fi 保持原状" 9
# The hold setter is gated on patch 0009's sta-monitor switch. Without it the
# write returns -EOPNOTSUPP, the window receives nothing, and the failure looks
# like "no AWDL peer nearby" -- so check it here where the cause is nameable.
STA_MON=$P/marionette_awdl_sta_monitor
if [ -r "$STA_MON" ] && [ "$(cat "$STA_MON")" != "Y" ]; then
  fail "驱动未以 sta_monitor=1 加载，接收窗口收不到任何帧；请用 awdl-stack-load.sh 重新加载驱动" 9
fi

# Hotspot and AWDL both want the radio; refuse instead of producing a half
# working state where neither is usable.
# StartedState also belongs to WifiClientModeManager; it is not a hotspot
# state. Inspect actual nl80211 interface types instead of unscoped dumpsys.
hotspot_active() {
  iw dev 2>/dev/null | awk '$1 == "type" && ($2 == "AP" || $2 == "AP/VLAN") { found=1 } END { exit !found }'
}
if hotspot_active; then
  fail "请先关闭手机热点" 6
fi

# --- clear anything a previous run left behind ------------------------------
# A killed backend leaves its TAP (`awdl0`) and, because the app installs the
# same binary as `libmarionette_airdrop.so`, a receiver still holding the
# AirDrop HTTPS port. Both turn the next start into a confusing failure
# ("could not create TAP interface" / "Address already in use"), so clear them
# before doing anything else.
#
# A resident service is killed with SIGKILL often enough (app update, low
# memory, `am force-stop`) that leftovers are the normal case, not the
# exception. Refusing on them would leave the service permanently unable to
# start. Only refuse when a backend is actually still alive; otherwise the
# interfaces are stale and ours to reclaim.
backend_running() {
  for proc in /proc/[0-9]*; do
    [ -r "$proc/cmdline" ] || continue
    # argv[0] only: this script's own command line carries the backend path as
    # an argument, and so does the `su -c "..."` wrapper around it, so matching
    # anywhere in cmdline would always find "a live backend" and refuse to run.
    argv0=$(tr '\0' '\n' < "$proc/cmdline" 2>/dev/null | head -1)
    case "$argv0" in
      *libmarionette_awdl.so*) return 0 ;;
    esac
  done
  return 1
}

if ip link show "$HOST_IF" >/dev/null 2>&1 || iw dev "$MON" info >/dev/null 2>&1; then
  if backend_running; then
    fail "已有 AirDrop 实验接口，请先停止其他会话后重试" 9
  fi
  iw dev "$MON" del >/dev/null 2>&1
  ip link del "$HOST_IF" >/dev/null 2>&1
fi

# Hard platform requirement, measured rather than assumed: this driver opens a
# monitor vdev only while the station holds a link (`__hdd_mon_open` returns
# -ENOTCONN otherwise, dmesg: MARIONETTE_STA_MONITOR_INIT_RESULT ret=-107), and
# remain-on-channel is refused with -EINVAL in the same state. Without an
# association there is therefore no way to hear an AWDL peer at all, so say so
# instead of starting an engine that can never see anything.
#
# Wait rather than sample once: Xiaomi's Wi-Fi service cycles the link for a
# second or two at a time, and a single check turns that blip into a spurious
# "not connected" error.
tick=0
while [ "$tick" -lt 60 ]; do
  iw dev "$TX_IF" link 2>/dev/null | grep -q '^Connected' && break
  sleep 0.5
  tick=$((tick + 1))
done
if ! iw dev "$TX_IF" link 2>/dev/null | grep -q '^Connected'; then
  fail "请先连接一个 Wi-Fi 网络 —— AirDrop 需要 Wi-Fi 芯片保持联网" 7
fi

# The channel the station is actually on. This is not a preference — the radio
# cannot be on two channels, and in coexistence mode the platform owns it. The
# anchor we advertise must be the truth, otherwise peers follow our channel
# sequence to a channel we are not transmitting on and stop hearing us.
CHANNEL=$(iw dev "$TX_IF" info 2>/dev/null | sed -n 's/.*channel \([0-9]*\) .*/\1/p' | head -1)
[ -n "$CHANNEL" ] || fail "无法读取当前 Wi-Fi 信道" 7

# --- prime the sink, then start the reader ----------------------------------
HOLD_WAS=$(cat "$CAPTURE_HOLD" 2>/dev/null)
MUTE_WAS=$(cat "$BMISS_MUTE" 2>/dev/null)
MGMT_WAS=$(cat "$MGMT_TX" 2>/dev/null)
trap cleanup EXIT
trap 'exit 0' HUP INT TERM
ip link set "$TX_IF" up 2>/dev/null

# `awdlsink` is created lazily by the driver on the first AWDL frame it tries
# to deliver, so one capture window has to run before filin can bind to it. The
# first frame of that window is the one lost to the creation (counted in
# marionette_awdl_sink_drop); everything after it is delivered.
#
# Retry: a peer may simply not have transmitted during a single window, and a
# window can also fail outright while the link is settling. Failing on the
# first attempt would turn "the Mac was quiet for two seconds" into a dead
# service. The vdev stays resident across attempts -- only the filters toggle.
primed=0
attempt=0
while [ "$attempt" -lt 6 ]; do
  attempt=$((attempt + 1))
  if mon_open_retry; then
    hold_on
    tick=0
    while [ "$tick" -lt 40 ]; do
      [ -e "/sys/class/net/$SINK" ] && break
      sleep 0.1
      tick=$((tick + 1))
    done
    hold_off
    if [ -e "/sys/class/net/$SINK" ]; then
      primed=1
      break
    fi
  fi
  sleep 1
done
[ "$primed" = 1 ] || \
  fail "AirDrop 接收汇聚接口未出现（连续窗口内没有收到任何 AWDL 帧），请确认附近有可发现的设备后重试" 8

# TX gate: cfg80211 refuses non-public action frames unless this is enabled, and
# it defaults to off. Writing it is idempotent.
[ -w "$MGMT_TX" ] && echo Y > "$MGMT_TX" 2>/dev/null

touch "$STATE/active"
echo preparing > "$STATE/state"

# --- run --------------------------------------------------------------------
# FILIN_TX_IFACE switches filin's transmit backend from AF_PACKET injection on
# the monitor vdev (firmware-refused here) to NL80211_CMD_FRAME on the station,
# and tells it not to retune the radio, which belongs to the platform.
#
# `--no-force-master` is required, not cosmetic. filin forces itself to win the
# AWDL election by default, which means it never adopts a peer's master and so
# never syncs: /status stayed at synced=false, master_anchor=0 and
# aw_alignment_pct=0 for as long as the sink had no TSFT to sync on. With the
# descriptor timestamp in the radiotap header (patch 0051) the normal election
# works, and it is the difference between "hears frames" and "shares the peer's
# availability windows":
#
#   force-master     synced=false  anchor=0  alignment=0%   (default)
#   normal election  synced=true   anchor=6  alignment 50 -> 81%
#
# `-f` is --no-rssi-filter, not force-master; keep it so weak peers are still
# admitted.
FILIN_TX_IFACE="$TX_IF" "$BACKEND" \
  -i "$SINK" -N -f -c "$CHANNEL" --park --no-force-master \
  --http-addr "127.0.0.1:$HTTP_PORT" \
  >"$STATE/filin.log" 2>&1 &
CHILD=$!
TAP_OWNED=1

# Report the moment the engine answers, so the UI can distinguish "starting"
# from "running but nothing heard".
tick=0
while [ "$tick" -lt 100 ]; do
  if ! kill -0 "$CHILD" 2>/dev/null; then
    break
  fi
  if grep -q "transmitting via NL80211_CMD_FRAME" "$STATE/filin.log" 2>/dev/null; then
    setup_ipv6_route || fail "无法配置 AirDrop IPv6 路由，请检查实验路由冲突" 10
    echo listening > "$STATE/state"
    break
  fi
  sleep 0.2
  tick=$((tick + 1))
done

# --- resident capture supervisor -------------------------------------------
# filin is bound to $SINK and never rebinds. All this loop does is hand it
# bursts of air: hold the filters so the monitor ring fills, release them so the
# station keeps its beacons. The vdev is never deleted, so no
# target_if_vdev_mgr assert is generated and the device stays up.
#
# The loop also owns the guardian duty: outlive the app. The app pid and the
# stop file are the only conditions that end it.
case "$WINDOW_MS:$PERIOD_MS" in *[!0-9:]*|:*|*:) fail "接收窗口参数无效" 11 ;; esac
[ "$WINDOW_MS" -ge 50 ] && [ "$PERIOD_MS" -gt "$WINDOW_MS" ] && [ "$PERIOD_MS" -le 4000 ] || fail "接收窗口参数超出范围" 11
GAP_MS=$((PERIOD_MS - WINDOW_MS))

# Short capture gaps keep TCP retransmissions and AP beacon loss bounded.
# Check lifecycle at least every 200ms even during a longer retry wait.
nap_ms() {
  remaining_ms=$1
  while [ "$remaining_ms" -gt 0 ]; do
    [ -f "$STATE/stop" ] && return 1
    kill -0 "$APP_PID" 2>/dev/null || return 1
    kill -0 "$CHILD" 2>/dev/null || return 1
    chunk_ms=$remaining_ms
    [ "$chunk_ms" -le 200 ] || chunk_ms=200
    sleep "$(printf '%d.%03d' "$((chunk_ms / 1000))" "$((chunk_ms % 1000))")"
    remaining_ms=$((remaining_ms - chunk_ms))
  done
  return 0
}
nap() { nap_ms "$(($1 * 1000))"; }

# Link liveness. The mute hides the firmware's beacon-miss indication, so the
# link is judged by the station's own view instead: two consecutive samples
# without an association mean the link is really gone. Tearing the vdev down
# lets the framework reconnect cleanly, and the next iteration reopens it.
lost=0
while :; do
  if ! iw dev "$TX_IF" link 2>/dev/null | grep -q '^Connected'; then
    lost=$((lost + 1))
    if [ "$lost" -ge 2 ]; then
      # No association means no monitor vdev and no channel to follow; a window
      # would only fail. Drop ours so the framework can reconnect.
      mon_close
      echo waiting-link > "$STATE/state"
    fi
    nap 2 || break
    continue
  fi
  lost=0
  live_channel=$(iw dev "$TX_IF" info 2>/dev/null | sed -n 's/.*channel \([0-9]*\) .*/\1/p' | head -1)
  if [ -n "$live_channel" ] && [ "$live_channel" != "$CHANNEL" ]; then
    echo "station channel changed: $CHANNEL -> $live_channel; restarting backend"
    break
  fi
  if [ "$MON_RESIDENT" != 1 ]; then
    if mon_open_retry; then
      echo listening > "$STATE/state"
    else
      nap 2 || break
      continue
    fi
  fi
  hold_on
  nap_ms "$WINDOW_MS" || { hold_off; break; }
  hold_off
  nap_ms "$GAP_MS" || break
done

echo backend exited > "$STATE/state"
