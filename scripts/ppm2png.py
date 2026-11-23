#!/usr/bin/env python3
"""Convert a binary PPM (QEMU screendump) to PNG without external libraries."""
import struct
import sys
import zlib


def read_ppm(path):
    data = open(path, "rb").read()
    fields = []
    pos = 0
    while len(fields) < 4:
        while data[pos:pos + 1].isspace():
            pos += 1
        if data[pos:pos + 1] == b"#":
            pos = data.index(b"\n", pos) + 1
            continue
        start = pos
        while not data[pos:pos + 1].isspace():
            pos += 1
        fields.append(data[start:pos])
    pos += 1  # single whitespace after maxval
    magic, w, h, maxval = fields[0], int(fields[1]), int(fields[2]), int(fields[3])
    if magic != b"P6" or maxval != 255:
        raise SystemExit(f"{path}: not an 8-bit binary PPM ({magic}, maxval {maxval})")
    return w, h, data[pos:pos + w * h * 3]


def main():
    src, dst = sys.argv[1], sys.argv[2]
    w, h, pix = read_ppm(src)
    raw = b"".join(b"\x00" + pix[y * w * 3:(y + 1) * w * 3] for y in range(h))

    def chunk(tag, payload):
        return (struct.pack(">I", len(payload)) + tag + payload +
                struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n" +
           chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) +
           chunk(b"IDAT", zlib.compress(raw, 9)) +
           chunk(b"IEND", b""))
    open(dst, "wb").write(png)
    print(f"{dst} {w}x{h}")


if __name__ == "__main__":
    main()
