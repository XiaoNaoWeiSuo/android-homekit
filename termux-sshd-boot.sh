#!/system/bin/sh

# KernelSU service.d launcher for Termux OpenSSH.
# Starts only the Termux user's sshd; does not run an SSH daemon as root.

TERMUX_HOME=/data/data/com.termux/files/home
TERMUX_PREFIX=/data/data/com.termux/files/usr
TERMUX_UID=10241

for i in 1 2 3 4 5 6 12 24; do
  [ -x "$TERMUX_PREFIX/bin/sshd" ] && break
  sleep 5
done

[ -x "$TERMUX_PREFIX/bin/sshd" ] || exit 0

if ! /system/bin/pidof sshd >/dev/null 2>&1; then
  su -u "$TERMUX_UID" -c "HOME=$TERMUX_HOME PREFIX=$TERMUX_PREFIX PATH=$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:$TERMUX_PREFIX/sbin /system/bin/sh -c '$TERMUX_PREFIX/bin/sshd -p 8022'" >/dev/null 2>&1
fi

# Start the Cloudflare Tunnel only when a token file has been configured.
TOKEN_FILE="$TERMUX_HOME/.cloudflared/tunnel.token"
LOG_FILE="$TERMUX_HOME/.cloudflared/tunnel.log"
if [ -x "$TERMUX_PREFIX/bin/cloudflared" ] && [ -s "$TOKEN_FILE" ] && ! /system/bin/pgrep -f 'cloudflared tunnel run' >/dev/null 2>&1; then
  su -u "$TERMUX_UID" -c "HOME=$TERMUX_HOME PREFIX=$TERMUX_PREFIX PATH=$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:$TERMUX_PREFIX/sbin TUNNEL_TOKEN=\$(cat \"$TOKEN_FILE\") $TERMUX_PREFIX/bin/cloudflared tunnel run >>\"$LOG_FILE\" 2>&1" >/dev/null 2>&1 &
fi
