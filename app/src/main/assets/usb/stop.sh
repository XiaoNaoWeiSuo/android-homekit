#!/system/bin/sh
# Stop USB peripheral mode and hand the gadget back to the vendor HAL.
#
# Restoring adb is the delicate part: the vendor config already links
# ffs.mtp/ffs.adb as function0/function1. Linking the same function instance
# again (the old f1=ffs.adb / f2=ffs.mtp) makes the configuration fail to bind,
# which leaves the phone with no USB at all - no adb, no mtp, nothing the host
# can enumerate.
G=/config/usb_gadget/g1

killall -TERM marionette_usbd 2>/dev/null
sleep 0.4
killall -KILL marionette_usbd 2>/dev/null
rm -f /data/local/tmp/marionette_usbd.pid

echo "" > $G/UDC
sleep 0.4
cd $G/configs/b.1 || exit 1

# Drop only the links this project added. function0/function1 belong to the
# vendor configuration and must stay.
rm -f f1 f2 f3 f4

# Add ffs links only when the vendor did not leave its own behind.
have_adb=0
have_mtp=0
for l in function*; do
  [ -L "$l" ] || continue
  t=$(readlink "$l")
  case "$t" in
    *ffs.adb) have_adb=1 ;;
    *ffs.mtp) have_mtp=1 ;;
  esac
done
[ "$have_adb" = 1 ] || ln -s ../../functions/ffs.adb f1 2>/dev/null
[ "$have_mtp" = 1 ] || ln -s ../../functions/ffs.mtp f2 2>/dev/null

# Give the vendor HAL a moment to re-mount functionfs for adbd before binding.
start vendor.usbgadget-hal-1-1 2>/dev/null
sleep 0.8

echo a600000.dwc3 > $G/UDC
setprop sys.usb.config adb 2>/dev/null
sleep 0.5
echo "STATE=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null)"
echo "FUNCS=$(ls function* 2>/dev/null | tr '\n' ' ')"
