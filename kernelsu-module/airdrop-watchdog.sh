#!/system/bin/sh
# Persistent control service only. This script never changes the radio or
# launches the exclusive AirDrop experiment, and respects the user's switch.
MODDIR="${0%/*}"
PACKAGE=dev.local.mihotspot
SERVICE="$PACKAGE/.airdrop.AirDropResidentService"
PREFS="/data/user/0/$PACKAGE/shared_prefs/airdrop.xml"
LOCK="$MODDIR/.airdrop-watchdog.lock"

if ! mkdir "$LOCK" 2>/dev/null; then
  [ -s "$LOCK/pid" ] || /system/bin/sleep 1
  owner="$(cat "$LOCK/pid" 2>/dev/null)"
  if [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null &&
      tr '\000' ' ' < "/proc/$owner/cmdline" 2>/dev/null | grep -q 'airdrop-watchdog.sh'; then
    exit 0
  fi
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || exit 0
fi
echo "$$" > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT
trap '' HUP
trap 'exit 0' INT TERM
/system/bin/log -t Marionette-KSU 'airdrop_watchdog_started wifiUnchanged=true'
while [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
  if [ "$(getprop sys.boot_completed)" = 1 ] &&
      [ "$(getprop sys.user.0.ce_available)" = true ] &&
      grep -q 'name="resident_enabled" value="true"' "$PREFS" 2>/dev/null; then
    state="$(/system/bin/timeout -k 2 6 dumpsys activity services "$SERVICE" 2>/dev/null)"
    if ! printf '%s' "$state" | grep -q 'isForeground=true'; then
      result="$(/system/bin/timeout -k 2 6 am start-foreground-service --user 0 -n "$SERVICE" -a dev.local.mihotspot.airdrop.RESIDENT_RECOVER 2>&1)"
      /system/bin/log -t Marionette-KSU "airdrop_watchdog_recovery result=$result"
    fi
  fi
  # Measure elapsed time to avoid tight respawns when sleep is interrupted.
  target=$(( $(cut -d. -f1 /proc/uptime) + 30 ))
  while [ "$(cut -d. -f1 /proc/uptime)" -lt "$target" ]; do /system/bin/sleep 5; done
done
