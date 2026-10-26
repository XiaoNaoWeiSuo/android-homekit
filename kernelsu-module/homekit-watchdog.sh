#!/system/bin/sh
# Independent of the app process. A disabled driver stays disabled.
MODDIR="${0%/*}"
PACKAGE=dev.local.mihotspot
SERVICE="$PACKAGE/.HomeKitService"
PREFS="/data/user/0/$PACKAGE/shared_prefs/homekit.xml"
LOCK="$MODDIR/.homekit-watchdog.lock"
TAG=Marionette-KSU

log_message() { /system/bin/log -t "$TAG" "$*" 2>/dev/null || true; }
uptime_seconds() { cut -d. -f1 /proc/uptime; }
user_unlocked() {
  [ "$(getprop sys.user.0.ce_available)" = "true" ] && return 0
  /system/bin/timeout -k 2 6 dumpsys user 2>/dev/null | grep -q 'Started users state:.*0=RUNNING_UNLOCKED'
}
pause_interval() {
  target=$(( $(uptime_seconds) + 30 ))
  # Child exits can interrupt sleep on some su/BusyBox builds. Measure time,
  # and use the system implementation to avoid a tight respawn loop.
  while [ "$(uptime_seconds)" -lt "$target" ]; do /system/bin/sleep 5; done
}

if ! mkdir "$LOCK" 2>/dev/null; then
  # The other boot hook may have created the directory just before its pid file.
  [ -s "$LOCK/pid" ] || /system/bin/sleep 1
  owner="$(cat "$LOCK/pid" 2>/dev/null)"
  if [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null &&
      tr '\000' ' ' < "/proc/$owner/cmdline" 2>/dev/null | grep -q 'homekit-watchdog.sh'; then
    exit 0
  fi
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || exit 0
fi
echo "$$" > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT
trap '' HUP
trap 'exit 0' INT TERM
last_restart=0
stalled_pid=""
stalled_heartbeat=""
log_message "homekit_watchdog_started intervalSeconds=30"

while [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
  if [ "$(getprop sys.boot_completed)" = "1" ] &&
      user_unlocked &&
      [ -f "$PREFS" ] &&
      ! grep -q 'name="driver_service_enabled" value="false"' "$PREFS"; then
    now="$(uptime_seconds)"
    state="$(/system/bin/timeout -k 2 6 dumpsys activity services "$SERVICE" 2>/dev/null)"
    heartbeat="$(sed -n 's/.*name="runtime_heartbeat_elapsed" value="\([0-9]*\)".*/\1/p' "$PREFS")"
    reason=""
    if ! printf '%s' "$state" | grep -q 'isForeground=true'; then
      reason=service_missing
    elif [ -n "$heartbeat" ] && [ "$heartbeat" -gt 0 ] &&
        [ "$((now * 1000 - heartbeat))" -gt 90000 ] && [ "$((now - last_restart))" -gt 90 ]; then
      pid="$(pidof "$PACKAGE" 2>/dev/null)"
      # Confirm the same stalled process twice. On wake from deep sleep the
      # app needs a scheduling turn before its heartbeat can advance.
      if [ -n "$pid" ] && [ "$pid" = "$stalled_pid" ] && [ "$heartbeat" = "$stalled_heartbeat" ]; then
        reason=heartbeat_stalled
        # Never mark the package force-stopped; only retire its stuck process.
        kill -TERM $pid 2>/dev/null
        stalled_pid=""
      else
        stalled_pid="$pid"
        stalled_heartbeat="$heartbeat"
      fi
    else
      stalled_pid=""
      stalled_heartbeat=""
    fi
    if [ -n "$reason" ] && [ "$((now - last_restart))" -ge 30 ]; then
      last_restart="$now"
      result="$(/system/bin/timeout -k 2 6 am start-foreground-service --user 0 -n "$SERVICE" -a dev.local.mihotspot.action.WATCHDOG 2>&1)"
      log_message "homekit_watchdog_recovery reason=$reason result=$result"
    fi
  fi
  pause_interval
done
log_message "homekit_watchdog_stopped"
