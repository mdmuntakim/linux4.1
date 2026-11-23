#!/usr/bin/env bash
# Boot the pinned Linux 4.1.52 kernel + rootfs in the pinned QEMU on the CI
# runner, assert the kernel and shell, capture screenshots through the app's
# own RFB client, and measure frame rate and input latency.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

need java
need javac
need python3

QEMU_DIR="${QEMU_DIR:-$OUT/qemu-host}"
KERNEL="${KERNEL:-$OUT/bzImage}"
ROOTFS="${ROOTFS:-$OUT/rootfs.img}"
DATA="${DATA:-$OUT/data.img}"
[ -x "$QEMU_DIR/qemu-system-x86_64" ] || die "host QEMU not found in $QEMU_DIR"
[ -f "$KERNEL" ] || die "kernel not found: $KERNEL"
[ -f "$ROOTFS" ] || die "rootfs not found: $ROOTFS"

rm -rf "$OUT/screenshots"
mkdir -p "$OUT/screenshots"

PROBE="$ROOT/build/probe"
rm -rf "$PROBE"
mkdir -p "$PROBE"
log "compiling the RFB probe from the app sources"
javac -d "$PROBE" \
  "$ROOT/app/app/src/main/java/com/mdmuntakim/linux41/RfbClient.java" \
  "$ROOT/app/app/src/main/java/com/mdmuntakim/linux41/Keys.java" \
  "$ROOT/tools/RfbProbe.java"

TMP="$(mktemp -d)"
QPID=""
cleanup() {
  if [ -n "$QPID" ]; then
    kill "$QPID" 2>/dev/null || true
    wait "$QPID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

log "booting QEMU (TCG multi-threaded) with the Linux 4.1.52 guest"
BOOT_T0="$(python3 -c 'import time; print(time.time())')"
"$QEMU_DIR/qemu-system-x86_64" \
  -L "$QEMU_DIR/pc-bios" \
  -name Linux4.1-boot-test \
  -m 512 -smp 2 \
  -accel tcg,thread=multi \
  -kernel "$KERNEL" \
  -append "console=ttyS0 console=tty0 root=/dev/vda rw rootfstype=ext4 loglevel=4 panic=5" \
  -drive "file=$ROOTFS,if=virtio,format=raw,cache=writeback" \
  -drive "file=$DATA,if=virtio,format=raw,cache=writeback" \
  -netdev user,id=n0 \
  -device virtio-net-pci,netdev=n0 \
  -vga std -display none \
  -vnc 127.0.0.1:9 \
  -serial "unix:$TMP/serial.sock,server=on,wait=off" \
  -monitor none -rtc base=utc -no-reboot \
  >"$OUT/qemu-stdout.log" 2>&1 &
QPID=$!

BOOT_T0="$BOOT_T0" timeout 300 python3 "$ROOT/scripts/serial_test.py" \
  "$TMP/serial.sock" "$OUT" \
  "java -cp $PROBE com.mdmuntakim.linux41.RfbProbe"

mv -f "$OUT/boot-screenshot.png" "$OUT/screenshots/boot-console.png"
mv -f "$OUT/console-screenshot.png" "$OUT/screenshots/kernel-log.png"

python3 - "$OUT" <<'EOF'
import json, os, sys
out = sys.argv[1]
def load(name):
    with open(os.path.join(out, name)) as f:
        return json.load(f)
serial = load("serial-results.json")
fps = load("fps-metrics.json")
lat = load("console-metrics.json")
result = {
    "kernel": serial["kernel"],
    "boot_seconds": serial["boot_seconds"],
    "shell_ok": serial["shell_ok"],
    "data_disk_mounted": serial["data_mounted"],
    "framebuffer": "%dx%d" % (fps.get("width", 0), fps.get("height", 0)),
    "fps": fps.get("fps", 0),
    "fps_frames": fps.get("fps_frames", 0),
    "input_latency_median_ms": lat.get("latency_median_ms", 0),
    "input_latency_samples_ms": lat.get("latency_samples_ms", []),
    "screenshots": ["screenshots/boot-console.png", "screenshots/kernel-log.png"],
}
with open(os.path.join(out, "test-results.json"), "w") as f:
    json.dump(result, f, indent=2)
    f.write("\n")
if not serial["kernel"].startswith("4.1."):
    raise SystemExit("kernel is not 4.1.x: " + serial["kernel"])
if not serial["shell_ok"]:
    raise SystemExit("shell check failed")
print(json.dumps(result, indent=2))
EOF

log "boot test passed: $(python3 -c 'import json;d=json.load(open("'"$OUT"'/test-results.json"));print(d["kernel"], "boot", d["boot_seconds"], "s")')"
