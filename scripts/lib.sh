#!/usr/bin/env bash
# Shared helpers.  Sourced by every script, never executed directly.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BUILD=${BUILD_DIR:-$ROOT/build}
OUT=${OUT_DIR:-$ROOT/out}
DL=${DL_DIR:-$ROOT/dl}
JOBS=${JOBS:-$(nproc)}

# shellcheck source=scripts/versions.sh
. "$ROOT/scripts/versions.sh"

log() { printf '==> %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

kernel_arch() {
    case "$1" in
        arm64) echo arm64 ;;
        x86_64) echo x86_64 ;;
        *) die "unsupported architecture: $1 (arm64 or x86_64)" ;;
    esac
}

# Cross toolchain for arm64.  GCC 13 is the newest GCC that builds 4.1.52
# without C23 surprises; the patch series still makes it work with newer ones.
arch_env() {
    case "$1" in
        arm64)
            ARCH=arm64
            CROSS_COMPILE=aarch64-linux-gnu-
            CC_NAME=aarch64-linux-gnu-gcc-13
            command -v "$CC_NAME" >/dev/null ||
                CC_NAME=aarch64-linux-gnu-gcc
            command -v "$CC_NAME" >/dev/null ||
                die "need gcc-aarch64-linux-gnu (apt install gcc-aarch64-linux-gnu)"
            ;;
        x86_64)
            ARCH=x86_64
            CROSS_COMPILE=
            CC_NAME=gcc
            ;;
        *) die "unsupported architecture: $1" ;;
    esac
    export ARCH CROSS_COMPILE
}

qemu_system() {
    case "$1" in
        arm64) echo qemu-system-aarch64 ;;
        x86_64) echo qemu-system-x86_64 ;;
    esac
}

qemu_bin() {
    local name
    name=$(qemu_system "$1")
    if [ -x "$BUILD/qemu-install/bin/$name" ]; then
        echo "$BUILD/qemu-install/bin/$name"
    elif command -v "$name" >/dev/null; then
        command -v "$name"
    else
        die "$name not found: run scripts/build-qemu.sh"
    fi
}
