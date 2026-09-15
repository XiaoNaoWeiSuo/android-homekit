#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

APP_ID="dev.local.mihotspot"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"

if [[ -z "${JAVA_HOME:-}" && -x "/Applications/Android Studio.app/Contents/jbr/bin/java" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

if [[ -x "$ROOT_DIR/gradlew" ]]; then
  GRADLE=("$ROOT_DIR/gradlew")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=("$(command -v gradle)")
else
  GRADLE=("$HOME/.gradle/wrapper/dists/gradle-9.1.0-all/7wzd0jkjit61aq2p43wpjgij9/gradle-9.1.0/bin/gradle")
fi

if [[ ! -x "${GRADLE[0]}" ]]; then
  echo "找不到 Gradle 9.1，请安装 Gradle 或修改脚本中的 Gradle 路径。" >&2
  exit 1
fi

echo "==> 构建 R8 优化 release APK"
"${GRADLE[@]}" --no-daemon :app:clean :app:assembleRelease

if [[ ! -f "$APK_PATH" ]]; then
  echo "构建完成但找不到 APK：$APK_PATH" >&2
  exit 1
fi

echo "==> APK 大小"
du -h "$APK_PATH"

if ! command -v adb >/dev/null 2>&1; then
  echo "未找到 adb；APK 已生成，跳过安装。" >&2
  exit 0
fi

SERIAL="${ADB_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
  SERIAL="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
fi

if [[ -z "$SERIAL" ]]; then
  echo "没有检测到已授权 Android 设备；APK 已生成：$APK_PATH"
  exit 0
fi

ABI_LIST="$(adb -s "$SERIAL" shell getprop ro.product.cpu.abilist | tr -d '\r')"
if [[ "$ABI_LIST" != *arm64-v8a* ]]; then
  echo "设备不是 ARM64：$ABI_LIST" >&2
  exit 1
fi

echo "==> 安装到 ${SERIAL}（保留应用数据和 HomeKit 配对状态）"
adb -s "$SERIAL" install -r "$APK_PATH"

echo "==> 启动应用并检查前台服务"
adb -s "$SERIAL" shell am start -n "$APP_ID/.MainActivity" >/dev/null
SERVICE_OK=0
for _ in $(seq 1 15); do
  if adb -s "$SERIAL" shell dumpsys activity services "$APP_ID" | grep -q 'isForeground=true'; then
    SERVICE_OK=1
    break
  fi
  sleep 1
done
if [[ "$SERVICE_OK" == "1" ]]; then
  echo "前台服务已运行，安装完成。"
else
  echo "APK 已安装，但暂未检测到前台服务；请确认通知权限和蓝牙权限。" >&2
  exit 2
fi

echo "APK: $APK_PATH"
