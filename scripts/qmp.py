#!/usr/bin/env python3
"""Talk to a running QEMU over QMP.

  scripts/qmp.py shot    --socket S --path out.ppm
  scripts/qmp.py quit    --socket S
  scripts/qmp.py latency --socket S --serial serial.log --rounds 10 \
                         [--key a] [--settle 1.5]

`latency` sends key events through QEMU's input layer and measures the wall
time until the guest acknowledges them on the serial console.  That includes
the emulated input device, the guest input stack, the guest scheduler and the
serial output path, i.e. the latency a user would feel minus the host's own
display latency.
"""
import argparse
import json
import os
import socket
import statistics
import sys
import time


class QMP:
    def __init__(self, path, timeout=10.0):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(timeout)
        self.sock.connect(path)
        self.file = self.sock.makefile("rwb")
        self._read()  # greeting
        self.cmd("qmp_capabilities")

    def _read(self):
        while True:
            line = self.file.readline()
            if not line:
                raise RuntimeError("QMP connection closed")
            msg = json.loads(line)
            if "event" in msg:
                continue
            return msg

    def cmd(self, name, **arguments):
        payload = {"execute": name}
        if arguments:
            payload["arguments"] = arguments
        self.file.write((json.dumps(payload) + "\n").encode())
        self.file.flush()
        msg = self._read()
        if "error" in msg:
            raise RuntimeError(f"{name}: {msg['error']}")
        return msg.get("return")

    def close(self):
        try:
            self.file.close()
        finally:
            self.sock.close()


def send_key(q, key, down):
    q.cmd(
        "input-send-event",
        events=[
            {
                "type": "key",
                "data": {"down": down, "key": {"type": "qcode", "data": key}},
            }
        ],
    )


def wait_for_line(path, needle, offset, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if os.path.exists(path):
            with open(path, "r", errors="replace") as f:
                f.seek(offset)
                text = f.read()
            if needle in text:
                end = text.index(needle) + len(text[text.index(needle):].split("\n")[0])
                return text.index(needle), end
        time.sleep(0.001)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["shot", "quit", "latency"])
    ap.add_argument("--socket", required=True)
    ap.add_argument("--path")
    ap.add_argument("--serial")
    ap.add_argument("--rounds", type=int, default=10)
    ap.add_argument("--key", default="a")
    ap.add_argument("--settle", type=float, default=1.0)
    args = ap.parse_args()

    if args.command == "quit":
        q = QMP(args.socket)
        q.cmd("quit")
        q.close()
        return

    if args.command == "shot":
        if not args.path:
            sys.exit("--path is required")
        q = QMP(args.socket)
        q.cmd("screendump", filename=os.path.abspath(args.path))
        q.close()
        print(args.path)
        return

    if not args.serial:
        sys.exit("--serial is required for latency")

    start = time.time()
    while not os.path.exists(args.socket) and time.time() - start < 30:
        time.sleep(0.05)
    while not (os.path.exists(args.serial) and "FB_DESK_READY" in open(
            args.serial, errors="replace").read()):
        if time.time() - start > 120:
            sys.exit("guest desktop did not come up")
        time.sleep(0.05)

    time.sleep(args.settle)

    q = QMP(args.socket)

    # Send one key event, then poll the serial log for the guest ack.
    latencies = []
    for i in range(args.rounds):
        offset = os.path.getsize(args.serial)
        t0 = time.perf_counter()
        send_key(q, args.key, True)
        send_key(q, args.key, False)
        deadline = t0 + 5.0
        hit = False
        while time.perf_counter() < deadline:
            if os.path.getsize(args.serial) > offset:
                with open(args.serial, "r", errors="replace") as f:
                    f.seek(offset)
                    text = f.read()
                pos = text.find("INPUT_ACK")
                if pos >= 0:
                    hit = True
                    break
            time.sleep(0.0005)
        if hit:
            latencies.append((time.perf_counter() - t0) * 1000.0)
        time.sleep(0.05)

    q.close()
    if not latencies:
        sys.exit("no INPUT_ACK from the guest")
    latencies.sort()
    result = {
        "rounds": len(latencies),
        "median_ms": round(statistics.median(latencies), 2),
        "p95_ms": round(latencies[min(len(latencies) - 1, int(len(latencies) * 0.95))], 2),
        "min_ms": round(latencies[0], 2),
        "max_ms": round(latencies[-1], 2),
        "samples_ms": [round(x, 2) for x in latencies],
    }
    print(json.dumps(result))


if __name__ == "__main__":
    main()
