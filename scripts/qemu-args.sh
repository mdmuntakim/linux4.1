#!/usr/bin/env bash
# Common QEMU command line for a guest.  Sourced by the run/boot/bench scripts.
#
# TCG is the baseline on every device: multi-threaded, 512 MiB guest RAM,
# 256 MiB translation block cache, a stdvga with a fixed 1024x768 EDID so the
# arm64 and x86_64 benchmarks draw exactly the same number of pixels, and
# virtio keyboard/tablet so input goes through the same device on both guests.

guest_qemu_args() {
    local guest=$1 mode=$2 seconds=$3 kernel=$4 initrd=$5
    local console

    case "$guest" in
        arm64)
            console=ttyAMA0
            QEMU_ARGS=(
                -M virt -cpu cortex-a57 -m 512M -smp 2
            )
            ;;
        x86_64)
            console=ttyS0
            QEMU_ARGS=(
                -M pc -cpu qemu64 -m 512M -smp 2
            )
            ;;
        *) die "unsupported guest: $guest" ;;
    esac

    QEMU_ARGS+=(
        -kernel "$kernel" -initrd "$initrd"
        -append "console=$console rdinit=/init panic=-1 quiet linux4.1.mode=$mode linux4.1.seconds=$seconds"
        -device VGA,edid=on,xres=1024,yres=768
        -device virtio-keyboard-pci
        -device virtio-tablet-pci
        -netdev user,id=n0 -device virtio-net-pci,netdev=n0
        -accel tcg,thread=multi,tb-size=256
        -display none -no-reboot
    )
    export QEMU_ARGS
}
