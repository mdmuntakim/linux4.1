#!/usr/bin/env bash
# Build QEMU 5.0.0 for the CI host (x86_64) so the boot test runs the same
# pinned emulator version as the app.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

need make
need gcc
need pkg-config

fetch "$QEMU_URL" "$CACHE/qemu-${QEMU_VERSION}.tar.xz" "$QEMU_SHA256"
extract "$CACHE/qemu-${QEMU_VERSION}.tar.xz" "$CACHE/qemu-${QEMU_VERSION}"

BUILD="$ROOT/build/qemu-host"
rm -rf "$BUILD"
mkdir -p "$BUILD"
cd "$BUILD"

log "qemu-host: configure"
"$CACHE/qemu-${QEMU_VERSION}/configure" \
  --target-list=x86_64-softmmu --disable-werror \
  --disable-sdl --disable-gtk --disable-opengl --disable-virglrenderer \
  --disable-vnc-jpeg --disable-vnc-sasl --disable-curses --disable-docs \
  --disable-tools --disable-guest-agent --disable-capstone --disable-libssh \
  --disable-usb-redir --disable-spice --disable-rbd --disable-glusterfs \
  --disable-libiscsi --disable-numa --disable-debug-info --enable-vnc \
  --disable-kvm --disable-xen --disable-vhost-net --disable-vhost-user \
  --disable-vhost-vsock --disable-rdma --disable-replication \
  --disable-smartcard --disable-tpm --disable-linux-aio --disable-attr \
  --disable-brlapi --disable-vte --disable-xkbcommon --disable-libxml2 \
  --disable-gnutls --disable-nettle --disable-gcrypt --disable-auth-pam \
  --disable-uuid --disable-iconv --disable-mpath --disable-containers \
  --disable-plugins --disable-live-block-migration >/dev/null

log "qemu-host: building with $JOBS jobs"
make -j"$JOBS"

mkdir -p "$OUT/qemu-host/pc-bios"
cp -f x86_64-softmmu/qemu-system-x86_64 "$OUT/qemu-host/qemu-system-x86_64"
for f in bios.bin bios-256k.bin vgabios-stdvga.bin vgabios-virtio.bin \
         kvmvapic.bin linuxboot.bin linuxboot_dma.bin multiboot.bin \
         pxe-e1000.rom pxe-virtio.rom; do
  [ -f "$CACHE/qemu-${QEMU_VERSION}/pc-bios/$f" ] && \
    cp -f "$CACHE/qemu-${QEMU_VERSION}/pc-bios/$f" "$OUT/qemu-host/pc-bios/$f"
done
log "qemu-host: $(stat -c%s "$OUT/qemu-host/qemu-system-x86_64") bytes"
