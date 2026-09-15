#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ADB_SERIAL:-adb-510af65c-ycG5h8._adb-tls-connect._tcp}"
PKG="${1:?usage: $0 package.name [output-dir]}"
OUT="${2:-observations}"
ADB=(adb -s "$SERIAL")

mkdir -p "$OUT"
PID=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
if [[ -z "$PID" ]]; then
  echo "Package is not running: $PKG" >&2
  exit 2
fi

STAMP=$(date +%Y%m%d-%H%M%S)
echo "Observing $PKG (pid $PID). Output: $OUT/$PKG-$STAMP.log"
echo "This records process/network syscall metadata; it does not bypass TLS pinning."
"${ADB[@]}" shell "su -c '/system/bin/strace -ff -tt -T -e trace=network,read,write,sendto,recvfrom -p $PID'" \
  2>&1 | tee "$OUT/$PKG-$STAMP.log"
