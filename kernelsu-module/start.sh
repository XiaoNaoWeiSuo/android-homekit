#!/system/bin/sh
PACKAGE="dev.local.mihotspot"
SERVICE="${PACKAGE}/.HomeKitService"
TAG="Marionette-KSU"
log_message() { /system/bin/log -t "$TAG" "$*" 2>/dev/null || true; }
wait_for_boot() { attempt=0; while [ "$attempt" -lt 120 ]; do [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && return 0; attempt=$((attempt + 1)); sleep 2; done; return 1; }
wait_for_user_unlock() { attempt=0; while [ "$attempt" -lt 120 ]; do ce_available="$(getprop sys.user.0.ce_available 2>/dev/null)"; [ "$ce_available" = "true" ] && return 0; if [ -z "$ce_available" ] && [ "$(cmd user is-user-unlocked 0 2>/dev/null | tr -d '\r')" = "true" ]; then return 0; fi; attempt=$((attempt + 1)); sleep 2; done; return 1; }
wait_for_package() { attempt=0; while [ "$attempt" -lt 90 ]; do pm path "$PACKAGE" 2>/dev/null | grep -q '^package:' && return 0; attempt=$((attempt + 1)); sleep 2; done; return 1; }
grant_runtime_permissions() { for permission in android.permission.BLUETOOTH_ADVERTISE android.permission.BLUETOOTH_CONNECT android.permission.NEARBY_WIFI_DEVICES android.permission.POST_NOTIFICATIONS android.permission.ACCESS_FINE_LOCATION; do pm grant "$PACKAGE" "$permission" >/dev/null 2>&1 || true; done; }
log_message "start_hook=$1 boot=$(getprop sys.boot_completed) ce=$(getprop sys.user.0.ce_available)"
if ! wait_for_boot; then log_message "boot_not_complete_timeout"; exit 0; fi
if ! wait_for_user_unlock; then log_message "user_unlock_timeout"; exit 0; fi
if ! wait_for_package; then log_message "package_not_found package=$PACKAGE"; exit 0; fi
grant_runtime_permissions
for attempt in 1 2 3; do result="$(am start-foreground-service --user 0 -n "$SERVICE" 2>&1)"; status=$?; if [ "$status" -eq 0 ]; then log_message "service_start_requested attempt=$attempt result=$result"; exit 0; fi; log_message "service_start_retry attempt=$attempt status=$status result=$result"; sleep 5; done
log_message "service_start_failed package=$PACKAGE"
exit 0
