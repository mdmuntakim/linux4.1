#!/usr/bin/env bash
# Build the Alpine minirootfs guest image (ext4, x86_64).
set -euo pipefail
. "$(dirname "$0")/lib.sh"

need tar
need mke2fs
need sha256sum

fetch "$ALPINE_URL" "$CACHE/alpine-minirootfs-${ALPINE_VERSION}-x86_64.tar.gz" "$ALPINE_SHA256"

RFS="$ROOT/build/rootfs"
SUDO=""
[ "$(id -u)" = 0 ] || SUDO="sudo"

log "rootfs: extracting Alpine $ALPINE_VERSION minirootfs"
rm -rf "$RFS"
mkdir -p "$RFS"
$SUDO tar -xzf "$CACHE/alpine-minirootfs-${ALPINE_VERSION}-x86_64.tar.gz" -C "$RFS"

log "rootfs: applying Linux4.1 overlay"
$SUDO install -m 0644 "$ROOT/rootfs/inittab" "$RFS/etc/inittab"
$SUDO install -m 0755 "$ROOT/rootfs/linux41-init" "$RFS/etc/linux41-init"
$SUDO install -m 0644 "$ROOT/rootfs/motd" "$RFS/etc/motd"
$SUDO install -m 0644 "$ROOT/rootfs/profile-linux41.sh" "$RFS/etc/profile.d/linux41.sh"
$SUDO mkdir -p "$RFS/root" "$RFS/data" "$RFS/proc" "$RFS/sys" "$RFS/dev" "$RFS/tmp"
$SUDO chmod 1777 "$RFS/tmp"

# apk needs DNS inside the chroot.  e2fsprogs gives the guest mkfs.ext4 for the
# persistent data disk.
log "rootfs: installing e2fsprogs inside the chroot"
$SUDO cp -f /etc/resolv.conf "$RFS/etc/resolv.conf"
for d in proc sys dev; do
  $SUDO mount --bind "/$d" "$RFS/$d" 2>/dev/null || true
done
cleanup_mounts() {
  for d in dev sys proc; do
    $SUDO umount "$RFS/$d" 2>/dev/null || true
  done
}
trap cleanup_mounts EXIT
$SUDO chroot "$RFS" /bin/sh -c '
  set -e
  apk update >/dev/null
  apk add --no-cache e2fsprogs >/dev/null
  rm -rf /var/cache/apk/* /tmp/*
'
cleanup_mounts
trap - EXIT
$SUDO rm -f "$RFS/etc/resolv.conf"

mkdir -p "$OUT"
log "rootfs: creating ${ROOTFS_MIB} MiB ext4 image"
rm -f "$OUT/rootfs.img"
$SUDO mke2fs -q -t ext4 -F -L linux41 -m 1 -d "$RFS" "$OUT/rootfs.img" "${ROOTFS_MIB}M"
$SUDO chown "$(id -u):$(id -g)" "$OUT/rootfs.img"
log "rootfs: $(stat -c%s "$OUT/rootfs.img") bytes -> $OUT/rootfs.img"

# A blank sparse data disk for the CI boot test.  On the device the app
# creates this file itself at the size chosen in settings.
rm -f "$OUT/data.img"
truncate -s "${DATA_MIB}M" "$OUT/data.img"
log "rootfs: blank ${DATA_MIB} MiB data disk -> $OUT/data.img"
