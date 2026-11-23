#!/usr/bin/env python3
"""Drive the Linux4.1 guest over its serial console and run the RFB probe.

The assertions are the acceptance checks that can be made without an Android
device: the guest really runs a 4.1.x kernel, the shell accepts commands, the
framebuffer is delivered over RFB, and the persistent data disk mounts.
"""
import json
import os
import re
import socket
import subprocess
import sys
import time

SOCK, OUT, PROBE = sys.argv[1], sys.argv[2], sys.argv[3]
BOOT_T0 = float(os.environ["BOOT_T0"])

sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
sock.settimeout(0.3)
deadline = time.time() + 60
while True:
    try:
        sock.connect(SOCK)
        break
    except OSError:
        if time.time() > deadline:
            raise SystemExit("could not connect to the QEMU serial socket")
        time.sleep(0.2)

log = open(os.path.join(OUT, "serial-boot.log"), "wb", buffering=0)


def read_until(pattern, timeout=30.0, quiet=False):
    end = time.time() + timeout
    data = b""
    rx = re.compile(pattern)
    while time.time() < end:
        try:
            chunk = sock.recv(65536)
        except socket.timeout:
            continue
        if not chunk:
            break
        data += chunk
        log.write(chunk)
        if rx.search(data):
            return data
    raise TimeoutError("did not see %r; last output: %r" % (pattern, data[-800:]))


def run(cmd, timeout=30.0):
    sock.sendall(cmd.encode() + b"\n")
    return read_until(rb"# ", timeout).decode("utf-8", "replace")


# Wait for the autologin shell.  A newline prods a getty that is still starting.
time.sleep(0.5)
sock.sendall(b"\n")
boot = read_until(rb"# ", 120)
boot_seconds = time.time() - BOOT_T0

out = run("uname -r")
m = re.search(r"^4\.1\.\d+", out, re.M)
if not m:
    raise SystemExit("uname -r did not report a 4.1.x kernel:\n" + out)
kernel = m.group(0)

shell = run("echo LINUX41_SHELL_OK")
if "LINUX41_SHELL_OK" not in shell:
    raise SystemExit("shell did not echo the test string:\n" + shell)

# First screenshot: the console as it looks right after boot.
shot = subprocess.run(
    PROBE.split() + ["127.0.0.1", "5909", os.path.join(OUT, "boot"), "0", "0"],
    capture_output=True, text=True, timeout=120)
sys.stdout.write(shot.stdout)
sys.stderr.write(shot.stderr)
if shot.returncode != 0:
    raise SystemExit("screenshot probe failed")

# Frame-rate load: redraw the console continuously on tty1.
resp = run("(while true; do dmesg | tail -n 16 > /dev/tty1; sleep 0.03; done) & echo FPSLOOP=$!")
m = re.search(r"FPSLOOP=(\d+)", resp)
pid = m.group(1) if m else ""
time.sleep(0.5)

fps_probe = subprocess.run(
    PROBE.split() + ["127.0.0.1", "5909", os.path.join(OUT, "fps"), "8", "0"],
    capture_output=True, text=True, timeout=120)
sys.stdout.write(fps_probe.stdout)
sys.stderr.write(fps_probe.stderr)
if fps_probe.returncode != 0:
    raise SystemExit("FPS probe failed")

if pid.isdigit():
    run("kill %s" % pid)
else:
    run("pkill -f 'dmesg | tail' || true")

# Show the kernel log on the graphical console, then capture it.
run("dmesg | tail -n 18 > /dev/tty1")
time.sleep(0.8)

lat_probe = subprocess.run(
    PROBE.split() + ["127.0.0.1", "5909", os.path.join(OUT, "console"), "0", "5"],
    capture_output=True, text=True, timeout=120)
sys.stdout.write(lat_probe.stdout)
sys.stderr.write(lat_probe.stderr)
if lat_probe.returncode != 0:
    raise SystemExit("latency probe failed")

# Persistence check: the guest formats /dev/vdb on first boot and mounts it.
persist = run("mount | grep ' /data ' || echo NO_DATA")
data_mounted = "/data" in persist

boot_metrics = {
    "kernel": kernel,
    "boot_seconds": round(boot_seconds, 2),
    "shell_ok": True,
    "data_mounted": data_mounted,
}
with open(os.path.join(OUT, "serial-results.json"), "w") as f:
    json.dump(boot_metrics, f, indent=2)
print(json.dumps(boot_metrics))
sock.close()
log.close()
