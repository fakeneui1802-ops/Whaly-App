#!/usr/bin/env python3
"""Whaly launcher art, drawn from code.

Everything is painted on a 64x64 grid and blown up with nearest neighbour so
the pixels stay square. Shapes go onto a mask first, the mask is dilated once
to produce a uniform white outline, then the details land on top.
"""
from __future__ import annotations

import os
import sys

from PIL import Image, ImageChops, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

GRID = 64
SCALE = 8

BLACK = (8, 10, 12)
WHITE = (255, 255, 255)
EYE = (58, 160, 255)

BG_NEAR = (26, 134, 224)
BG_FAR = (9, 34, 60)
BG_BLOCK = 4


def background() -> Image.Image:
    image = Image.new("RGB", (GRID, GRID), BG_FAR)
    draw = ImageDraw.Draw(image)
    blocks = GRID // BG_BLOCK
    for by in range(blocks):
        for bx in range(blocks):
            # Diagonal ramp, brightest at the top left like the reference.
            t = (bx + by) / (2 * (blocks - 1))
            t = round(t * 5) / 5
            colour = tuple(round(BG_NEAR[i] + (BG_FAR[i] - BG_NEAR[i]) * t) for i in range(3))
            x0, y0 = bx * BG_BLOCK, by * BG_BLOCK
            draw.rectangle((x0, y0, x0 + BG_BLOCK - 1, y0 + BG_BLOCK - 1), fill=colour)
    return image


def silhouette() -> Image.Image:
    mask = Image.new("L", (GRID, GRID), 0)
    draw = ImageDraw.Draw(mask)

    # Tail: two fat swept lobes over a short stem.
    draw.line([(32, 19), (19, 3)], fill=255, width=9)
    draw.line([(32, 19), (45, 3)], fill=255, width=9)
    draw.line([(32, 20), (32, 10)], fill=255, width=8)

    # Head: wide dome.
    draw.ellipse((12, 15, 52, 55), fill=255)

    # Jaw block that carries the baleen.
    draw.ellipse((17, 36, 47, 54), fill=255)

    # Pectoral fins.
    draw.polygon([(14, 40), (4, 50), (2, 57), (10, 53), (17, 45)], fill=255)
    draw.polygon([(50, 40), (60, 50), (62, 57), (54, 53), (47, 45)], fill=255)

    return mask


def shifted(mask: Image.Image, dx: int, dy: int) -> Image.Image:
    out = Image.new("L", mask.size, 0)
    out.paste(mask, (dx, dy))
    return out


def dilate(mask: Image.Image) -> Image.Image:
    out = mask.copy()
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            if dx or dy:
                out = ImageChops.lighter(out, shifted(mask, dx, dy))
    return out


def draw_details(image: Image.Image, mask: Image.Image) -> None:
    """Paint the white and blue marks, clipped to the whale silhouette."""
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    # Brow: one white arc per eye running from the outer edge up to the crown.
    draw.line([(17, 30), (20, 25), (25, 21), (31, 19)], fill=WHITE, width=2)
    draw.line([(47, 30), (44, 25), (39, 21), (33, 19)], fill=WHITE, width=2)

    # Eyes: angular blue wedges, inner corner lower than the outer.
    draw.polygon([(18, 31), (26, 26), (28, 30), (24, 35), (18, 35)], fill=EYE)
    draw.polygon([(46, 31), (38, 26), (36, 30), (40, 35), (46, 35)], fill=EYE)
    draw.rectangle((21, 29, 23, 30), fill=WHITE)
    draw.rectangle((41, 29, 43, 30), fill=WHITE)

    # Mouth line separating the dome from the jaw.
    draw.arc((15, 25, 49, 51), start=25, end=155, fill=WHITE, width=2)

    # Baleen: vertical plates hanging from the mouth line.
    for x in range(21, 45, 4):
        draw.line([(x, 41), (x, 51)], fill=WHITE, width=2)
    draw.arc((18, 40, 46, 58), start=200, end=340, fill=WHITE, width=2)

    alpha = ImageChops.multiply(layer.split()[3], mask)
    image.paste(layer.convert("RGB"), (0, 0), alpha)


def build() -> Image.Image:
    base = background()
    mask = silhouette()
    thick = dilate(dilate(mask))
    ring = ImageChops.subtract(thick, mask).point(lambda v: 255 if v > 0 else 0)

    base.paste(Image.new("RGB", base.size, WHITE), (0, 0), ring)
    base.paste(Image.new("RGB", base.size, BLACK), (0, 0), mask)
    draw_details(base, mask)
    return base.resize((GRID * SCALE, GRID * SCALE), Image.NEAREST)


def circular(image: Image.Image) -> Image.Image:
    size = image.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    out = image.convert("RGBA")
    out.putalpha(mask)
    return out


def monochrome(image: Image.Image, size: int = 432) -> Image.Image:
    grey = image.convert("L").resize((size, size), Image.NEAREST)
    alpha = grey.point(lambda v: 255 if v > 96 else 0)
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def write(image: Image.Image, path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, "PNG", optimize=True)
    print("  ", os.path.relpath(path, ROOT))


def main() -> int:
    art = build()
    res = os.path.join(ROOT, "app", "src", "main", "res")
    write(art, os.path.join(ROOT, "branding", "whaly-icon-source.png"))

    legacy = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
    for folder, size in legacy.items():
        scaled = art.resize((size, size), Image.NEAREST)
        write(scaled, os.path.join(res, folder, "ic_launcher.png"))
        write(circular(scaled), os.path.join(res, folder, "ic_launcher_round.png"))

    write(art.resize((512, 512), Image.NEAREST), os.path.join(res, "drawable-nodpi", "ic_launcher_art.png"))
    write(monochrome(art), os.path.join(res, "drawable-nodpi", "ic_launcher_monochrome_art.png"))

    docs = os.path.join(ROOT, "docs", "assets")
    write(art.resize((512, 512), Image.NEAREST), os.path.join(docs, "whaly-icon.png"))
    write(art.resize((64, 64), Image.NEAREST), os.path.join(docs, "favicon.png"))
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
