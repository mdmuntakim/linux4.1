#!/usr/bin/env bash
# Build the guest userspace from source and pack it into an initramfs.
#
#   scripts/build-userspace.sh arm64
#
# Everything in the image is compiled here: musl (libc), BusyBox (coreutils,
# shell, mount, mkswap...), and fbdesk (the framebuffer desktop/benchmark).
# No prebuilt distribution binary is copied in.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

guest=${1:-arm64}
kernel_arch "$guest" >/dev/null
arch_env "$guest"

sysroot="$BUILD/sysroot-$guest"
rootfs="$BUILD/rootfs-$guest"
out="$OUT/rootfs-$guest"

[ -f "$DL/musl-$MUSL_VERSION.tar.gz" ] || die "run scripts/fetch-sources.sh first"
[ -f "$DL/busybox-$BUSYBOX_VERSION.tar.bz2" ] || die "run scripts/fetch-sources.sh first"

mkdir -p "$BUILD" "$sysroot" "$out"
if [ "$guest" = x86_64 ]; then
    # A native x86_64 musl build is fine; the guest runs the same ISA.
    MUSL_CC=gcc
    MUSL_CROSS=
    BUSYBOX_STRIP=strip
    DESKTOP_CC=gcc
    MUSL_TARGET=x86_64-linux-musl
else
    MUSL_CC=$CC_NAME
    MUSL_CROSS=$CROSS_COMPILE
    BUSYBOX_STRIP=${CROSS_COMPILE}strip
    DESKTOP_CC=$CC_NAME
    MUSL_TARGET=aarch64-linux-musl
fi

# ---- musl -----------------------------------------------------------------
if [ ! -f "$sysroot/usr/lib/libc.a" ]; then
    log "building musl $MUSL_VERSION for $guest"
    src="$BUILD/musl-$guest"
    rm -rf "$src"
    mkdir -p "$src"
    tar xf "$DL/musl-$MUSL_VERSION.tar.gz" -C "$src" --strip-components=1
    (
        cd "$src"
        make distclean >/dev/null 2>&1 || true
        CC="$MUSL_CC" CROSS_COMPILE="$MUSL_CROSS" \
            ./configure --prefix=/usr --target="$MUSL_TARGET" \
            --disable-shared --enable-static >/dev/null
        make -j"$JOBS" >/dev/null
        make DESTDIR="$sysroot" install >/dev/null
    )
else
    log "musl already built in $sysroot"
fi

# ---- BusyBox --------------------------------------------------------------
busybox="$BUILD/busybox-$guest"
if [ ! -x "$busybox/busybox" ]; then
    log "building BusyBox $BUSYBOX_VERSION for $guest (static, musl)"
    rm -rf "$busybox"
    mkdir -p "$busybox"
    tar xf "$DL/busybox-$BUSYBOX_VERSION.tar.bz2" -C "$busybox" \
        --strip-components=1
    (
        cd "$busybox"
        make defconfig >/dev/null
        sed -i 's/^# CONFIG_STATIC is not set/CONFIG_STATIC=y/' .config
        # BusyBox' tc applet needs kernel uapi headers newer than musl ships
        # in its own include tree; the desktop does not use traffic control.
        sed -i 's/^CONFIG_TC=y/# CONFIG_TC is not set/' .config
        make -j"$JOBS" \
            CC="$MUSL_CC --sysroot=$sysroot" \
            HOSTCC=gcc \
            STRIP="$BUSYBOX_STRIP" >/dev/null
    )
else
    log "BusyBox already built in $busybox"
fi

# ---- desktop --------------------------------------------------------------
log "building fbdesk for $guest (static musl, stripped)"
if [ ! -f "$ROOT/userspace/desktop/font8x16.h" ]; then
    kdir="$BUILD/kernel-$guest/linux-$KERNEL_VERSION"
    [ -d "$kdir" ] || die "build the kernel first (font comes from its tree)"
    python3 "$ROOT/scripts/extract-font.py" "$kdir" \
        "$ROOT/userspace/desktop/font8x16.h"
fi
make -C "$ROOT/userspace/desktop" clean >/dev/null
make -C "$ROOT/userspace/desktop" \
    CC="$DESKTOP_CC --sysroot=$sysroot" >/dev/null
desktop="$ROOT/userspace/desktop/fbdesk"

# ---- rootfs ---------------------------------------------------------------
log "assembling the initramfs"
rm -rf "$rootfs"
mkdir -p "$rootfs"/{bin,sbin,etc,proc,sys,dev,tmp,run,root,usr/bin}
install -m 0755 "$busybox/busybox" "$rootfs/bin/busybox"
install -m 0755 "$desktop" "$rootfs/bin/fbdesk"
install -m 0755 "$ROOT/userspace/init" "$rootfs/init"
while read -r applet; do
    [ -n "$applet" ] || continue
    ln -sf busybox "$rootfs/bin/$applet"
done <"$ROOT/userspace/applets.txt"
cat >"$rootfs/etc/motd" <<'EOF'
Linux 4.1.52 (musl + BusyBox + fbdesk)
EOF
( cd "$rootfs" && find . -print0 | cpio --null -o -H newc 2>/dev/null |
      gzip -9 ) >"$out/rootfs.cpio.gz"

log "initramfs: $(du -h "$out/rootfs.cpio.gz" | cut -f1)"
log "uname reported by the built kernel: $(make -s -C \
    "$BUILD/kernel-$guest/linux-$KERNEL_VERSION" ARCH="$ARCH" kernelrelease)"
