#!/bin/sh
# Switch-source probe. Records host luma continuously while the camera source
# is switched, so the freeze window shows up as a timeline on both sides.
# Usage: sh build-tools/usb/switch_probe.sh [avfoundation-device-index]
# Results: /tmp/switch_report.txt
set -e
W=${ADB_SERIAL:-adb-510af65c-ycG5h8._adb-tls-connect._tcp}
DEV=${1:-0}
SVC="dev.local.mihotspot/dev.local.mihotspot.peripheral.usb.UsbPeripheralService"

sw() {
  adb -s "$W" shell log -t Probe "MARKER switch $1 at $(date +%H:%M:%S)"
  adb -s "$W" shell am start-foreground-service -n "$SVC" -a dev.local.mihotspot.usb.SWITCH_CAMERA \
    --es camera_source "$1" >/dev/null
}

pkill -f 'ffmpeg .*avfoundation' 2>/dev/null || true
adb -s "$W" logcat -c
adb -s "$W" logcat -v time -s UsbPeripheralService:I UsbVideoSource:I Probe:I >/tmp/ulog.txt 2>&1 &
LOGPID=$!
sleep 1

ffmpeg -loglevel error -hide_banner -f avfoundation -pixel_format uyvy422 -framerate 30 -i "$DEV" -t 22 \
  -vf "fps=4,signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=/tmp/lumameta.txt" \
  -f null - >/tmp/fout.txt 2>/tmp/luma.txt &
FFPID=$!

sleep 4
sw BACK
sleep 9
sw FRONT
wait $FFPID || true
sleep 1
kill $LOGPID 2>/dev/null || true

{
  echo "== device"
  grep -E 'USB_CAMERA_|MARKER|USB_VIDEO_STATS' /tmp/ulog.txt | tail -40
  echo "== host luma (1 per second)"
  awk -F'=' '/pts_time/{t=$2} /YAVG/{printf "%.1fs luma=%.0f\n", t, $2}' /tmp/lumameta.txt | awk 'NR%4==1'
} >/tmp/switch_report.txt 2>&1
cat /tmp/switch_report.txt
