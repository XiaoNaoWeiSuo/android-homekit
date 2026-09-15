#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ADB_SERIAL:-adb-510af65c-ycG5h8._adb-tls-connect._tcp}"
PKG="${1:?usage: $0 package.name [script] }"
SCRIPT="${2:-frida-tls-observe.js}"
ADB=(adb -s "$SERIAL")

"${ADB[@]}" push /tmp/frida-server /data/local/tmp/frida-server >/dev/null
"${ADB[@]}" shell "su -c 'chmod 755 /data/local/tmp/frida-server; pkill -f /data/local/tmp/frida-server || true; /data/local/tmp/frida-server >/data/local/tmp/frida-server.log 2>&1 &'"
sleep 1
"${ADB[@]}" shell "su -c 'pidof frida-server'"
echo "Attaching to $PKG; Ctrl-C stops observation."
frida -H 127.0.0.1:27042 -U "$PKG" -l "$SCRIPT"
