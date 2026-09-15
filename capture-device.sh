#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ADB_SERIAL:-adb-510af65c-ycG5h8._adb-tls-connect._tcp}"
ADB=(adb -s "$SERIAL")
REMOTE=/data/local/tmp/passive-capture
LOCAL="${1:-captures}"

mkdir -p "$LOCAL"
"${ADB[@]}" shell "su -c 'mkdir -p $REMOTE; chmod 700 $REMOTE'"

echo "Starting passive capture on wlan0/rmnet_ipa0 (Ctrl-C to stop)..."
echo "Files will be saved under $LOCAL/"
"${ADB[@]}" exec-out shell "su -c '/system/bin/tcpdump -i any -s 0 -U -w - not port 8022'" > "$LOCAL/android-$(date +%Y%m%d-%H%M%S).pcap"
