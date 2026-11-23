#!/usr/bin/env bash
# QEMU, from source, for both guest architectures.
#
#   scripts/build-qemu.sh
#
# The build is host-native: the benchmark compares an aarch64 guest against an
# x86_64 guest on the same machine, so both must be emulated with the same TCG.
# On a phone the same QEMU source is cross-compiled for Android (docs/APK.md).
#
# QEMU 9.x builds with Meson (the legacy ./configure + Makefile front end is a
# compatibility shim since 9.0), so this script drives `meson setup`, `ninja` and
# `ninja install`.  The build directory is inside the source tree, under
# $BUILD/qemu-$QEMU_VERSION/build, and the binaries land in $BUILD/qemu-install.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

tarball="$DL/qemu-$QEMU_VERSION.tar.xz"
[ -f "$tarball" ] || die "run scripts/fetch-sources.sh first"

src="$BUILD/qemu-$QEMU_VERSION"
prefix="$BUILD/qemu-install"
build="$src/build"

if [ -x "$prefix/bin/qemu-system-aarch64" ] &&
   [ -x "$prefix/bin/qemu-system-x86_64" ] &&
   ! [ -n "${FORCE_QEMU_REBUILD:-}" ]; then
    log "QEMU already built in $prefix"
    "$prefix/bin/qemu-system-aarch64" --version | head -1
    exit 0
fi

if [ ! -d "$src" ]; then
    log "extracting QEMU $QEMU_VERSION"
    mkdir -p "$BUILD"
    tar xf "$tarball" -C "$BUILD"
fi

if [ -n "${FORCE_QEMU_REBUILD:-}" ]; then
    rm -rf "$build"
fi

if [ -f "$build/meson-info/intro-projectinfo.json" ]; then
    log "reconfiguring QEMU in $build"
    meson setup --reconfigure "$src" "$build" \
        --prefix="$prefix" \
        --target-list=aarch64-softmmu,x86_64-softmmu \
        --buildtype=release \
        -Ddocs=false -Dtools=false -Dguest_agent=false -Dwerror=false \
        -Ddebug_tcg=false -Dopengl=false -Dvirglrenderer=false \
        -Dplugins=true -Dslirp=true >/dev/null
else
    log "configuring QEMU (TCG only, aarch64 + x86_64 system emulation)"
    meson setup "$src" "$build" \
        --prefix="$prefix" \
        --target-list=aarch64-softmmu,x86_64-softmmu \
        --buildtype=release \
        -Ddocs=false -Dtools=false -Dguest_agent=false -Dwerror=false \
        -Ddebug_tcg=false -Dopengl=false -Dvirglrenderer=false \
        -Dplugins=true -Dslirp=true >/dev/null
fi

log "building QEMU with $JOBS jobs"
ninja -C "$build" >/dev/null
ninja -C "$build" install >/dev/null

"$prefix/bin/qemu-system-aarch64" --version | head -1
"$prefix/bin/qemu-system-x86_64" --version | head -1
log "QEMU installed in $prefix/bin"
