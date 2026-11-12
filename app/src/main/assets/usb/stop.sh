#!/system/bin/sh
# Stop USB peripheral mode and hand the gadget back to the vendor HAL.
#
# Restoring adb is the delicate part: the vendor config already links
# ffs.mtp/ffs.adb as function0/function1. Linking the same function instance
# again (the old f1=ffs.adb / f2=ffs.mtp) makes the configuration fail to bind,
# which leaves the phone with no USB at all - no adb, no mtp, nothing the host
# can enumerate.
G=/config/usb_gadget/g1

# ---- preflight: never unbind while a host is attached ----
# Tearing the gadget down under a live host is what makes this kernel die: the
# UVC function's deactivate percpu_ref is still in flight, its RCU callback
# fires after the function is gone, and percpu_ref_switch_to_atomic_rcu()
# dereferences freed memory (seen as an Oops in rcuop). Defer instead.
CUR=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null)
case "$CUR" in
  configured|addressed|powered)
    echo "PREFLIGHT=host-attached-stop"
    echo "STATE=$CUR"
    exit 3
    ;;
esac

killall -TERM marionette_usbd 2>/dev/null
# Wait until the process is actually gone before unbinding: its fd release is
# what deactivates the UVC function, and that must not overlap the teardown.
i=0
while [ -n "$(pidof marionette_usbd)" ] && [ $i -lt 20 ]; do
  sleep 0.1
  i=$((i + 1))
done
killall -KILL marionette_usbd 2>/dev/null
rm -f /data/local/tmp/marionette_usbd.pid

echo "" > $G/UDC
sleep 0.8
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

# Never leave the gadget without ffs.adb: if that link is missing the vendor
# config cannot bind and the phone needs a reboot to get any USB at all.
adb_linked=0
for l in f* function*; do
  [ -L "$l" ] || continue
  case "$(readlink "$l")" in *ffs.adb) adb_linked=1 ;; esac
done
if [ "$adb_linked" = 0 ]; then
  ln -s ../../functions/ffs.adb f1 2>/dev/null
fi

# Give the vendor HAL a moment to re-mount functionfs for adbd before binding.
start vendor.usbgadget-hal-1-1 2>/dev/null
sleep 0.8

# Bind, retrying a couple of times: configfs refuses while the composite is
# still releasing the previous gadget, and a silent failure here is exactly the
# "nothing happens when I plug in" state.
i=0
while [ -z "$(cat $G/UDC 2>/dev/null)" ] && [ $i -lt 3 ]; do
  echo a600000.dwc3 > $G/UDC 2>/dev/null
  sleep 0.4
  i=$((i + 1))
done
setprop sys.usb.config adb 2>/dev/null
sleep 0.5
echo "STATE=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null)"
echo "BOUND=$(cat $G/UDC 2>/dev/null)"
echo "ADB_LINK=$adb_linked"
echo "FUNCS=$(ls f* function* 2>/dev/null | tr '\n' ' ')"
