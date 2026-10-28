#!/system/bin/sh
# Load the Marionette patched AWDL WLAN driver from the app's bundled modules,
# replacing the stock kiwi_v2. Idempotent: if the patched driver is already
# loaded it exits 0 immediately. Rolls back to stock on any failure so the
# phone always ends with a working Wi-Fi.
#
# Usage: driver-load.sh <cfg80211.ko> <kiwi_v2.ko>
#
# This is the on-device form of native/airdrop/tools/awdl-stack-load.sh, run by
# AirDropService via su before radio.sh, so the app is self-contained instead of
# depending on a manually insmod'd module.
set -u

CFG=${1:-}
WLAN=${2:-}
STOCK=/vendor_dlkm/lib/modules/qca_cld3_kiwi_v2.ko
STOCK_CFG=/vendor_dlkm/lib/modules/cfg80211.ko
LOCK=/data/local/tmp/marionette-stack.lock
PARAMS="marionette_awdl_mgmt_rx=1 marionette_awdl_roc_rx=1 \
marionette_awdl_sta_monitor=1 marionette_awdl_monitor_raw_rx=1"
CAPTURE_HOLD=/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold
SCAN_RESTORE=

ow() { cut -d' ' -f1 /proc/uptime; }
run_bounded() { /system/bin/timeout -k 2 "$@"; }

wait_link() {
	i=0
	while [ $i -lt 40 ]; do
		if iw dev wlan0 link 2>/dev/null | grep -q '^Connected'; then return 0; fi
		/system/bin/sleep 0.5; i=$((i + 1))
	done
	return 1
}

wait_wifi_disabled() {
	attempt=0; quiet=0; quiet_required=${1:-2}
	while [ "$attempt" -lt 40 ]; do
		if run_bounded 2 cmd wifi status 2>/dev/null | grep -q '^Wifi is disabled' &&
		   run_bounded 3 dumpsys wifi 2>/dev/null | grep -q '^NumActiveModeManagers: 0$'; then
			quiet=$((quiet + 1)); [ "$quiet" -lt "$quiet_required" ] || return 0
		else quiet=0; fi
		sleep 0.25; attempt=$((attempt + 1))
	done
	return 1
}

quiesce_scan_mode() {
	if [ -z "$SCAN_RESTORE" ]; then
		SCAN_RESTORE=$(settings get global wifi_scan_always_enabled)
		case "$SCAN_RESTORE" in 0|1|null) ;; *) return 1 ;; esac
	fi
	if [ "$SCAN_RESTORE" = 1 ]; then
		run_bounded 8 cmd wifi set-scan-always-available disabled || return 1
	fi
}

restore_scan_mode() {
	if [ "$SCAN_RESTORE" = 1 ]; then
		run_bounded 8 cmd wifi set-scan-always-available enabled || \
			echo SCAN_SETTING_RESTORE_FAILED >&2
	fi
}

release_lock() {
	restore_scan_mode
	rmdir "$LOCK" 2>/dev/null
}

restart_wificond() {
	old_pid=$(pidof wificond 2>/dev/null || true)
	setprop ctl.restart wificond || return 1
	attempt=0
	while [ "$attempt" -lt 40 ]; do
		new_pid=$(pidof wificond 2>/dev/null || true)
		if [ -n "$new_pid" ] && [ "$new_pid" != "$old_pid" ] &&
		   [ "$(getprop init.svc.wificond)" = running ]; then return 0; fi
		sleep 0.1; attempt=$((attempt + 1))
	done
	echo WIFICOND_RESTART_TIMEOUT >&2; return 1
}

enable_after_reload() {
	restart_wificond || return 1
	run_bounded 8 svc wifi disable >/dev/null 2>&1 || return 1
	wait_wifi_disabled 12 || return 1
	run_bounded 8 svc wifi enable >/dev/null 2>&1 || return 1
}

reconcile_link() {
	wait_link && return 0
	run_bounded 8 svc wifi disable >/dev/null 2>&1 || return 1
	wait_wifi_disabled 12 || return 1
	run_bounded 8 svc wifi enable >/dev/null 2>&1 || return 1
	wait_link
}

unload() {
	quiesce_scan_mode || return 1
	run_bounded 8 svc wifi disable >/dev/null 2>&1 || return 1
	wait_wifi_disabled || { echo WIFI_DISABLE_TIMEOUT >&2; return 1; }
	for m in kiwi_v2 mac80211 cfg80211; do
		[ -d "/sys/module/$m" ] || continue
		run_bounded 12 rmmod "$m" || return 1
		[ ! -d "/sys/module/$m" ] || { echo "MODULE_STILL_LOADED $m" >&2; return 1; }
	done
}

restore_stock() {
	unload || return 1
	run_bounded 12 insmod "$STOCK_CFG" || return 1
	run_bounded 20 insmod "$STOCK" || return 1
	enable_after_reload || return 1
	reconcile_link || { echo STOCK_LOADED_NO_LINK >&2; return 1; }
	echo STOCK_LOADED_LINKED
}

# --- capability fast-path: already patched? -------------------------------
[ "$(id -u)" = 0 ] || { echo "need root"; exit 2; }
[ -f "$WLAN" ] || { echo "missing wlan module: $WLAN"; exit 2; }
[ -f "$CFG" ] || { echo "missing cfg80211 module: $CFG"; exit 2; }
if [ -f "$CAPTURE_HOLD" ]; then
	echo "patched driver already loaded; nothing to do"
	exit 0
fi

# --- load patched driver ---------------------------------------------------
mkdir "$LOCK" 2>/dev/null || { echo "another stack operation is running"; exit 9; }
trap 'release_lock' EXIT

if ! unload; then
	echo "UNLOAD_FAILED rolling back to stock"
	restore_stock || echo STOCK_RESTORE_FAILED >&2
	exit 1
fi
if ! run_bounded 12 insmod "$CFG"; then
	echo "CFG80211_LOAD_FAILED rolling back to stock"
	restore_stock || echo STOCK_RESTORE_FAILED >&2; exit 1
fi
# shellcheck disable=SC2086
if ! run_bounded 20 insmod "$WLAN" $PARAMS; then
	echo "WLAN_LOAD_FAILED rolling back to stock"
	run_bounded 12 rmmod kiwi_v2 2>/dev/null
	restore_stock || echo STOCK_RESTORE_FAILED >&2; exit 1
fi
if [ ! -f "$CAPTURE_HOLD" ]; then
	echo "PATCHED_MODULE_MISSING_CAPABILITY rolling back to stock"
	restore_stock || echo STOCK_RESTORE_FAILED >&2; exit 1
fi
if ! enable_after_reload; then
	echo "FRAMEWORK_RESTART_FAILED rolling back to stock"
	restore_stock || echo STOCK_RESTORE_FAILED >&2; exit 1
fi
CFG_GATE=/sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx
[ -f "$CFG_GATE" ] && echo Y > "$CFG_GATE" 2>/dev/null
DATA_GATE=/sys/module/kiwi_v2/parameters/marionette_awdl_data_tx
[ -f "$DATA_GATE" ] && echo Y > "$DATA_GATE" 2>/dev/null
if reconcile_link; then
	echo "PATCHED_STACK_LOADED link=$(iw dev wlan0 link | sed -n 's/^Connected to \([^ ]*\).*/\1/p')"
	exit 0
else
	echo "PATCHED_STACK_NO_LINK rolling back to stock"
	restore_stock || echo STOCK_RESTORE_FAILED >&2; exit 1
fi
