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
WLAN=${3:-/data/local/tmp/marionette-candidate-0046.ko}
STOCK=/vendor_dlkm/lib/modules/qca_cld3_kiwi_v2.ko
STOCK_CFG=/vendor_dlkm/lib/modules/cfg80211.ko
LOCK=/data/local/tmp/marionette-stack.lock
# The gates the AWDL RX paths depend on.  raw_tx stays off: nothing in this
# path uses the monitor-vdev TX contract.
#
# marionette_awdl_sta_monitor (patch 0009) is deliberately NOT enabled.  With
# it on, wlan_mlme_is_sta_mon_conc_supported() returns true unconditionally, so
# policy_mgr restricts the STA's channel set to the monitor vdev's channel and
# every later STA connect to a *different* channel is refused in the host
# pre-checks:
#     Connect with <bssid> ... is FAILURE cm_reason 0 status_code 0
# arriving 2-20 ms after "Trying to associate", i.e. before any over-the-air
# exchange.  Stock performs the identical association in ~260 ms.  Bisected on
# device 2026-10-22 with assoc-gate-bisect.sh: roc_rx=1 and monitor_raw_rx=1
# alone are both fine, sta_monitor=1 alone reproduces it, all-off is fine.
# The channel it breaks is 5220 MHz (channel 44) - the only AWDL social channel
# this AP offers - so the gate had to go for the 0046 probe to be reproducible.
# Patch 0009 is an [EXPERIMENTAL] attempt to keep the STA associated while a
# monitor vdev ran; patch 0046 (resident firmware promiscuous RX) makes the
# monitor vdev unnecessary, so the experiment is obsolete and should be
# superseded in the driver stack the way 0045 was.
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

reconcile_link() {
	wait_link && return 0
	# Reconcile framework DisabledState with wifi_on=1 after module replacement.
	svc wifi disable >/dev/null 2>&1
	sleep 2
	setprop ctl.restart wificond
	svc wifi enable >/dev/null 2>&1
	wait_link
}

unload() {
	/system/bin/timeout -k 2 8 svc wifi disable >/dev/null 2>&1
	/system/bin/sleep 3
	for m in kiwi_v2 mac80211 cfg80211; do
		[ -d "/sys/module/$m" ] || continue
		/system/bin/timeout -k 2 12 rmmod "$m" 2>/dev/null
	done
}

load_common() {
	/system/bin/timeout -k 2 12 insmod "$1" || return 1
	shift
	# shellcheck disable=SC2086
	/system/bin/timeout -k 2 20 insmod "$1" $PARAMS || return 1
	return 0
}

case "${1:-status}" in
load)
	[ "$(id -u)" = 0 ] || { echo "need root"; exit 2; }
	[ -f "$CFG" ] && [ -f "$WLAN" ] || { echo "missing module"; exit 2; }
	mkdir "$LOCK" 2>/dev/null || { echo "another load is running"; exit 9; }
	trap 'rmdir "$LOCK" 2>/dev/null' EXIT
	unload
	if ! load_common "$CFG" "$WLAN"; then
		echo "LOAD_FAILED rolling back to stock"
		rmdir "$LOCK" 2>/dev/null
		trap - EXIT
		/system/bin/timeout -k 2 12 insmod "$STOCK_CFG" 2>/dev/null
		/system/bin/timeout -k 2 20 insmod "$STOCK" 2>/dev/null
		setprop ctl.restart wificond
		svc wifi enable >/dev/null 2>&1
		exit 1
	fi
	# cfg80211 was reloaded, so wificond's netlink sockets point at a dead
	# family.  Recreate them before asking for Wi-Fi back.
	setprop ctl.restart wificond
	svc wifi enable >/dev/null 2>&1
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
		echo "STACK_LOADED_NO_LINK"
		exit 1
	fi
	;;
stock)
	[ "$(id -u)" = 0 ] || { echo "need root"; exit 2; }
	unload
	/system/bin/timeout -k 2 12 insmod "$STOCK_CFG" || exit 1
	/system/bin/timeout -k 2 20 insmod "$STOCK" || exit 1
	setprop ctl.restart wificond
	svc wifi enable >/dev/null 2>&1
	reconcile_link || { echo "STOCK_LOADED_NO_LINK"; exit 1; }
	echo "STOCK_LOADED_LINKED"
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
	echo "usage: $0 load|stock|status [candidate.ko] [cfg80211.ko]"
	exit 2
	;;
esac
