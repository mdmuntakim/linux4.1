#!/usr/bin/env bash
# Build Linux 4.1.52 from kernel.org source for one guest architecture.
#
#   scripts/build-kernel.sh arm64
#   scripts/build-kernel.sh x86_64
#
# The patch series in kernel/patches/ makes the 2017 tree build with a modern
# GCC/binutils; the config fragment adds the virtio/DRM/zram bits a QEMU guest
# needs.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

guest=${1:-arm64}
kernel_arch "$guest" >/dev/null
arch_env "$guest"

src="$BUILD/kernel-$guest/linux-$KERNEL_VERSION"
tarball="$DL/linux-$KERNEL_VERSION.tar.xz"
[ -f "$tarball" ] || die "missing $tarball: run scripts/fetch-sources.sh"

mkdir -p "$BUILD/kernel-$guest" "$OUT/kernel-$guest"

if [ ! -d "$src" ]; then
    log "extracting linux-$KERNEL_VERSION for $guest"
    tar xf "$tarball" -C "$BUILD/kernel-$guest"
fi

cd "$src"

log "applying the modern-toolchain patch series"
for p in "$ROOT"/kernel/patches/*.patch; do
    if patch -p1 --dry-run --silent <"$p" >/dev/null 2>&1; then
        patch -p1 --silent <"$p"
        log "  applied $(basename "$p")"
    elif patch -p1 -R --dry-run --silent <"$p" >/dev/null 2>&1; then
        log "  already applied $(basename "$p")"
    else
        die "cannot apply $(basename "$p")"
    fi
done

log "configuring for $guest (defconfig + kernel/configs/qemu_$guest.fragment)"
make -s ARCH="$ARCH" CROSS_COMPILE="$CROSS_COMPILE" CC="$CC_NAME" defconfig
ARCH="$ARCH" CROSS_COMPILE="$CROSS_COMPILE" \
    "$ROOT/scripts/kconfig-merge.sh" .config \
    "$ROOT/kernel/configs/qemu_$guest.fragment"
make -s ARCH="$ARCH" CROSS_COMPILE="$CROSS_COMPILE" CC="$CC_NAME" olddefconfig

log "building the kernel with $($CC_NAME --version | head -1)"
case "$guest" in
    arm64) target=Image ;;
    x86_64) target=bzImage ;;
esac
make -s ARCH="$ARCH" CROSS_COMPILE="$CROSS_COMPILE" CC="$CC_NAME" -j"$JOBS" "$target"

case "$guest" in
    arm64) cp arch/arm64/boot/Image "$OUT/kernel-$guest/Image" ;;
    x86_64) cp arch/x86/boot/bzImage "$OUT/kernel-$guest/bzImage" ;;
esac
cp .config "$OUT/kernel-$guest/config"

log "kernel release: $(make -s ARCH=$ARCH kernelrelease)"
log "artifacts in $OUT/kernel-$guest"
ls -l "$OUT/kernel-$guest"
