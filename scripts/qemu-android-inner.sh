#!/bin/sh
# Runs inside an aarch64 Alpine container.  Builds QEMU 5.0.0 statically with
# TCG and VNC only.  The result is shipped as lib/arm64-v8a/libqemu-system-x86_64.so
# so Android extracts it executable into nativeLibraryDir.
set -eu

: "${OUT:=/w/out}"
: "${CACHE:=/w/.cache}"
: "${JOBS:=4}"
. /w/scripts/pins.env

log() { printf '==> %s\n' "$*" >&2; }

log "installing build dependencies"
apk add --no-cache build-base glib-dev glib-static gettext-static \
  pixman-dev pixman-static zlib-dev zlib-static \
  perl python3 pkgconf curl xz tar linux-headers >/dev/null

mkdir -p "$CACHE" "$OUT/qemu-android"
TARBALL="$CACHE/qemu-${QEMU_VERSION}.tar.xz"
if [ ! -f "$TARBALL" ]; then
  log "downloading $QEMU_URL"
  curl -fL --retry 3 -o "$TARBALL.part" "$QEMU_URL"
  mv "$TARBALL.part" "$TARBALL"
fi
echo "$QEMU_SHA256  $TARBALL" | sha256sum -c - >/dev/null
log "verified qemu-${QEMU_VERSION}.tar.xz"

SRC="/w/build/qemu-${QEMU_VERSION}-android"
if [ ! -d "$SRC" ]; then
  mkdir -p "$SRC"
  tar -C "$SRC" --strip-components=1 -xf "$TARBALL"
fi

BUILD="/w/build/qemu-android"
rm -rf "$BUILD"
mkdir -p "$BUILD"
cd "$BUILD"

log "configure (static, x86_64-softmmu, TCG, VNC)"
"$SRC/configure" \
  --target-list=x86_64-softmmu \
  --static --disable-pie --disable-werror \
  --disable-sdl --disable-gtk --disable-opengl --disable-virglrenderer \
  --disable-vnc-jpeg --disable-vnc-png --disable-vnc-sasl \
  --disable-curses --disable-docs --disable-tools --disable-guest-agent \
  --disable-capstone --disable-libssh --disable-libusb --disable-usb-redir \
  --disable-bzip2 --disable-lzo --disable-snappy --disable-zstd \
  --disable-seccomp --disable-curl --disable-spice --disable-rbd \
  --disable-glusterfs --disable-libiscsi --disable-libnfs --disable-numa \
  --disable-tcmalloc --disable-jemalloc --disable-debug-info \
  --disable-stack-protector --enable-vnc --disable-kvm --disable-xen \
  --disable-vhost-net --disable-vhost-user --disable-vhost-vsock \
  --disable-rdma --disable-replication --disable-smartcard --disable-tpm \
  --disable-linux-aio --disable-attr --disable-cap-ng --disable-brlapi \
  --disable-vte --disable-xkbcommon --disable-libxml2 --disable-gnutls \
  --disable-nettle --disable-gcrypt --disable-auth-pam --disable-fdt \
  --disable-uuid --disable-iconv --disable-mpath --disable-containers \
  --disable-plugins --disable-live-block-migration

log "building with $JOBS jobs"
make -j"$JOBS"

log "installing"
mkdir -p "$OUT/qemu-android/pc-bios"
cp -f x86_64-softmmu/qemu-system-x86_64 "$OUT/qemu-android/libqemu-system-x86_64.so"
strip "$OUT/qemu-android/libqemu-system-x86_64.so"
for f in bios.bin bios-256k.bin vgabios-stdvga.bin vgabios-virtio.bin \
         kvmvapic.bin linuxboot.bin linuxboot_dma.bin multiboot.bin \
         pxe-e1000.rom pxe-virtio.rom; do
  [ -f "$SRC/pc-bios/$f" ] && cp -f "$SRC/pc-bios/$f" "$OUT/qemu-android/pc-bios/$f"
done
chmod 0755 "$OUT/qemu-android/libqemu-system-x86_64.so"

log "qemu-android: $(stat -c%s "$OUT/qemu-android/libqemu-system-x86_64.so") bytes"
file "$OUT/qemu-android/libqemu-system-x86_64.so" 2>/dev/null || true
