#!/bin/sh
# Build the root USB daemon for arm64 Android without Gradle.
# The output is packaged as a native lib so AGP extracts it with exec permission;
# it is still a normal PIE executable with a main().
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
NDK=${ANDROID_NDK_HOME:-$SDK/ndk/27.0.12077973}
CXX=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++
OUT=$ROOT/app/src/main/jniLibs/arm64-v8a/libmarionette_usbd.so
mkdir -p "$(dirname "$OUT")"
set -- "$ROOT"/app/src/main/cpp/*.cpp
"$CXX" -std=c++17 -O2 -fPIE -pie -Wall -Wextra -Wno-unused-parameter \
  -o "$OUT" "$@" -llog -ldl -pthread
echo "built $OUT"
file "$OUT" 2>/dev/null || true
