#!/bin/sh
# Switch stress probe: hammers front/back switches while recording host luma,
# then reports per-switch time-to-first-frame and any capture failures.
# Usage: sh build-tools/usb/switch_stress.sh [avfoundation-device-index] [switches]
set -e
W=${ADB_SERIAL:-adb-510af65c-ycG5h8._adb-tls-connect._tcp}
DEV=${1:-0}
N=${2:-10}
SVC="dev.local.mihotspot/dev.local.mihotspot.peripheral.usb.UsbPeripheralService"

pkill -f 'ffmpeg .*avfoundation' 2>/dev/null || true
adb -s "$W" logcat -c
adb -s "$W" logcat -v time -s UsbVideoSource:I Probe:I >/tmp/ulog.txt 2>&1 &
LOGPID=$!
sleep 1

ffmpeg -loglevel error -hide_banner -f avfoundation -pixel_format uyvy422 -framerate 30 -i "$DEV" \
  -t $((N * 3 + 4)) \
  -vf "fps=4,signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=/tmp/lumameta.txt" \
  -f null - >/tmp/fout.txt 2>/tmp/luma.txt &
FFPID=$!

sleep 2
i=1
src=BACK
while [ "$i" -le "$N" ]; do
  adb -s "$W" shell log -t Probe "MARKER $i $src"
  adb -s "$W" shell am start-foreground-service -n "$SVC" -a dev.local.mihotspot.usb.SWITCH_CAMERA \
    --es camera_source "$src" >/dev/null
  if [ "$src" = BACK ]; then src=FRONT; else src=BACK; fi
  i=$((i + 1))
  sleep 3
done

wait $FFPID || true
sleep 1
kill $LOGPID 2>/dev/null || true

{
  echo "== device"
  grep -E 'USB_CAMERA_|MARKER' /tmp/ulog.txt
  echo "== host luma, one value per 0.25s"
  awk -F'=' '/YAVG/{printf "%d ", $2}' /tmp/lumameta.txt
  echo
} >/tmp/stress_report.txt 2>&1
cat /tmp/stress_report.txt
