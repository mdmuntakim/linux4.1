#!/usr/bin/env bash
# Assemble the signed arm64 APK from the built kernel, rootfs and QEMU.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

KERNEL="${KERNEL:-$OUT/bzImage}"
ROOTFS="${ROOTFS:-$OUT/rootfs.img}"
QEMU_ANDROID_DIR="${QEMU_ANDROID_DIR:-$OUT/qemu-android}"
APP="$ROOT/app"
ASSETS="$APP/app/src/main/assets"
JNI="$APP/app/src/main/jniLibs/arm64-v8a"

[ -f "$KERNEL" ] || die "kernel not found: $KERNEL"
[ -f "$ROOTFS" ] || die "rootfs not found: $ROOTFS"
[ -f "$QEMU_ANDROID_DIR/libqemu-system-x86_64.so" ] || die "arm64 QEMU not found in $QEMU_ANDROID_DIR"
[ -f "$APP/keystore/linux41.jks" ] || die "signing keystore missing"

log "staging guest assets"
rm -rf "$ASSETS" "$JNI"
mkdir -p "$ASSETS/kernel" "$ASSETS/rootfs" "$ASSETS/qemu" "$JNI"
cp -f "$KERNEL" "$ASSETS/kernel/bzImage"
cp -f "$ROOTFS" "$ASSETS/rootfs/rootfs.img"
cp -a "$QEMU_ANDROID_DIR/pc-bios" "$ASSETS/qemu/pc-bios"
cp -f "$QEMU_ANDROID_DIR/libqemu-system-x86_64.so" "$JNI/libqemu-system-x86_64.so"
printf '1.0.0\n' > "$ASSETS/version.txt"

log "building the release APK"
( cd "$APP" && ./gradlew --no-daemon --stacktrace assembleRelease )

APK="$APP/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "APK not produced"
cp -f "$APK" "$OUT/Linux4.1-arm64.apk"

log "verifying the ABI list"
ABIS="$(unzip -l "$APK" | awk '/lib\// {print $4}' | cut -d/ -f2 | sort -u)"
[ "$ABIS" = "arm64-v8a" ] || die "APK contains unexpected ABIs: $ABIS"

BT="$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] || die "Android build-tools not found"
"$BT/apksigner" verify --print-certs "$APK" | head -5

log "APK: $(stat -c%s "$OUT/Linux4.1-arm64.apk") bytes -> $OUT/Linux4.1-arm64.apk"
log "ABI: $ABIS"
log "APK entry sizes (bytes):"
unzip -l "$APK" | awk '/assets\/|lib\// {printf "  %10d  %s\n", $1, $4}'
