#!/system/bin/sh
# Marionette USB peripheral composition.
# __TOKEN__, __ENABLE_CAMERA__, __ENABLE_AUDIO__ are replaced by UsbGadgetController.
G=/config/usb_gadget/g1
H=$G/functions/hid.marionette
F=$G/functions/uvc.marionette
A=$G/functions/uac2.marionette

# ---- provisioning policy ----
# Binding while the cable is OUT does not attach on this vendor kernel: the PHY
# wakes on VBUS ("DWC3 exited from low power mode") but the gadget never
# connects, so the host sees nothing. The only path that has ever enumerated is
# taking the gadget over while the host is attached, so do that. Stops stay
# deferred until the cable is out (see stop.sh), which is where the kernel
# panics actually came from.
CUR=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null)
echo "PREV_STATE=$CUR"

killall -TERM marionette_usbd 2>/dev/null
# Wait for the daemon to really exit: its fd release is what deactivates the
# UVC function, and that must finish before configfs is touched.
i=0
while [ -n "$(pidof marionette_usbd)" ] && [ $i -lt 20 ]; do
  sleep 0.1
  i=$((i + 1))
done
killall -KILL marionette_usbd 2>/dev/null
sleep 0.3
stop vendor.usbgadget-hal-1-1 2>/dev/null
echo "" > $G/UDC 2>/dev/null
# configfs rejects new links while a function is still bound (EBUSY). Wait for
# the composite to actually let go of the UDC before touching descriptors;
# otherwise every ln below fails silently and the host keeps the old format.
i=0
while [ -n "$(cat $G/UDC 2>/dev/null)" ] && [ $i -lt 30 ]; do
  sleep 0.1
  i=$((i + 1))
  echo "" > $G/UDC 2>/dev/null
done
sleep 0.2
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

# ---- UVC: MJPEG frame ladder @30 ----
# USB 2.0 (this port enumerates as high-speed through the adapter) tops out at
# ~24.6 MB/s for one isoc endpoint, so the payload must stay compressed; MJPEG is
# decoded by macOS and Windows with no extra driver. Several frame sizes are
# advertised in one format so the host can pick a rung and the user can find
# where the link or the encoder gives up.
if [ "__ENABLE_CAMERA__" = "1" ]; then
  # Re-provisioning f_uvc is the most dangerous operation on this kernel: the
  # function's deactivate path goes through percpu_ref + RCU, and tearing it
  # down repeatedly has produced three different panics. Rebuild only when the
  # descriptor set below actually changed; a plain off/on cycle reuses it.
  UVC_VER=4
  if [ -L $F/streaming/header/h/m ] && \
     [ "$(cat /data/local/tmp/uvc_desc.ver 2>/dev/null)" = "$UVC_VER" ] && \
     [ -s /data/local/tmp/uvc_modes.txt ]; then
    echo "UVC=reused"
  else
  # f_uvc freezes its streaming descriptors on first bind: after that, configfs
  # answers EBUSY to any new format link even once the UDC is cleared, so a
  # gadget provisioned by an older script can never gain MJPEG in place. Drop
  # the whole function and build it again from scratch every time.
  # The order matters: frame attributes are only writable while the format is
  # not yet linked into streaming/header/h. If the links from a previous run
  # survive, rmdir leaves them behind, mkdir re-creates the frames with kernel
  # defaults (640x360) and every wWidth write below is rejected with EBUSY.
  # So: unlink first, then remove directories deepest-first.
  # (rm -rf fails on configfs anyway: it unlinks attributes first and gets EPERM.)
  find $F -type l -delete 2>/dev/null
  for d in $(find $F -mindepth 1 -depth -type d 2>/dev/null); do rmdir "$d" 2>/dev/null; done
  rmdir $F 2>/dev/null
  if [ -d $F/streaming ]; then
    echo "UVC_REBUILD_FAILED stale tree under $F" > /data/local/tmp/uvc_link.log
  fi
  mkdir $F 2>/dev/null
  mkdir -p $F/control/header/h
  [ -L $F/control/class/fs/h ] || ( cd $F/control/class/fs && ln -s ../../header/h h )
  [ -L $F/control/class/ss/h ] || ( cd $F/control/class/ss && ln -s ../../header/h h )
  # Frame ladder. The kernel numbers frames in creation order (bFrameIndex
  # 1,2,3...), so the first entry here is also the host's default. Portrait
  # rungs come first: the phone is normally held upright, and a landscape canvas
  # of a portrait scene is what made the camera picture look sideways.
  # The table is read by the daemon so it can tell the app which rung the host
  # committed (MODE <w> <h> on the video socket).
  M=$F/streaming/mjpeg/m
  mkdir -p $M
  : > /data/local/tmp/uvc_modes.txt
  af_num=0
  add_frame() {
    af_name=$1; af_w=$2; af_h=$3; af_buf=$4
    af_num=$((af_num + 1))
    af_dir=$M/$af_name
    mkdir -p $af_dir
    echo $af_w > $af_dir/wWidth 2>/dev/null
    echo $af_h > $af_dir/wHeight 2>/dev/null
    # Upper bound for one JPEG at this size; the daemon sends the real length.
    echo $af_buf > $af_dir/dwMaxVideoFrameBufferSize 2>/dev/null
    # Declared bandwidth must stay inside what one high-speed isoc endpoint can
    # carry (3072 bytes per microframe, ~196 Mbit/s). Derive it from the frame
    # bound at the 30 fps we ask for; an invented w*h*160 figure made 2560x1440
    # claim 590 Mbit/s and macOS refused to open the device at all.
    echo $((af_buf * 240)) > $af_dir/dwMaxBitRate 2>/dev/null
    echo $((af_buf * 120)) > $af_dir/dwMinBitRate 2>/dev/null
    echo 333333 > $af_dir/dwDefaultFrameInterval 2>/dev/null
    echo 333333 > $af_dir/dwFrameInterval 2>/dev/null
    af_idx=$(cat $af_dir/bFrameIndex 2>/dev/null)
    af_ok=$(cat $af_dir/wWidth 2>/dev/null)
    if [ "$af_ok" != "$af_w" ]; then
      echo "UVC_FRAME_WRITE_FAILED $af_name wanted=$af_w got=$af_ok" >> /data/local/tmp/uvc_link.log
    fi
    # The kernel numbers frames in creation order, and bFrameIndex is not
    # reliably readable right after mkdir, so record the position we just used.
    echo "$af_num $af_w $af_h $af_buf" >> /data/local/tmp/uvc_modes.txt
  }
  # Portrait rungs only. A landscape canvas made the app rotate by
  # sensor-90 and resample, which is what showed the scene lying on its side
  # and produced stripe artefacts after a camera switch. With only portrait
  # modes the host must pick one, the app rotates by sensorOrientation (the
  # canonical mapping) and encodes straight into the negotiated canvas.
  add_frame p1080 1080 1920 400000
  add_frame p720 720 1280 250000
  mkdir -p $F/streaming/header/h
  ( cd $F/streaming/header/h && ln -s ../../mjpeg/m m )
  if [ ! -L $F/streaming/header/h/m ]; then
    echo "UVC_LINK_FAILED header/h/m missing (udc=$(cat $G/UDC 2>/dev/null))" > /data/local/tmp/uvc_link.log
  else
    rm -f /data/local/tmp/uvc_link.log
  fi
  [ -L $F/streaming/class/fs/h ] || ( cd $F/streaming/class/fs && ln -s ../../header/h h )
  [ -L $F/streaming/class/hs/h ] || ( cd $F/streaming/class/hs && ln -s ../../header/h h )
  [ -L $F/streaming/class/ss/h ] || ( cd $F/streaming/class/ss && ln -s ../../header/h h )
  echo 3072 > $F/streaming_maxpacket 2>/dev/null
  echo 1 > $F/streaming_interval 2>/dev/null
  echo 0 > $F/streaming_maxburst 2>/dev/null
  echo "$UVC_VER" > /data/local/tmp/uvc_desc.ver
  fi
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
sleep 1.2
STATE=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null)
VBUS=$(cat /sys/class/power_supply/usb/online 2>/dev/null)
if [ "$STATE" = "not attached" ] && [ -s /sys/class/udc/a600000.dwc3/state ] && [ -z "$(cat $G/UDC 2>/dev/null)" ]; then
  # Only a bind failure counts as wedged. "VBUS present but not attached" is
  # the normal state for a charge-only cable and must not trigger a fallback.
  echo "PORT=wedged"
fi
echo "STATE=$STATE"
echo "VBUS=$VBUS"
echo "HID_NODE=$HIDNODE"
for v in /sys/class/video4linux/video*; do
  [ "$(cat $v/name 2>/dev/null)" = "dwc3-gadget" ] && echo "VIDEO_NODE=/dev/$(basename $v)"
done
grep -q UAC2Gadget /proc/asound/cards 2>/dev/null && echo "CARD=UAC2Gadget"
echo "DAEMON=$(cat /data/local/tmp/marionette_usbd.pid 2>/dev/null)"
