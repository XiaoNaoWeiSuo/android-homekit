#!/system/bin/sh
MODDIR="${MODPATH:-${0%/*}}"
chmod 0755 "$MODDIR/service.sh" "$MODDIR/boot-completed.sh" "$MODDIR/start.sh" 2>/dev/null || true
