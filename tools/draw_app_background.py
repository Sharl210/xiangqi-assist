#!/usr/bin/env python3
"""Generate the original Xiangqi Assist navy/gold app background (no external assets)."""
import math
import struct
import zlib
from pathlib import Path

W, H = 1080, 2400
TOP = (8, 22, 43)
BOTTOM = (15, 41, 69)
GOLD = (232, 183, 74)
RED = (190, 55, 56)

pixels = bytearray(W * H * 4)


def mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def set_pixel(x, y, color, alpha=255):
    if not (0 <= x < W and 0 <= y < H):
        return
    at = (y * W + x) * 4
    if alpha >= 255:
        pixels[at:at + 4] = bytes((*color, 255))
        return
    inv = 255 - alpha
    for i in range(3):
        pixels[at + i] = (color[i] * alpha + pixels[at + i] * inv) // 255
    pixels[at + 3] = 255


# Navy vertical gradient with a restrained golden glow near the masthead.
for y in range(H):
    t = y / (H - 1)
    base = mix(TOP, BOTTOM, t)
    for x in range(W):
        d = math.hypot((x - W * 0.5) / (W * 0.72), (y - H * 0.20) / (H * 0.23))
        glow = max(0.0, 1.0 - d) * 0.085
        c = tuple(min(255, int(base[i] * (1 - glow) + GOLD[i] * glow)) for i in range(3))
        at = (y * W + x) * 4
        pixels[at:at + 4] = bytes((*c, 255))


def line(x0, y0, x1, y1, color, alpha=255, width=2):
    dx, dy = x1 - x0, y1 - y0
    steps = max(abs(dx), abs(dy), 1)
    for n in range(steps + 1):
        x = int(x0 + dx * n / steps)
        y = int(y0 + dy * n / steps)
        for oy in range(-width // 2, width // 2 + 1):
            for ox in range(-width // 2, width // 2 + 1):
                set_pixel(x + ox, y + oy, color, alpha)


def circle(cx, cy, radius, color, alpha=255, width=3):
    steps = max(360, int(radius * 7))
    old = None
    for n in range(steps + 1):
        a = n * math.tau / steps
        p = (int(cx + math.cos(a) * radius), int(cy + math.sin(a) * radius))
        if old is not None:
            line(*old, *p, color, alpha, width)
        old = p


# Logo-like orbit rings.
for r, a, w in ((300, 34, 5), (244, 20, 3), (176, 12, 2)):
    circle(540, 420, r, GOLD, a, w)

# Abstract Chinese-chess board watermark; river gap is intentional.
left, right, top, bottom = 110, 970, 1030, 2020
for col in range(9):
    x = int(left + (right - left) * col / 8)
    line(x, top, x, 1490, GOLD, 15, 2)
    line(x, 1560, x, bottom, GOLD, 15, 2)
for row in range(10):
    y = int(top + (bottom - top) * row / 9)
    line(left, y, right, y, GOLD, 15, 2)
# Palaces.
line(432, top, 648, int(top + (bottom - top) * 2 / 9), GOLD, 18, 2)
line(648, top, 432, int(top + (bottom - top) * 2 / 9), GOLD, 18, 2)
line(432, int(top + (bottom - top) * 7 / 9), 648, bottom, GOLD, 18, 2)
line(648, int(top + (bottom - top) * 7 / 9), 432, bottom, GOLD, 18, 2)

# Quiet red seal accent in the lower-right corner.
for y in range(2150, 2246):
    for x in range(890, 986):
        edge = min(x - 890, 985 - x, y - 2150, 2245 - y)
        if edge >= 0:
            set_pixel(x, y, RED, 28 if edge > 6 else 70)

raw = b"".join(b"\0" + bytes(pixels[y * W * 4:(y + 1) * W * 4]) for y in range(H))


def chunk(kind, data):
    return (struct.pack(">I", len(data)) + kind + data +
            struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

png = (b"\x89PNG\r\n\x1a\n" +
       chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0)) +
       chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
out = Path(__file__).resolve().parents[1] / "app/src/main/res/drawable-nodpi/app_background.png"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_bytes(png)
print(out, len(png))
