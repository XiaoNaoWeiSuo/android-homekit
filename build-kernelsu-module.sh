#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_TEMPLATE="$ROOT_DIR/kernelsu-module"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
OUTPUT_PATH="$ROOT_DIR/marionette-kernelsu-module.zip"

if [[ -x "$ROOT_DIR/gradlew" ]]; then
  GRADLE=("$ROOT_DIR/gradlew")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=("$(command -v gradle)")
elif [[ -n "${GRADLE_BIN:-}" && -x "$GRADLE_BIN" ]]; then
  GRADLE=("$GRADLE_BIN")
elif [[ -x "/Users/lin/.gradle/wrapper/dists/gradle-9.1.0-all/7wzd0jkjit61aq2p43wpjgij9/gradle-9.1.0/bin/gradle" ]]; then
  GRADLE=("/Users/lin/.gradle/wrapper/dists/gradle-9.1.0-all/7wzd0jkjit61aq2p43wpjgij9/gradle-9.1.0/bin/gradle")
else
  echo "找不到 Gradle。请安装 Gradle、设置 GRADLE_BIN，或把 gradlew 放到项目根目录。" >&2
  exit 1
fi

if [[ ! -f "$MODULE_TEMPLATE/module.prop" || ! -f "$MODULE_TEMPLATE/service.sh" || ! -f "$MODULE_TEMPLATE/boot-completed.sh" ]]; then
  echo "KernelSU 模块模板不完整：$MODULE_TEMPLATE" >&2
  exit 1
fi

echo "==> 构建 R8 release APK"
"${GRADLE[@]}" --no-daemon :app:assembleRelease

if [[ ! -f "$APK_PATH" ]]; then
  echo "构建完成但找不到 APK：$APK_PATH" >&2
  exit 1
fi

STAGE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/marionette-ksu.XXXXXX")"
trap 'rm -rf "$STAGE_DIR"' EXIT

echo "==> 组装 KernelSU 模块"
cp -R "$MODULE_TEMPLATE/." "$STAGE_DIR/"
mkdir -p "$STAGE_DIR/system/priv-app/Marionette"
cp "$APK_PATH" "$STAGE_DIR/system/priv-app/Marionette/Marionette.apk"
chmod 0755 "$STAGE_DIR/service.sh"
chmod 0755 "$STAGE_DIR/boot-completed.sh" "$STAGE_DIR/start.sh" "$STAGE_DIR/customize.sh"

rm -f "$OUTPUT_PATH"
(cd "$STAGE_DIR" && zip -Xqr "$OUTPUT_PATH" .)

echo "KernelSU 模块已生成：$OUTPUT_PATH"
du -h "$OUTPUT_PATH"
