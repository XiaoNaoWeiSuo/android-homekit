#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NDK_DIR="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
case "$(uname -s)" in
  Darwin) NDK_HOST=darwin-x86_64 ;;
  Linux) NDK_HOST=linux-x86_64 ;;
  *) echo 'Unsupported NDK host' >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$NDK_HOST/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export CARGO_TARGET_DIR="$ROOT_DIR/native/airdrop/target"
cargo build --manifest-path "$ROOT_DIR/native/airdrop/Cargo.toml" --locked --release --target aarch64-linux-android
OUTPUT_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUTPUT_DIR"
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/filin" "$OUTPUT_DIR/libmarionette_awdl.so"
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/luftlift" "$OUTPUT_DIR/libmarionette_airdrop.so"
"$TOOLCHAIN/llvm-strip" "$OUTPUT_DIR/libmarionette_awdl.so" "$OUTPUT_DIR/libmarionette_airdrop.so"
echo 'Android ARM64 AirDrop backends packaged.'
