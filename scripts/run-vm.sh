#!/usr/bin/env bash
# Run the guest interactively (serial console on the terminal).
#
#   scripts/run-vm.sh arm64
#   scripts/run-vm.sh arm64 --graphics   # open a QEMU window with the desktop
set -euo pipefail
. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/qemu-args.sh"

guest=${1:-arm64}
graphics=0
for arg in "$@"; do [ "$arg" = "--graphics" ] && graphics=1; done

case "$guest" in
    arm64) kernel="$OUT/kernel-arm64/Image" ;;
    x86_64) kernel="$OUT/kernel-x86_64/bzImage" ;;
esac
initrd="$OUT/rootfs-$guest/rootfs.cpio.gz"

qemu=$(qemu_bin "$guest")
guest_qemu_args "$guest" desktop 0 "$kernel" "$initrd"

if [ "$graphics" = 1 ]; then
    # Serial on the terminal, desktop in a window.
    exec "$qemu" "${QEMU_ARGS[@]}" -display gtk -serial mon:stdio
fi
exec "$qemu" "${QEMU_ARGS[@]}" -serial mon:stdio
