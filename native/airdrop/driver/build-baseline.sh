#!/usr/bin/env bash
# Build research objects/modules with CFI/LTO preserved. Never installs them;
# actual runtime and firmware compatibility require separate verification.
set -euo pipefail
if [[ $# != 4 || "$(uname -s)" != Linux ]]; then
  echo 'Usage (Linux): build-baseline.sh KERNEL_SOURCE KERNEL_OUT WLAN_SOURCE CLANG_DIR' >&2
  exit 2
fi
DRIVER_KERNEL="$(realpath "$1")"
DRIVER_OUT="$(realpath "$2")"
DRIVER_WLAN="$(realpath "$3")"
DRIVER_CLANG="$(realpath "$4")"
[[ -f "$DRIVER_OUT/include/generated/autoconf.h" && -f "$DRIVER_OUT/scripts/module.lds" ]] || {
  echo 'Kernel output is not prepared; run the documented modules_prepare first.' >&2; exit 3;
}
"$DRIVER_CLANG/bin/clang" --version | head -1 | grep -q 'r450784e.*14.0.7' || {
  echo 'Expected the recorded Android r450784e Clang 14.0.7 toolchain.' >&2; exit 4;
}
export PATH="$DRIVER_CLANG/bin:$PATH"
DRIVER_TARGET="${DRIVER_TARGET:-kiwi_v2.o}"
DRIVER_EXTRA=()
case "$DRIVER_TARGET" in
  kiwi_v2.o|kiwi_v2.lto.o) ;;
  modules)
    [[ -f "${DRIVER_PROVIDER_SYMVERS:-}" ]] || {
      echo 'Module linking requires an actual provider symvers via DRIVER_PROVIDER_SYMVERS.' >&2; exit 6;
    }
    grep -qx 'CONFIG_DEBUG_INFO_BTF_MODULES=y' "$DRIVER_OUT/.config" || {
      echo 'Module BTF layout differs from the recorded phone; install pahole and rerun olddefconfig.' >&2; exit 7;
    }
    DRIVER_EXTRA+=("KBUILD_EXTRA_SYMBOLS=$(realpath "$DRIVER_PROVIDER_SYMVERS")")
    ;;
  *) echo 'Unsupported DRIVER_TARGET' >&2; exit 8 ;;
esac
# The public kernel backports single-netdev MLO signatures into 5.15.
# Select the existing Android feature branches, preserving CFI type checking.
grep -q 'unsigned int link_id' "$DRIVER_KERNEL/include/net/cfg80211.h" || {
  echo 'Kernel cfg80211 API differs from the recorded baseline.' >&2; exit 5;
}
make -C "$DRIVER_KERNEL" O="$DRIVER_OUT" ARCH=arm64 LLVM=1 LLVM_IAS=1 \
  M="$DRIVER_WLAN/qcacld-3.0" WLAN_ROOT="$DRIVER_WLAN/qcacld-3.0" \
  WLAN_COMMON_ROOT=../qca-wifi-host-cmn WLAN_COMMON_INC="$DRIVER_WLAN/qca-wifi-host-cmn" \
  WLAN_FW_API="$DRIVER_WLAN/fw-api" WLAN_PLATFORM_INC="$DRIVER_WLAN/platform/inc" \
  WLAN_PROFILE=kiwi_v2 MODNAME=kiwi_v2 DEVNAME=kiwi_v2 WLAN_CTRL_NAME=wlan \
  CONFIG_QCA_CLD_WLAN=m CONFIG_CNSS_OUT_OF_TREE=y CONFIG_CNSS2=m \
  CONFIG_CNSS_UTILS=m CONFIG_CNSS_GENL=m CONFIG_BUS_AUTO_SUSPEND=y \
  'KCFLAGS=-D__ANDROID_COMMON_KERNEL__ -DHARDWARE_PROJECT_M2=1' \
  "${DRIVER_EXTRA[@]}" -j"${DRIVER_JOBS:-4}" "$DRIVER_TARGET"
