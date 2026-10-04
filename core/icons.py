"""
felix-ai Core -- icons.py
Draws the app icon as a PNG with the standard library only (no Pillow
on Termux), so the web app can be installed without shipping binaries.
"""

import struct
import zlib

BG = (14, 17, 22)        # app background
ACCENT = (88, 166, 255)  # rounded tile
INK = (255, 255, 255)

_cache = {}


def _inside_round_rect(x, y, x0, y0, x1, y1, r):
    if x < x0 or x >= x1 or y < y0 or y >= y1:
        return False
    cx = min(max(x, x0 + r), x1 - r - 1)
    cy = min(max(y, y0 + r), y1 - r - 1)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def make_icon(size: int, maskable: bool = False) -> bytes:
    """A white 'F' on an accent tile. Maskable icons fill the whole
    canvas (the OS crops them); normal ones leave a margin."""
    key = (size, maskable)
    if key in _cache:
        return _cache[key]

    margin = 0 if maskable else int(size * 0.06)
    tile = (margin, margin, size - margin, size - margin)
    radius = 0 if maskable else int(size * 0.22)

    # Letter F inside the safe zone (central 60%).
    span = int(size * 0.6)
    left = (size - span) // 2
    top = (size - span) // 2
    bar = max(2, span // 5)
    parts = [
        (left, top, left + bar, top + span),                       # stem
        (left, top, left + span, top + bar),                       # top arm
        (left, top + int(span * 0.42), left + int(span * 0.78), top + int(span * 0.42) + bar),  # middle arm
    ]

    rows = []
    for y in range(size):
        row = bytearray([0])  # PNG filter type 0
        for x in range(size):
            if any(a <= x < c and b <= y < d for a, b, c, d in parts):
                px = INK
            elif _inside_round_rect(x, y, *tile, radius):
                px = ACCENT
            else:
                px = BG
            row.extend(px)
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    _cache[key] = png
    return png
