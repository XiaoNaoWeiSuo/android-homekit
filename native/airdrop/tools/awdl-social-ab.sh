#!/system/bin/sh
# A/B the firmware RX filter with targeted scans on the AWDL social channels.
#
# A plain scan sweeps the whole regulatory channel list, so the dwell time on
# the three AWDL social channels (6 / 44 / 149) is a few percent of the sweep
# and an AWDL availability window is easily missed.  Scanning only those
# frequencies concentrates the dwell time where AWDL action frames actually
# live, which makes marionette_awdl_rx_wmi a usable signal.
#
# The station never moves: the scan is a normal scan and the link survives.
#
# Usage: awdl-social-ab.sh [scans_per_arm] [freqs...]
set -u

N=${1:-6}
shift 2>/dev/null
FREQS=${*:-"2437 5220 5745"}
P=/sys/module/kiwi_v2/parameters
GATE=$P/marionette_awdl_fw_promisc
AWDL=$P/marionette_awdl_rx_wmi
MGMT=$P/marionette_mgmt_rx_wmi
LOG=/data/local/tmp/awdl-social-ab.log

: >"$LOG"
say() { echo "$*"; echo "$*" >>"$LOG"; }

[ -f "$GATE" ] || { echo "FAIL: $GATE missing (patch 0046 not loaded)"; exit 2; }
[ "$(cat $P/marionette_awdl_mgmt_rx)" = "Y" ] ||
	{ echo "FAIL: marionette_awdl_mgmt_rx is not Y (patch 0006 gate)"; exit 2; }
iw dev wlan0 link 2>/dev/null | grep -q '^Connected' ||
	{ echo "FAIL: wlan0 not associated"; exit 3; }

say "station=$(iw dev wlan0 link 2>/dev/null | sed -n 's/^Connected to \([^ ]*\).*/\1/p')"
say "scan_freqs=$FREQS scans_per_arm=$N"

arm() {
	label=$1
	i=0
	while [ $i -lt "$N" ]; do
		iw dev wlan0 scan freq $FREQS >/dev/null 2>&1
		i=$((i + 1))
		say "  $label scan$i awdl_rx_wmi=$(cat "$AWDL") mgmt_rx_wmi=$(cat "$MGMT")"
	done
}

echo 0 >"$GATE" 2>/dev/null
say "=== arm A: firmware filter OFF ==="
arm A

echo 1 >"$GATE" 2>/dev/null
say "gate=$(cat "$GATE")"
say "=== arm B: firmware filter ON ==="
arm B

echo 0 >"$GATE" 2>/dev/null
say "restored gate=$(cat "$GATE")"

say "=== verdict ==="
say "If arm B moved awdl_rx_wmi and arm A did not, the firmware obeys"
say "WMI_PDEV_SET_RX_FILTER_PROMISCUOUS for AWDL action frames."
say "If neither arm moved it, no AWDL frame was heard on $FREQS at all:"
say "re-run with a known-active Apple peer before concluding."
