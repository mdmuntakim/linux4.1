#!/usr/bin/env bash
# Boot the built kernel + initramfs in QEMU and assert that the guest really
# runs Linux 4.1.
#
#   scripts/boot-test.sh arm64
#   scripts/boot-test.sh x86_64
#
# Exit status is 0 only if the serial console printed LINUX4.1_BOOT_OK with a
# release starting with "4.1" and the benchmark workload completed.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/qemu-args.sh"

guest=${1:-arm64}
kernel_arch "$guest" >/dev/null

case "$guest" in
    arm64) kernel="$OUT/kernel-arm64/Image" ;;
    x86_64) kernel="$OUT/kernel-x86_64/bzImage" ;;
esac
initrd="$OUT/rootfs-$guest/rootfs.cpio.gz"
[ -f "$kernel" ] || die "missing $kernel: run scripts/build-kernel.sh $guest"
[ -f "$initrd" ] || die "missing $initrd: run scripts/build-userspace.sh $guest"

qemu=$(qemu_bin "$guest")
mkdir -p "$OUT/logs"
log="$OUT/logs/boot-$guest.log"
rm -f "$log"

guest_qemu_args "$guest" bench 3 "$kernel" "$initrd"
log "booting $guest under $($qemu --version | head -1)"
set +e
timeout 240 "$qemu" "${QEMU_ARGS[@]}" -serial "file:$log"
status=$?
set -e

fail() {
    echo "boot test FAILED for $guest: $*" >&2
    tail -40 "$log" >&2 || true
    exit 1
}

[ -f "$log" ] || fail "no serial output (qemu exit $status)"
grep -q "LINUX4.1_BOOT_OK" "$log" || fail "guest never reached init"

release=$(sed -n 's/.*LINUX4.1_BOOT_OK //p' "$log" | head -1 | tr -d '\r')
case "$release" in
    4.1.*) log "guest uname -r: $release (starts with 4.1)" ;;
    *) fail "guest reports '$release', not a 4.1 kernel" ;;
esac

grep -q "LINUX4.1_BENCH_DONE" "$log" || fail "benchmark workload did not finish"
grep -q "RESULT fps=" "$log" || fail "guest produced no FPS result"
grep -q "zram" "$log" || log "note: zram message not seen"

log "boot test OK: $(grep -o 'RESULT fps=[0-9.]*' "$log" | head -1)"
echo "LINUX4.1_BOOT_TEST_OK guest=$guest release=$release"
