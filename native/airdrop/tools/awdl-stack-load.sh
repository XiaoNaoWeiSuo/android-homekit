#!/system/bin/sh
# Load the marionette AWDL driver stack on fuxi, or roll back to stock.
#
# Every device reboot silently restores the stock kiwi_v2, which has none of
# the marionette_* parameters, so every measurement run has to start by
# swapping the modules back in.  This is that step, made repeatable.
#
# Usage: awdl-stack-load.sh load [cfg80211.ko] [wlan-candidate.ko]
#        awdl-stack-load.sh stock
#        awdl-stack-load.sh status
#
# NOTE: $2 is the CFG80211 module and $3 is the WLAN candidate, in that order.
# The earlier usage line listed them the other way round, which silently
# insmods the WLAN driver as cfg80211 and fails with ENOENT.
#
# `load` disables Wi-Fi first: the WLAN driver cannot be removed while the
# station is up, and cfg80211's dynamically allocated generic-netlink family
# identity changes on reload, so wificond's cached sockets have to be recreated
# before Wi-Fi is requested again.
set -u

CFG=${2:-/data/local/tmp/marionette-cfg80211-0043.ko}
WLAN=${3:-}
STOCK=/vendor_dlkm/lib/modules/qca_cld3_kiwi_v2.ko
STOCK_CFG=/vendor_dlkm/lib/modules/cfg80211.ko
LOCK=/data/local/tmp/marionette-stack.lock
SCAN_RESTORE=
# The caller selects a verified candidate explicitly. STA-monitor concurrency
# remains opt-in; the current capture implementation needs it but it can limit
# association channels. Never silently load an obsolete default candidate.
PARAMS="marionette_awdl_mgmt_rx=1 marionette_awdl_roc_rx=1 \
marionette_awdl_monitor_raw_rx=1 \
${MARIONETTE_EXTRA_PARAMS:-}"

wait_link() {
	i=0
	while [ $i -lt 40 ]; do
		if iw dev wlan0 link 2>/dev/null | grep -q '^Connected'; then
			return 0
		fi
		/system/bin/sleep 0.5
		i=$((i + 1))
	done
	return 1
}

run_bounded() {
	/system/bin/timeout -k 2 "$@"
}

wait_wifi_disabled() {
	attempt=0
	quiet=0
	quiet_required=${1:-2}
	while [ "$attempt" -lt 40 ]; do
		# A disabled UI switch can coexist with an active scan-only manager.
		# Do not reload cfg80211 until the framework owns no client interface.
		if run_bounded 2 cmd wifi status 2>/dev/null | grep -q '^Wifi is disabled' &&
		   run_bounded 3 dumpsys wifi 2>/dev/null | grep -q '^NumActiveModeManagers: 0$'; then
			quiet=$((quiet + 1))
			[ "$quiet" -lt "$quiet_required" ] || return 0
		else
			quiet=0
		fi
		sleep 0.25
		attempt=$((attempt + 1))
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

release_stack_lock() {
	# Restore the user's setting on both success and failure, before unlock.
	if [ "$SCAN_RESTORE" = 1 ]; then
		run_bounded 8 cmd wifi set-scan-always-available enabled ||
			echo SCAN_SETTING_RESTORE_FAILED >&2
	fi
	rmdir "$LOCK" 2>/dev/null
}

restart_wificond() {
	old_pid=$(pidof wificond 2>/dev/null || true)
	setprop ctl.restart wificond || return 1
	attempt=0
	while [ "$attempt" -lt 40 ]; do
		new_pid=$(pidof wificond 2>/dev/null || true)
		if [ -n "$new_pid" ] && [ "$new_pid" != "$old_pid" ] &&
		   [ "$(getprop init.svc.wificond)" = running ]; then
			return 0
		fi
		sleep 0.1
		attempt=$((attempt + 1))
	done
	echo WIFICOND_RESTART_TIMEOUT >&2
	return 1
}

enable_after_reload() {
	# ctl.restart is asynchronous. Do not let WifiService open sockets on
	# the old wificond process/netlink family and then lose them on restart.
	restart_wificond || return 1
	# Binder death is processed asynchronously even after the new daemon runs.
	# This device schedules recovery ~2 s later, or disables Wi-Fi when its
	# recovery budget is exhausted. Drain that transition while still off.
	run_bounded 8 svc wifi disable >/dev/null 2>&1 || return 1
	wait_wifi_disabled 12 || return 1
	run_bounded 8 svc wifi enable >/dev/null 2>&1 || return 1
}

reconcile_link() {
	wait_link && return 0
	# One bounded reconciliation, with observed framework transitions.
	run_bounded 8 svc wifi disable >/dev/null 2>&1 || return 1
	wait_wifi_disabled 12 || return 1
	# The module/family and daemon have not changed. Restarting wificond here
	# only creates another death notification and consumes recovery budget.
	run_bounded 8 svc wifi enable >/dev/null 2>&1 || return 1
	wait_link
}

acquire_stack_lock() {
	mkdir "$LOCK" 2>/dev/null || { echo "another stack operation is running"; return 9; }
	trap 'release_stack_lock' EXIT
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

load_common() {
	run_bounded 12 insmod "$1" || return 1
	shift
	# shellcheck disable=SC2086
	run_bounded 20 insmod "$1" $PARAMS || return 1
}

restore_stock() {
	# Also handles a partial candidate load; never insmod over a live stack.
	unload || return 1
	run_bounded 12 insmod "$STOCK_CFG" || return 1
	run_bounded 20 insmod "$STOCK" || return 1
	enable_after_reload || return 1
	reconcile_link || { echo "STOCK_LOADED_NO_LINK"; return 1; }
	echo "STOCK_LOADED_LINKED"
}

case "${1:-status}" in
load)
	[ "$(id -u)" = 0 ] || { echo "need root"; exit 2; }
	[ -f "$CFG" ] && [ -f "$WLAN" ] || { echo "missing module"; exit 2; }
	acquire_stack_lock || exit $?
	unload || { echo "UNLOAD_FAILED"; exit 1; }
	if ! load_common "$CFG" "$WLAN"; then
		echo "LOAD_FAILED rolling back to stock"
		restore_stock || echo "STOCK_RESTORE_FAILED" >&2
		exit 1
	fi
	if ! enable_after_reload; then
		echo "FRAMEWORK_RESTART_FAILED rolling back to stock"
		restore_stock || echo "STOCK_RESTORE_FAILED" >&2
		exit 1
	fi
	# The AWDL submission gate lives in cfg80211, not in the WLAN driver, and
	# defaults to off.  Without it cfg80211_mlme_mgmt_tx falls through to the
	# strict check - "if (!wdev->connected) return -ENOTCONN" - and every
	# AWDL action frame is rejected with -107, which looks exactly like a dead
	# link and is not one.
	CFG_GATE=/sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx
	if [ -f "$CFG_GATE" ]; then
		echo Y > "$CFG_GATE"
		echo "cfg80211_gate=$(cat "$CFG_GATE")"
	else
		echo "cfg80211_gate=MISSING" >&2
	fi
	# AWDL data frames are a separate gate inside the WLAN driver (0043/0044).
	# Action frames submit without it; the AirDrop payload does not.
	DATA_GATE=/sys/module/kiwi_v2/parameters/marionette_awdl_data_tx
	if [ -f "$DATA_GATE" ]; then
		echo Y > "$DATA_GATE"
		echo "awdl_data_tx=$(cat "$DATA_GATE")"
	fi
	if reconcile_link; then
		echo "STACK_LOADED link=$(iw dev wlan0 link | sed -n 's/^Connected to \([^ ]*\).*/\1/p')"
	else
		echo "STACK_LOADED_NO_LINK rolling back to stock"
		restore_stock || echo "STOCK_RESTORE_FAILED" >&2
		exit 1
	fi
	;;
stock)
	[ "$(id -u)" = 0 ] || { echo "need root"; exit 2; }
	acquire_stack_lock || exit $?
	restore_stock || exit 1
	;;
status)
	echo "modules: $(ls /sys/module | grep -E '^(kiwi_v2|cfg80211|mac80211)$' | tr '\n' ' ')"
	if [ -d /sys/module/kiwi_v2 ]; then
		echo "marionette: $(ls /sys/module/kiwi_v2/parameters | grep -c marionette) params"
		for p in marionette_awdl_mgmt_rx marionette_awdl_roc_rx \
			 marionette_awdl_sta_monitor marionette_awdl_monitor_raw_rx \
			 marionette_awdl_capture_hold; do
			[ -f "/sys/module/kiwi_v2/parameters/$p" ] &&
				echo "  $p=$(cat /sys/module/kiwi_v2/parameters/$p)"
		done
	fi
	echo "wlan0=$(cat /sys/class/net/wlan0/address 2>/dev/null)"
	echo "cfg80211_gate=$(cat /sys/module/cfg80211/parameters/marionette_awdl_mgmt_tx 2>/dev/null)"
	iw dev 2>/dev/null | sed -n 's/^\s*Interface /iface /p'
	iw dev wlan0 link 2>/dev/null | head -3
	;;
*)
	echo "usage: $0 load|stock|status [cfg80211.ko] [candidate.ko]"
	exit 2
	;;
esac
