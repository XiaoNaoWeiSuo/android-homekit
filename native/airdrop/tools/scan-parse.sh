#!/system/bin/sh
# Correct scan parse: pair each BSSID with ITS OWN freq/signal/SSID.
#
# The earlier chan-survey.sh awk kept the SSID of the *next* BSS block, which
# mis-attributed 5c:7d:ae:5c:60:6e (actually YUNI_ZTE_5G on 2437) to
# "LIMO-5G ceshi".  This one flushes the record at every BSS line.
#
# Usage: scan-parse.sh [ssid-filter]
set -u

WANT=${1:-}
iw dev wlan0 scan >/dev/null 2>&1
SCAN=$(iw dev wlan0 scan dump 2>/dev/null)

echo "$SCAN" | awk -v want="$WANT" '
  function flush() {
    if (bss != "" && (want == "" || ssid == want))
      printf "%-20s freq=%-6s ch=%-4s %-18s %s\n", bss, f, ch, sig, (ssid == "" ? "<hidden>" : ssid)
  }
  /^BSS / {
    flush()
    bss = $2; sub(/\(.*/, "", bss)
    f = ""; ch = ""; sig = ""; ssid = ""; ds = 0
  }
  /freq:/ { f = $2; sub(/freq:/, "", f) }
  /signal:/ { sig = $1 " " $2 }
  /DS Parameter set: channel/ { ds = $NF }
  /SSID:/ { ssid = substr($0, index($0, "SSID:") + 5) }
  END { flush() }
' | sort -t= -k2 -n

echo "=== channel map for the target SSID ==="
echo "$SCAN" | awk -v want="$WANT" '
  /^BSS / { if (bss != "" && ssid == want) print "  " bss " freq=" f; bss = $2; sub(/\(.*/, "", bss); f = ""; ssid = "" }
  /freq:/ { f = $2; sub(/freq:/, "", f) }
  /SSID:/ { ssid = substr($0, index($0, "SSID:") + 5) }
  END { if (bss != "" && ssid == want) print "  " bss " freq=" f }
'
