#!/usr/bin/env bash
# Pinned upstream versions and their SHA-256.  Every source this project ships
# is fetched from its own upstream, never from a distribution.
set -euo pipefail

KERNEL_VERSION=4.1.52
MUSL_VERSION=1.2.5
BUSYBOX_VERSION=1.36.1
QEMU_VERSION=9.2.3

KERNEL_URL="https://cdn.kernel.org/pub/linux/kernel/v4.x/linux-${KERNEL_VERSION}.tar.xz"
KERNEL_SHA256=6ad9389e55e0ea57768eae173747058a4487fa3630e10a7999cfec9f945e559c

MUSL_URL="https://musl.libc.org/releases/musl-${MUSL_VERSION}.tar.gz"
MUSL_SHA256=a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4

BUSYBOX_URL="https://busybox.net/downloads/busybox-${BUSYBOX_VERSION}.tar.bz2"
BUSYBOX_SHA256=b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314

QEMU_URL="https://download.qemu.org/qemu-${QEMU_VERSION}.tar.xz"
QEMU_SHA256=baed494270c361bf69816acc84512e3efed71c7a23f76691642b80bc3de7693e
