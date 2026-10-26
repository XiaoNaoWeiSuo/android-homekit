#!/usr/bin/env bash
set -euo pipefail
PROBE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROBE_NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
case "$(uname -s)" in
  Darwin) PROBE_HOST=darwin-x86_64 ;;
  Linux) PROBE_HOST=linux-x86_64 ;;
  *) echo 'Unsupported NDK host' >&2; exit 1 ;;
esac
mkdir -p "$PROBE_ROOT/build/airdrop"
"$PROBE_NDK/toolchains/llvm/prebuilt/$PROBE_HOST/bin/aarch64-linux-android26-clang" \
  -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra -Werror -O2 \
  "$PROBE_ROOT/native/airdrop/tools/coexist-probe.c" -o "$PROBE_ROOT/build/airdrop/coexist-probe"
"$PROBE_NDK/toolchains/llvm/prebuilt/$PROBE_HOST/bin/aarch64-linux-android26-clang" \
  -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra -Werror -O2 \
  "$PROBE_ROOT/native/airdrop/tools/coexist-probe-test.c" -o "$PROBE_ROOT/build/airdrop/coexist-probe-test"
"$PROBE_NDK/toolchains/llvm/prebuilt/$PROBE_HOST/bin/aarch64-linux-android26-clang" \
  -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra -Werror -O2 \
  "$PROBE_ROOT/native/airdrop/tools/kmsg-reader.c" -o "$PROBE_ROOT/build/airdrop/kmsg-reader"
"$PROBE_NDK/toolchains/llvm/prebuilt/$PROBE_HOST/bin/aarch64-linux-android26-clang" \
  -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra -Werror -O2 \
  "$PROBE_ROOT/native/airdrop/tools/awdl-witness.c" -o "$PROBE_ROOT/build/airdrop/awdl-witness"
"$PROBE_NDK/toolchains/llvm/prebuilt/$PROBE_HOST/bin/aarch64-linux-android26-clang" \
  -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra -Werror -O2 \
  "$PROBE_ROOT/native/airdrop/tools/awdl-witness-test.c" -o "$PROBE_ROOT/build/airdrop/awdl-witness-test"
