#!/usr/bin/env bash
# Build the genuine Linux 4.1.52 kernel for the x86_64 QEMU guest.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

need make
need gcc
need bc
need flex
need bison

fetch "$LINUX_URL" "$CACHE/linux-${LINUX_VERSION}.tar.xz" "$LINUX_SHA256"
extract "$CACHE/linux-${LINUX_VERSION}.tar.xz" "$CACHE/linux-${LINUX_VERSION}"

SRC="$CACHE/linux-${LINUX_VERSION}"
KOUT="$ROOT/build/kernel"
mkdir -p "$KOUT" "$OUT"

log "kernel: x86_64_defconfig + kernel/linux41.fragment"
make -C "$SRC" O="$KOUT" x86_64_defconfig >/dev/null
"$SRC/scripts/kconfig/merge_config.sh" -m -O "$KOUT" \
  "$KOUT/.config" "$ROOT/kernel/linux41.fragment" >/dev/null
make -C "$SRC" O="$KOUT" olddefconfig >/dev/null

# Verify the options that the guest depends on really survived olddefconfig.
check() {
  local sym="$1" want="$2"
  local val
  val="$(sed -n "s/^${sym}=//p" "$KOUT/.config" | head -1)"
  [ "$val" = "$want" ] || die "$sym is '$val', expected '$want'"
}
check CONFIG_EXT4_FS y
check CONFIG_VIRTIO_BLK y
check CONFIG_VIRTIO_NET y
check CONFIG_DEVTMPFS_MOUNT y
check CONFIG_DRM_BOCHS y
check CONFIG_SERIAL_8250_CONSOLE y

log "kernel: building bzImage with $JOBS jobs"
make -C "$SRC" O="$KOUT" -j"$JOBS" KCFLAGS="-fcommon" bzImage

cp -f "$KOUT/arch/x86/boot/bzImage" "$OUT/bzImage"
cp -f "$KOUT/.config" "$OUT/kernel.config"
log "kernel: $(stat -c%s "$OUT/bzImage") bytes -> $OUT/bzImage"
