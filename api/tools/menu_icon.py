#!/usr/bin/env python3
"""Makes All Menus icons in the stock style from a filled SVG path.

The head unit's Launcher_WP3 icons (99x99 px, hdpi) are solid glyphs filled with a vertical
gradient over the glyph's own height, with separate day and night colours, plus a flat
translucent "disabled" version of each. The colours below were measured from the stock
icons (EV, Setup, Phone): see README.md.

usage: menu_icon.py <glyph.svgpath> <res dir> <name>
  <glyph.svgpath>  an SVG path "d" string in a 24x24 view box (filled, nonzero rule)
  writes <res>/drawable-hdpi/<name>.png, <name>_dis.png and the same in drawable-night-hdpi

Pure Python (no image libraries): paths are flattened to polygons and rasterized with
4x4 supersampling.
"""
import math
import re
import struct
import sys
import zlib
from pathlib import Path

SIZE = 99
# glyph box inside the 99 px icon, as in the head unit's (glyphs span about y 21-80)
BOX = (17.0, 20.5, 82.0, 80.5)
SS = 4

PALETTES = {
    "day": [(0.0, (0x34, 0xE0, 0xD5)), (0.5, (0x5C, 0xB3, 0xDA)), (1.0, (0x84, 0x7F, 0xDC))],
    "night": [(0.0, (0x32, 0xA6, 0xAE)), (0.5, (0x3E, 0x81, 0xB1)), (1.0, (0x4E, 0x52, 0xB3))],
}
DISABLED = {"day": ((0x5D, 0x73, 0x78), 52), "night": ((0x5E, 0x75, 0x7C), 100)}


# ---------------------------------------------------------------- path parsing

def tokens(d):
    for m in re.finditer(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?", d):
        yield m.group(0)


def flatten(d, steps=16):
    """SVG path -> list of closed polygons (lists of (x, y))."""
    toks = list(tokens(d))
    i = 0
    polys, cur = [], []
    x = y = sx = sy = 0.0
    cmd = None
    last_ctrl = None

    def num():
        nonlocal i
        v = float(toks[i])
        i += 1
        return v

    def flag():
        # arc flags may be packed ("011")
        nonlocal i
        t = toks[i]
        if len(t) > 1 and t[0] in "01" and not t.startswith(("0.", "1.")):
            toks[i] = t[1:]
            return int(t[0])
        i += 1
        return int(float(t))

    def close():
        nonlocal cur
        if len(cur) > 2:
            polys.append(cur)
        cur = []

    while i < len(toks):
        if re.match(r"[A-Za-z]", toks[i]):
            cmd = toks[i]
            i += 1
            if cmd in "Zz":
                close()
                x, y = sx, sy
                last_ctrl = None
                continue
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = (x, y) if rel else (0.0, 0.0)
        if c == "M":
            close()
            x, y = ox + num(), oy + num()
            sx, sy = x, y
            cur = [(x, y)]
            cmd = "l" if rel else "L"
            last_ctrl = None
        elif c == "L":
            x, y = ox + num(), oy + num()
            cur.append((x, y))
            last_ctrl = None
        elif c == "H":
            x = ox + num() if rel else num()
            cur.append((x, y))
            last_ctrl = None
        elif c == "V":
            y = (y + num()) if rel else num()
            cur.append((x, y))
            last_ctrl = None
        elif c in "CS":
            if c == "C":
                x1, y1 = ox + num(), oy + num()
            else:
                x1, y1 = (2 * x - last_ctrl[0], 2 * y - last_ctrl[1]) if last_ctrl else (x, y)
            x2, y2 = ox + num(), oy + num()
            ex, ey = ox + num(), oy + num()
            for k in range(1, steps + 1):
                t = k / steps
                mt = 1 - t
                cur.append((mt ** 3 * x + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t ** 3 * ex,
                            mt ** 3 * y + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t ** 3 * ey))
            last_ctrl = (x2, y2)
            x, y = ex, ey
        elif c in "QT":
            if c == "Q":
                x1, y1 = ox + num(), oy + num()
            else:
                x1, y1 = (2 * x - last_ctrl[0], 2 * y - last_ctrl[1]) if last_ctrl else (x, y)
            ex, ey = ox + num(), oy + num()
            for k in range(1, steps + 1):
                t = k / steps
                mt = 1 - t
                cur.append((mt * mt * x + 2 * mt * t * x1 + t * t * ex, mt * mt * y + 2 * mt * t * y1 + t * t * ey))
            last_ctrl = (x1, y1)
            x, y = ex, ey
        elif c == "A":
            rx, ry, rot = num(), num(), num()
            large, sweep = flag(), flag()
            ex, ey = ox + num(), oy + num()
            cur.extend(arc(x, y, rx, ry, rot, large, sweep, ex, ey, steps * 2))
            x, y = ex, ey
            last_ctrl = None
        else:
            raise ValueError("unsupported path command " + cmd)
    close()
    return polys


def arc(x1, y1, rx, ry, rot, large, sweep, x2, y2, steps):
    """Endpoint arc -> points (SVG implementation notes F.6)."""
    if rx == 0 or ry == 0:
        return [(x2, y2)]
    phi = math.radians(rot)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    rx, ry = abs(rx), abs(ry)
    lam = (x1p / rx) ** 2 + (y1p / ry) ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0, num / den)) * (-1 if large == sweep else 1)
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(2, int(steps * abs(dt) / math.pi))
    pts = []
    for k in range(1, n + 1):
        t = t1 + dt * k / n
        pts.append((cx + rx * math.cos(t) * cp - ry * math.sin(t) * sp,
                    cy + rx * math.cos(t) * sp + ry * math.sin(t) * cp))
    return pts


# ---------------------------------------------------------------- rendering

def coverage(polys):
    """Per-pixel coverage (0..1) of the glyph, nonzero fill, glyph scaled into BOX."""
    x0, y0, x1, y1 = BOX
    scale = min(x1 - x0, y1 - y0) / 24.0
    offx = x0 + ((x1 - x0) - 24 * scale) / 2
    offy = y0 + ((y1 - y0) - 24 * scale) / 2
    edges = []
    for poly in polys:
        pts = [(offx + px * scale, offy + py * scale) for px, py in poly]
        for (ax, ay), (bx, by) in zip(pts, pts[1:] + pts[:1]):
            if ay != by:
                edges.append((ax, ay, bx, by))
    cov = [[0.0] * SIZE for _ in range(SIZE)]
    for row in range(SIZE * SS):
        sy = (row + 0.5) / SS
        xs = []
        for ax, ay, bx, by in edges:
            if (ay <= sy < by) or (by <= sy < ay):
                xs.append((ax + (sy - ay) * (bx - ax) / (by - ay), 1 if by > ay else -1))
        xs.sort()
        wind = 0
        for k in range(len(xs) - 1):
            wind += xs[k][1]
            if wind == 0:
                continue
            a, b = xs[k][0] * SS, xs[k + 1][0] * SS
            for col in range(max(0, math.ceil(a - 0.5)), min(SIZE * SS, math.ceil(b - 0.5))):
                cov[row // SS][col // SS] += 1.0 / (SS * SS)
    return cov


def glyph_rows(cov):
    rows = [y for y in range(SIZE) if any(v > 0.5 for v in cov[y])]
    return (rows[0], rows[-1]) if rows else (0, SIZE - 1)


def gradient(stops, t):
    t = max(0.0, min(1.0, t))
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            f = (t - t0) / (t1 - t0)
            return tuple(round(a + (b - a) * f) for a, b in zip(c0, c1))
    return stops[-1][1]


def image(cov, mode, disabled):
    top, bottom = glyph_rows(cov)
    out = []
    for y in range(SIZE):
        line = bytearray()
        for x in range(SIZE):
            a = min(1.0, cov[y][x])
            if disabled:
                rgb, alpha = DISABLED[mode]
                line += bytes(rgb) + bytes([round(alpha * a)])
            else:
                # mostly vertical, slightly diagonal, like the stock icons
                t = (y - top) / max(1, bottom - top) * 0.92 + (x - 17) / 65 * 0.08
                line += bytes(gradient(PALETTES[mode], t)) + bytes([round(255 * a)])
        out.append(bytes(line))
    return out


def png(rows):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw = b"".join(b"\0" + r for r in rows)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    glyph, res, name = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
    cov = coverage(flatten(glyph.read_text().strip()))
    for mode, folder in (("day", "drawable-hdpi"), ("night", "drawable-night-hdpi")):
        (res / folder).mkdir(parents=True, exist_ok=True)
        (res / folder / (name + ".png")).write_bytes(png(image(cov, mode, False)))
        (res / folder / (name + "_dis.png")).write_bytes(png(image(cov, mode, True)))


if __name__ == "__main__":
    main()
