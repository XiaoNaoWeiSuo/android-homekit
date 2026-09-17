#!/system/bin/sh
MODDIR="${0%/*}"
exec "$MODDIR/start.sh" boot-completed
