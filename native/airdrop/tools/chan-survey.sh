#!/system/bin/sh
# Scan and report what is on the station's channel, and whether AWDL frames
# are reachable there at all.  Used to decide if fw-promisc-probe.sh has any
# chance of seeing an AWDL frame before running it.
set -u

echo "=== link ==="
iw dev wlan0 link 2>/dev/null | head -2
CH=$(iw dev wlan0 info 2>/dev/null | sed -n 's/.*channel \([0-9]*\) .*/\1/p' | head -1)
echo "channel=${CH:-unknown}"

echo "=== scanning (this drops the link briefly) ==="
iw dev wlan0 scan >/dev/null 2>&1
SCAN=$(iw dev wlan0 scan dump 2>/dev/null)
echo "bss_count=$(echo "$SCAN" | grep -c '^BSS ')"

echo "$SCAN" | awk -v ch="$CH" '
  /^BSS / { bss=$2 }
  /freq:/ { f=$2; sub(/freq:/,"",f) }
  /SSID:/ { ssid=substr($0,index($0,"SSID:")+5) }
  /signal:/ { if (f != "") { printf "  %-20s freq=%-6s %s %s\n", bss, f, $1, ssid; f="" } }
' | sort -t= -k2 -n | head -40

echo "=== same-channel BSS count (channel ${CH:-?}) ==="
echo "$SCAN" | awk -v ch="$CH" '
  /^BSS / { bss=$2 }
  /freq:/ { f=$2; sub(/freq:/,"",f) }
  /signal:/ { if (f == ch) print "  " bss " " $1; f="" }
'
