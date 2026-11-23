#!/usr/bin/env bash
# Benchmark the aarch64 guest and the x86_64 guest with the same workload.
#
#   scripts/bench.sh
#
# For each guest:
#   * boot time   - wall clock from QEMU start to LINUX4.1_BOOT_OK on serial
#   * FPS         - frames/s reported by the same fbdesk binary, same 1024x768
#                   framebuffer, same TCG settings, same 512 MiB guest RAM
#   * input       - wall clock from a QMP key event to the guest's serial ack
#
# Results go to out/bench/results.json and out/bench/results.md.  Nothing here
# is estimated: a missing number stays missing and fails the run.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/qemu-args.sh"

rounds=${ROUNDS:-10}
bench_seconds=${BENCH_SECONDS:-10}
benchdir="$OUT/bench"
mkdir -p "$benchdir" "$OUT/logs" "$OUT/screenshots"

host_cpu=$(sed -n 's/^model name[[:space:]]*: //p' /proc/cpuinfo | head -1)
host_kernel=$(uname -sr)
result_json="$benchdir/results.json"
echo '{"host": {"cpu": "'"$host_cpu"'", "kernel": "'"$host_kernel"'", "date": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"}, "guests": {}}' >"$result_json"

for guest in arm64 x86_64; do
    case "$guest" in
        arm64) kernel="$OUT/kernel-arm64/Image" ;;
        x86_64) kernel="$OUT/kernel-x86_64/bzImage" ;;
    esac
    initrd="$OUT/rootfs-$guest/rootfs.cpio.gz"
    [ -f "$kernel" ] && [ -f "$initrd" ] ||
        die "missing artifacts for $guest (build kernel + userspace first)"

    qemu=$(qemu_bin "$guest")
    log="$OUT/logs/bench-$guest.log"
    rm -f "$log"

    # ---- FPS + boot time --------------------------------------------------
    log "benchmarking $guest: $bench_seconds s of fbdesk"
    guest_qemu_args "$guest" bench "$bench_seconds" "$kernel" "$initrd"
    t0=$(date +%s%N)
    timeout 300 "$qemu" "${QEMU_ARGS[@]}" -serial "file:$log" >/dev/null 2>&1 &
    pid=$!
    boot_ms=""
    for _ in $(seq 1 30000); do
        if grep -q LINUX4.1_BOOT_OK "$log" 2>/dev/null; then
            t1=$(date +%s%N)
            boot_ms=$(( (t1 - t0) / 1000000 ))
            break
        fi
        kill -0 $pid 2>/dev/null || break
        sleep 0.01
    done
    wait $pid || true

    [ -n "$boot_ms" ] || die "$guest never booted"
    fps=$(sed -n 's/.*RESULT fps=\([0-9.]*\).*/\1/p' "$log" | tail -1)
    frames=$(sed -n 's/.*RESULT fps=[0-9.]* frames=\([0-9]*\).*/\1/p' "$log" | tail -1)
    total_us=$(sed -n 's/.*total_us=\([0-9]*\).*/\1/p' "$log" | tail -1)
    [ -n "$fps" ] || die "$guest produced no FPS result"
    log "  boot ${boot_ms} ms, ${fps} fps (${frames} frames, ${total_us} us)"

    # ---- input latency + screenshot --------------------------------------
    log "measuring $guest input latency ($rounds key events)"
    slog="$OUT/logs/desktop-$guest.log"
    sock="$benchdir/$guest.qmp"
    rm -f "$slog" "$sock"
    guest_qemu_args "$guest" desktop 0 "$kernel" "$initrd"
    "$qemu" "${QEMU_ARGS[@]}" -serial "file:$slog" \
        -qmp "unix:$sock,server=on,wait=off" >/dev/null 2>&1 &
    qpid=$!

    latency=$(python3 "$ROOT/scripts/qmp.py" latency --socket "$sock" \
        --serial "$slog" --rounds "$rounds" --settle 2.0) ||
        die "$guest input latency measurement failed"

    ppm="$OUT/screenshots/$guest.ppm"
    python3 "$ROOT/scripts/qmp.py" shot --socket "$sock" --path "$ppm" >/dev/null ||
        die "$guest screenshot failed"
    python3 "$ROOT/scripts/ppm2png.py" "$ppm" "$OUT/screenshots/$guest.png" >/dev/null
    rm -f "$ppm"
    python3 "$ROOT/scripts/qmp.py" quit --socket "$sock" >/dev/null 2>&1 || true
    wait $qpid 2>/dev/null || true

    log "  input median $(echo "$latency" | python3 -c 'import json,sys;print(json.load(sys.stdin)["median_ms"])') ms"

    python3 - "$result_json" "$guest" "$boot_ms" "$fps" "$frames" "$total_us" \
        "$latency" <<'PY'
import json
import sys

path, guest, boot_ms, fps, frames, total_us, latency = sys.argv[1:8]
data = json.load(open(path))
data["guests"][guest] = {
    "boot_ms": int(boot_ms),
    "fps": float(fps),
    "frames": int(frames),
    "workload_us": int(total_us),
    "framebuffer": "1024x768x32",
    "input": json.loads(latency),
}
json.dump(data, open(path, "w"), indent=2)
PY

    # A screenshot per guest, taken from the guest framebuffer, in docs/.
    if [ -f "$OUT/screenshots/$guest.png" ]; then
        cp "$OUT/screenshots/$guest.png" "$ROOT/docs/screenshots/$guest.png" 2>/dev/null || true
    fi
done

python3 - "$result_json" "$benchdir/results.md" <<'PY'
import json
import sys

data = json.load(open(sys.argv[1]))
rows = []
for guest, r in data["guests"].items():
    rows.append("| {g} | {b} ms | {f:.2f} | {i} ms | {p} ms | {n} |".format(
        g=guest, b=r["boot_ms"], f=r["fps"],
        i=r["input"]["median_ms"], p=r["input"]["p95_ms"],
        n=r["frames"]))
md = [
    f"Host: {data['host']['cpu']} ({data['host']['kernel']}), "
    f"measured {data['host']['date']}",
    "",
    "| guest | boot time | FPS | input median | input p95 | frames |",
    "| --- | --- | --- | --- | --- | --- |",
    *rows,
    "",
]
open(sys.argv[2], "w").write("\n".join(md))
print("\n".join(md))
PY

log "results: $result_json"
log "results: $benchdir/results.md"
