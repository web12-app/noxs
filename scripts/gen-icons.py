#!/usr/bin/env python3
"""gen-icons.py — generate Noxs launcher PNGs (original tooling).

Rasterizes the Noxs "N" vector (same paths as res/drawable/ic_noxs_logo.xml)
into mipmap-*/ic_launcher.png + ic_launcher_round.png for API < 26 devices.
Pure Python (struct + zlib), no PIL required.
"""
import math
import struct
import sys
import zlib
from pathlib import Path

# Vector paths from ic_noxs_logo.xml (108x108 viewport)
N_POLYGON = [(30, 78), (30, 30), (38, 30), (64, 62), (64, 30), (72, 30),
             (72, 78), (64, 78), (38, 46), (38, 78)]
BAR_POLYGON = [(30, 84), (78, 84), (78, 88), (30, 88)]
BG = (0x10, 0x14, 0x1A, 0xFF)
GREEN = (0x3D, 0xDC, 0x84, 0xFF)
BLUE = (0x6E, 0xA8, 0xFE, 0xFF)


def point_in_poly(x, y, poly):
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if ((yi > y) != (yj > y)) and (x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi):
            inside = not inside
        j = i
    return inside


def render(size):
    """Render with 3x3 supersampling for smooth edges."""
    ss = 3
    total = size * ss
    acc = [[[0, 0, 0, 0] for _ in range(size)] for _ in range(size)]
    for py in range(total):
        vy = (py + 0.5) * 108.0 / total
        for px in range(total):
            vx = (px + 0.5) * 108.0 / total
            if point_in_poly(vx, vy, N_POLYGON):
                c = GREEN
            elif point_in_poly(vx, vy, BAR_POLYGON):
                c = BLUE
            else:
                c = BG
            acc[(py // ss)][(px // ss)][0] += c[0]
            acc[(py // ss)][(px // ss)][1] += c[1]
            acc[(py // ss)][(px // ss)][2] += c[2]
            acc[(py // ss)][(px // ss)][3] += 255
    rows = []
    for y in range(size):
        row = bytearray([0])  # filter type 0
        s2 = ss * ss
        for x in range(size):
            r, g, b, a = [v // s2 for v in acc[y][x]]
            row += bytes((r, g, b, a))
        rows.append(bytes(row))
    return rows


def png_chunk(tag, data):
    raw = tag + data
    return struct.pack(">I", len(data)) + raw + struct.pack(">I", zlib.crc32(raw) & 0xFFFFFFFF)


def write_png(path, size, rows):
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    payload = (b"\x89PNG\r\n\x1a\n"
               + png_chunk(b"IHDR", header)
               + png_chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
               + png_chunk(b"IEND", b""))
    path.write_bytes(payload)


def rounded_mask(size, rows, radius_ratio=0.22):
    """Zero alpha outside a rounded-rect for the round icon variant."""
    radius = size * radius_ratio
    s2 = 1.0
    out = []
    for y in range(size):
        row = bytearray(rows[y])
        for x in range(size):
            # distance to rounded-rect bounds
            dx = max(radius - x, x - (size - 1 - radius), 0)
            dy = max(radius - y, y - (size - 1 - radius), 0)
            outside = (dx * dx + dy * dy) > radius * radius
            if outside:
                row[4 * x + 4 - 1] = 0  # alpha byte (row starts with filter byte)
        out.append(bytes(row))
    return out


def main(root):
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, size in sizes.items():
        d = Path(root) / f"app/src/main/res/mipmap-{dpi}"
        d.mkdir(parents=True, exist_ok=True)
        rows = render(size)
        write_png(d / "ic_launcher.png", size, rows)
        write_png(d / "ic_launcher_round.png", size, rounded_mask(size, rows))
        print(f"  mipmap-{dpi}: {size}px OK")


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    main(root)
    print("[noxs] launcher icons generated")
