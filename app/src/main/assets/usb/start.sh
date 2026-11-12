#!/system/bin/sh
# Marionette USB peripheral composition.
# __TOKEN__, __ENABLE_CAMERA__, __ENABLE_AUDIO__ are replaced by UsbGadgetController.
G=/config/usb_gadget/g1
H=$G/functions/hid.marionette
F=$G/functions/uvc.marionette
A=$G/functions/uac2.marionette

killall -TERM marionette_usbd 2>/dev/null
sleep 0.2
stop vendor.usbgadget-hal-1-1 2>/dev/null
echo "" > $G/UDC
sleep 0.4
echo high-speed > $G/max_speed 2>/dev/null

cd $G/configs/b.1 || exit 10
rm -f f1 f2 f3 f4

# ---- HID: keyboard + relative mouse + consumer ----
mkdir $H 2>/dev/null
cat /data/local/tmp/marionette_hid.bin > $H/report_desc 2>/dev/null
# Wire reports include the report ID (keyboard 1+8 = 9 bytes). f_hid sizes the
# interrupt-IN request from this value; 8 truncated every keyboard report and
# left the host waiting for the missing byte, which wedged the endpoint.
echo 9 > $H/report_length 2>/dev/null
echo 0 > $H/protocol 2>/dev/null
echo 0 > $H/subclass 2>/dev/null
HIDDEV=$(cat $H/dev 2>/dev/null)
HIDMINOR=${HIDDEV#*:}
HIDNODE=/dev/hidg$HIDMINOR

# ---- UVC: 640x480 YUYV@30 ----
if [ "__ENABLE_CAMERA__" = "1" ]; then
  mkdir $F 2>/dev/null
  mkdir -p $F/control/header/h
  [ -L $F/control/class/fs/h ] || ( cd $F/control/class/fs && ln -s ../../header/h h )
  [ -L $F/control/class/ss/h ] || ( cd $F/control/class/ss && ln -s ../../header/h h )
  mkdir -p $F/streaming/uncompressed/u/640x480
  D=$F/streaming/uncompressed/u/640x480
  echo 640 > $D/wWidth 2>/dev/null
  echo 480 > $D/wHeight 2>/dev/null
  echo 614400 > $D/dwMaxVideoFrameBufferSize 2>/dev/null
  echo 147456000 > $D/dwMinBitRate 2>/dev/null
  echo 147456000 > $D/dwMaxBitRate 2>/dev/null
  echo 333333 > $D/dwDefaultFrameInterval 2>/dev/null
  echo 333333 > $D/dwFrameInterval 2>/dev/null
  mkdir -p $F/streaming/header/h
  [ -L $F/streaming/header/h/u ] || ( cd $F/streaming/header/h && ln -s ../../uncompressed/u u )
  [ -L $F/streaming/class/fs/h ] || ( cd $F/streaming/class/fs && ln -s ../../header/h h )
  [ -L $F/streaming/class/hs/h ] || ( cd $F/streaming/class/hs && ln -s ../../header/h h )
  [ -L $F/streaming/class/ss/h ] || ( cd $F/streaming/class/ss && ln -s ../../header/h h )
  echo 3072 > $F/streaming_maxpacket 2>/dev/null
  echo 1 > $F/streaming_interval 2>/dev/null
  echo 0 > $F/streaming_maxburst 2>/dev/null
fi

# ---- UAC2: 48 kHz stereo playback, mono capture ----
# Use our own instance: the vendor's uac2.0 appears late (or not at all) after
# boot, so writes to it landed on a missing directory, were swallowed by
# 2>/dev/null, and f4 ended up linked to a function we never configured.
if [ "__ENABLE_AUDIO__" = "1" ]; then
  mkdir $A 2>/dev/null
  # f_uac2 exposes the ALSA view inverted (verified with tinypcminfo and the
  # host's own device list): p_chmask sizes ALSA playback, which is what the
  # HOST RECORDS, while c_chmask sizes ALSA capture, which is what the HOST
  # PLAYS. So: mono phone mic -> p_chmask=1, stereo speaker -> c_chmask=3.
  echo 1 > $A/p_chmask
  echo 48000 > $A/p_srate
  echo 2 > $A/p_ssize
  echo 3 > $A/c_chmask
  echo 48000 > $A/c_srate
  echo 2 > $A/c_ssize
  echo 2 > $A/req_number 2>/dev/null
fi

# ---- links (adb stays as a development safety net) ----
cd $G/configs/b.1 || exit 11
ln -s ../../functions/hid.marionette f2 2>/dev/null
# ffs.adb may already be linked by the vendor configuration (functionN).
# Linking the same function instance twice makes the whole configuration fail
# to bind, so add our own link only when none exists.
have_adb=0
for l in function*; do
  [ -L "$l" ] || continue
  case "$(readlink "$l")" in *ffs.adb) have_adb=1 ;; esac
done
[ "$have_adb" = 1 ] || ln -s ../../functions/ffs.adb f1 2>/dev/null
[ "__ENABLE_CAMERA__" = "1" ] && ln -s ../../functions/uvc.marionette f3 2>/dev/null
[ "__ENABLE_AUDIO__" = "1" ] && ln -s ../../functions/uac2.marionette f4 2>/dev/null

# ---- daemon before bind: it polls for the nodes and subscribes to UVC events ----
killall -TERM marionette_usbd 2>/dev/null
rm -f /data/local/tmp/marionette_usbd.pid
nohup /data/local/tmp/marionette_usbd \
  --token __TOKEN__ \
  --hid-node "$HIDNODE" \
  --video-node auto \
  --hid-port 47831 --video-port 47832 --mic-port 47833 --spk-port 47834 \
  > /data/local/tmp/marionette_usbd.log 2>&1 &
echo $! > /data/local/tmp/marionette_usbd.pid
sleep 0.3

echo a600000.dwc3 > $G/UDC
echo "BIND=$?"
sleep 1.5
echo "STATE=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null)"
echo "HID_NODE=$HIDNODE"
for v in /sys/class/video4linux/video*; do
  [ "$(cat $v/name 2>/dev/null)" = "dwc3-gadget" ] && echo "VIDEO_NODE=/dev/$(basename $v)"
done
grep -q UAC2Gadget /proc/asound/cards 2>/dev/null && echo "CARD=UAC2Gadget"
echo "DAEMON=$(cat /data/local/tmp/marionette_usbd.pid 2>/dev/null)"
