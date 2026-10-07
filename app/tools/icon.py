#!/usr/bin/env python3
"""Draws the launcher icon: the aperture mark of the app's header (MenuView,
I_APERTURE: a ring and six blades from an inner hexagon out to the rim), in
white on a black rounded tile, at the three densities the APK carries.

    tools/icon.py            -> res/drawable-{mdpi,hdpi,xhdpi}/ic_launcher.png

Same geometry as the drawn mark: ring radius r = side/2 - 1, stroke side/12,
blades from 0.42 r at (60k + 30) degrees to the rim 68 degrees further on,
round caps. Drawn 16x larger and scaled down, for clean edges.
"""
import math
import os

from PIL import Image, ImageDraw

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96}
SS = 16  # supersampling
TILE = (0, 0, 0, 255)
MARK = (255, 255, 255, 255)


def round_line(d, x0, y0, x1, y1, w, fill):
    d.line((x0, y0, x1, y1), fill=fill, width=int(round(w)))
    r = w / 2
    for x, y in ((x0, y0), (x1, y1)):
        d.ellipse((x - r, y - r, x + r, y + r), fill=fill)


def icon(size):
    n = size * SS
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # the tile: a black rounded square, edge to edge
    d.rounded_rectangle((0, 0, n - 1, n - 1), radius=n * 0.2, fill=TILE)
    # the mark, in a square of 72% of the tile, centred
    s = n * 0.72
    cx = cy = n / 2
    r = s / 2 - SS
    w = max(2 * SS, s / 12)
    d.ellipse((cx - r - w / 2, cy - r - w / 2, cx + r + w / 2, cy + r + w / 2), outline=MARK, width=int(round(w)))
    ri = r * 0.42
    for k in range(6):
        a = math.radians(k * 60 + 30)
        b = a + math.radians(68)
        round_line(d, cx + ri * math.cos(a), cy + ri * math.sin(a), cx + r * math.cos(b), cy + r * math.sin(b), w, MARK)
    return im.resize((size, size), Image.LANCZOS)


def main():
    res = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res")
    for dens, size in SIZES.items():
        out = os.path.join(res, "drawable-" + dens, "ic_launcher.png")
        icon(size).save(out, optimize=True)
        print(out, size)


if __name__ == "__main__":
    main()
