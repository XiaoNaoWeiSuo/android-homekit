#!/system/bin/sh
# Stop USB peripheral mode and hand the gadget back to the vendor HAL.
G=/config/usb_gadget/g1

killall -TERM marionette_usbd 2>/dev/null
sleep 0.4
killall -KILL marionette_usbd 2>/dev/null
rm -f /data/local/tmp/marionette_usbd.pid

echo "" > $G/UDC
sleep 0.4
cd $G/configs/b.1 || exit 1
rm -f f1 f2 f3 f4
ln -s ../../functions/ffs.adb f1 2>/dev/null
ln -s ../../functions/ffs.mtp f2 2>/dev/null
echo a600000.dwc3 > $G/UDC
start vendor.usbgadget-hal-1-1 2>/dev/null
setprop sys.usb.config adb 2>/dev/null
echo "STATE=$(cat /sys/class/udc/a600000.dwc3/state 2>/dev/null)"
