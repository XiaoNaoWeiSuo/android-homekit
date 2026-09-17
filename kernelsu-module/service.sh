#!/system/bin/sh
MODDIR="${0%/*}"

# 启动Marionette HomeKit服务
"$MODDIR/start.sh" late_start &

# 等待开机完成
attempt=0
while [ "$attempt" -lt 60 ]; do
  [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
  attempt=$((attempt + 1))
  sleep 2
done

# 等待用户解锁
attempt=0
while [ "$attempt" -lt 60 ]; do
  ce_available="$(getprop sys.user.0.ce_available 2>/dev/null)"
  [ "$ce_available" = "true" ] && break
  if [ -z "$ce_available" ] && [ "$(cmd user is-user-unlocked 0 2>/dev/null | tr -d '\r')" = "true" ]; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 2
done

# 启动Termux应用
am start --user 0 -n com.termux/.app.TermuxActivity 2>/dev/null

# 启动Termux sshd
TERMUX_HOME=/data/data/com.termux/files/home
TERMUX_PREFIX=/data/data/com.termux/files/usr
TERMUX_UID=10241

for i in 1 2 3 4 5 6 12 24; do
  [ -x "$TERMUX_PREFIX/bin/sshd" ] && break
  sleep 5
done

if [ -x "$TERMUX_PREFIX/bin/sshd" ]; then
  if ! /system/bin/pidof sshd >/dev/null 2>&1; then
    su -u "$TERMUX_UID" -c "HOME=$TERMUX_HOME PREFIX=$TERMUX_PREFIX PATH=$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:$TERMUX_PREFIX/sbin /system/bin/sh -c '$TERMUX_PREFIX/bin/sshd -p 8022'" >/dev/null 2>&1 &
  fi
fi

# 启动mihomo
if [ -x "$TERMUX_PREFIX/bin/mihomo" ]; then
  if ! /system/bin/pidof mihomo >/dev/null 2>&1; then
    su -u "$TERMUX_UID" -c "HOME=$TERMUX_HOME PREFIX=$TERMUX_PREFIX PATH=$TERMUX_PREFIX/bin $TERMUX_PREFIX/bin/mihomo -d $TERMUX_HOME/.config/mihomo" >/dev/null 2>&1 &
  fi
fi

# 启动保活脚本
if [ -f "$MODDIR/termux-keepalive.sh" ]; then
  sh "$MODDIR/termux-keepalive.sh" &
fi
