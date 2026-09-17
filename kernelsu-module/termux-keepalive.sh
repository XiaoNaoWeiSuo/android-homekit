#!/system/bin/sh
# termux-keepalive.sh
# 定期检查并重启Termux服务

TERMUX_HOME=/data/data/com.termux/files/home
TERMUX_PREFIX=/data/data/com.termux/files/usr
TERMUX_UID=10241

while true; do
  # 检查sshd
  if [ -x "$TERMUX_PREFIX/bin/sshd" ]; then
    if ! /system/bin/pidof sshd >/dev/null 2>&1; then
      su -u "$TERMUX_UID" -c "HOME=$TERMUX_HOME PREFIX=$TERMUX_PREFIX PATH=$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:$TERMUX_PREFIX/sbin /system/bin/sh -c '$TERMUX_PREFIX/bin/sshd -p 8022'" >/dev/null 2>&1 &
    fi
  fi

  # 检查mihomo
  if [ -x "$TERMUX_PREFIX/bin/mihomo" ]; then
    if ! /system/bin/pidof mihomo >/dev/null 2>&1; then
      su -u "$TERMUX_UID" -c "HOME=$TERMUX_HOME PREFIX=$TERMUX_PREFIX PATH=$TERMUX_PREFIX/bin $TERMUX_PREFIX/bin/mihomo -d $TERMUX_HOME/.config/mihomo" >/dev/null 2>&1 &
    fi
  fi

  sleep 60
done
