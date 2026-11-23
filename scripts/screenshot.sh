#!/usr/bin/env bash
# Take a real screenshot of the guest desktop.
#
#   scripts/screenshot.sh arm64  [output-dir]
#   scripts/screenshot.sh x86_64 [output-dir]
#
# The VM is booted from the same kernel and initramfs that ship in the release;
# the monitor's screendump copies the guest framebuffer to a PPM, which is
# converted to PNG here.  Nothing is drawn on the host.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/qemu-args.sh"

guest=${1:-arm64}
outdir=${2:-$OUT/screenshots}
kernel_arch "$guest" >/dev/null

case "$guest" in
    arm64) kernel="$OUT/kernel-arm64/Image" ;;
    x86_64) kernel="$OUT/kernel-x86_64/bzImage" ;;
esac
initrd="$OUT/rootfs-$guest/rootfs.cpio.gz"
qemu=$(qemu_bin "$guest")

mkdir -p "$outdir" "$OUT/logs"
log="$OUT/logs/screenshot-$guest.log"
sock="$OUT/logs/screenshot-$guest.qmp"
rm -f "$log" "$sock"

guest_qemu_args "$guest" desktop 0 "$kernel" "$initrd"
"$qemu" "${QEMU_ARGS[@]}" -serial "file:$log" \
    -qmp "unix:$sock,server=on,wait=off" &
pid=$!
trap 'kill $pid 2>/dev/null || true' EXIT

for _ in $(seq 1 600); do
    grep -q FB_DESK_READY "$log" 2>/dev/null && break
    kill -0 $pid 2>/dev/null || die "qemu exited before the desktop came up"
    sleep 0.1
done
grep -q FB_DESK_READY "$log" || die "guest desktop did not come up"
sleep 2

ppm="$outdir/$guest.ppm"
png="$outdir/$guest.png"
python3 "$ROOT/scripts/qmp.py" shot --socket "$sock" --path "$ppm" >/dev/null
python3 "$ROOT/scripts/ppm2png.py" "$ppm" "$png"
rm -f "$ppm"
python3 "$ROOT/scripts/qmp.py" quit --socket "$sock" >/dev/null 2>&1 || true
wait $pid 2>/dev/null || true
trap - EXIT
log "screenshot: $png ($(du -h "$png" | cut -f1))"
