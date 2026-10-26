#!/usr/bin/env bash
# Linux-only, against the already validated kernel output and actual GKI exports.
set -euo pipefail
if [[ $# != 5 || "$(uname -s)" != Linux ]]; then
  echo 'Usage: build-die-logger.sh KERNEL_SOURCE KERNEL_OUT SOURCE_DIR CLANG_DIR PROVIDER_SYMVERS' >&2
  exit 2
fi
DIE_KERNEL="$(realpath "$1")"
DIE_OUT="$(realpath "$2")"
DIE_SOURCE="$(realpath "$3")"
DIE_CLANG="$(realpath "$4")"
DIE_SYMVERS="$(realpath "$5")"
grep -qx 'CONFIG_DEBUG_INFO_BTF_MODULES=y' "$DIE_OUT/.config"
export PATH="$DIE_CLANG/bin:$PATH"
make -C "$DIE_KERNEL" O="$DIE_OUT" M="$DIE_SOURCE" ARCH=arm64 LLVM=1 LLVM_IAS=1 \
  KBUILD_EXTRA_SYMBOLS="$DIE_SYMVERS" -j4 modules
