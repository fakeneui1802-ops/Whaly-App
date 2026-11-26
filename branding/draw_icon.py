#!/usr/bin/env python3
"""Whaly launcher art, drawn from code.

Painted on a 64x64 grid and blown up with nearest neighbour so the pixels stay
square. The whale goes onto a mask first, the mask is dilated twice for the
two-pixel white outline, then the marks land on top clipped to the silhouette.
"""
from __future__ import annotations

import os
import sys

from PIL import Image, ImageChops, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

GRID = 64
SCALE = 8

BLACK = (6, 8, 10)
WHITE = (255, 255, 255)
EYE = (41, 148, 246)

BG_NEAR = (27, 143, 224)
BG_FAR = (9, 39, 64)
BG_BLOCK = 8


def background() -> Image.Image:
    image = Image.new("RGB", (GRID, GRID), BG_FAR)
    draw = ImageDraw.Draw(image)
    blocks = GRID // BG_BLOCK
    for by in range(blocks):
        for bx in range(blocks):
            # Diagonal ramp, brightest at the top left like the reference art.
            t = (bx + by) / (2 * (blocks - 1))
            t = round(t * 5) / 5
            colour = tuple(round(BG_NEAR[i] + (BG_FAR[i] - BG_NEAR[i]) * t) for i in range(3))
            x0, y0 = bx * BG_BLOCK, by * BG_BLOCK
            draw.rectangle((x0, y0, x0 + BG_BLOCK - 1, y0 + BG_BLOCK - 1), fill=colour)
    return image


def silhouette() -> Image.Image:
    mask = Image.new("L", (GRID, GRID), 0)
    draw = ImageDraw.Draw(mask)

    # Tail: two thick lobes sweeping up and out from a short stem.
    draw.line([(32, 21), (23, 3)], fill=255, width=11)
    draw.line([(32, 21), (41, 3)], fill=255, width=11)
    draw.rectangle((29, 12, 35, 23), fill=255)

    # Dome: widest at eye level, tapering into the jaw.
    draw.ellipse((13, 18, 51, 52), fill=255)
    draw.polygon([(15, 42), (49, 42), (47, 55), (41, 59), (32, 61), (23, 59), (17, 55)], fill=255)

    # Pectoral fins.
    draw.polygon([(15, 43), (6, 52), (1, 58), (8, 58), (14, 53), (19, 46)], fill=255)
    draw.polygon([(49, 43), (58, 52), (63, 58), (56, 58), (50, 53), (45, 46)], fill=255)

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

    # Brow: one continuous line from each cheek up over the crown.
    draw.line(
        [(12, 31), (15, 26), (20, 22), (26, 20), (32, 19), (38, 20), (44, 22), (49, 26), (52, 31)],
        fill=WHITE,
        width=2,
    )

    # Eyes: blue wedges tucked under the brow, wide at the outer edge.
    draw.polygon([(15, 34), (23, 31), (24, 37), (20, 43), (15, 43)], fill=EYE)
    draw.polygon([(49, 34), (41, 31), (40, 37), (44, 43), (49, 43)], fill=EYE)
    draw.rectangle((18, 34, 20, 35), fill=WHITE)
    draw.rectangle((44, 34, 46, 35), fill=WHITE)

    # Mouth: shallow trough across the face.
    draw.line([(16, 44), (23, 46), (41, 46), (48, 44)], fill=WHITE, width=2)

    # Baleen: thick plates hanging from the mouth into the jaw.
    for x in range(19, 46, 4):
        draw.line([(x, 47), (x, 56)], fill=WHITE, width=2)
    draw.arc((16, 46, 48, 65), start=200, end=340, fill=WHITE, width=2)

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
