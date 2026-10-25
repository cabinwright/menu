#!/usr/bin/env python3
"""Lays icons out side by side at 3x on Kia's dark background, to compare new icons
with stock ones.  usage: preview.py <out.png> <row1 png,png,...> [<row2 ...>]"""
import struct
import sys
import zlib

BG = (17, 18, 22)
SCALE = 3


def read_png(path):
    data = open(path, "rb").read()
    pos, idat = 8, b""
    while pos < len(data):
        ln, = struct.unpack(">I", data[pos:pos + 4])
        tag, body = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + ln]
        if tag == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            if depth != 8 or ctype != 6:
                sys.exit(path + ": only 8-bit RGBA PNGs")
        elif tag == b"IDAT":
            idat += body
        pos += 12 + ln
    raw, stride, rows, prev, i = zlib.decompress(idat), w * 4, [], bytearray(w * 4), 0
    for _ in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - 4] if x >= 4 else 0
            b = prev[x]
            c = prev[x - 4] if x >= 4 else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else (b if pb <= pc else c))) & 255
        rows.append(line)
        prev = line
    return w, h, rows


def main():
    out, grid = sys.argv[1], [r.split(",") for r in sys.argv[2:]]
    cell = 99 * SCALE + 10
    width, height = cell * max(len(r) for r in grid) + 10, cell * len(grid) + 10
    img = [[BG] * width for _ in range(height)]
    for r, row in enumerate(grid):
        for c, path in enumerate(row):
            w, h, px = read_png(path)
            for y in range(h):
                for x in range(w):
                    R, G, B, A = px[y][x * 4:x * 4 + 4]
                    col = tuple(round(v * A / 255 + bg * (1 - A / 255)) for v, bg in zip((R, G, B), BG))
                    for dy in range(SCALE):
                        for dx in range(SCALE):
                            img[10 + r * cell + y * SCALE + dy][10 + c * cell + x * SCALE + dx] = col
    raw = b"".join(b"\0" + bytes(v for p in row for v in p) for row in img)

    def chunk(t, b):
        return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)

    open(out, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
                          + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


if __name__ == "__main__":
    main()
