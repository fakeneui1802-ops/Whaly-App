#!/usr/bin/env python3
"""Slice a real source image into every Whaly icon slot.

The source is pixel art: 1024x1024 with a 64x64 grid of 16px cells. Sampling the
centre of each cell rebuilds the art exactly and drops the JPEG ringing that would
otherwise show up as grey fringes once it is scaled down.

Usage:
    python branding/from_image.py <source image> [grid]

grid defaults to 64. Pass 0 to skip sampling and use the image as-is.
"""
from __future__ import annotations

import os
import shutil
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

LEGACY = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def square(image: Image.Image) -> Image.Image:
    side = min(image.size)
    left = (image.width - side) // 2
    top = (image.height - side) // 2
    return image.crop((left, top, left + side, top + side))


def sample(image: Image.Image, grid: int) -> Image.Image:
    """Rebuild the art by reading one pixel from the middle of every grid cell."""
    if grid <= 0:
        return image
    size = image.size[0]
    cell = size / grid
    out = Image.new("RGBA", (grid, grid))
    pixels = out.load()
    for y in range(grid):
        for x in range(grid):
            sx = min(size - 1, int(x * cell + cell / 2))
            sy = min(size - 1, int(y * cell + cell / 2))
            pixels[x, y] = image.getpixel((sx, sy))
    return out


def circular(image: Image.Image) -> Image.Image:
    size = image.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    out = image.convert("RGBA")
    out.putalpha(mask)
    return out


def monochrome(image: Image.Image, size: int = 432) -> Image.Image:
    """White silhouette; alpha follows brightness so the whale stays readable."""
    grey = image.convert("L").resize((size, size), Image.NEAREST)
    alpha = grey.point(lambda v: 255 if v > 140 else 0)
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def write(image: Image.Image, path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, "PNG", optimize=True)
    print("  ", os.path.relpath(path, ROOT))


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: from_image.py <source image> [grid]")
        return 2
    source = sys.argv[1]
    grid = int(sys.argv[2]) if len(sys.argv) > 2 else 64
    if not os.path.exists(source):
        print("missing source:", source)
        return 1

    art = sample(square(Image.open(source).convert("RGBA")), grid)
    art = art.resize((512, 512), Image.NEAREST)

    res = os.path.join(ROOT, "app", "src", "main", "res")
    write(art, os.path.join(ROOT, "branding", "whaly-icon-source.png"))

    for folder, size in LEGACY.items():
        scaled = art.resize((size, size), Image.NEAREST)
        write(scaled, os.path.join(res, folder, "ic_launcher.png"))
        write(circular(scaled), os.path.join(res, folder, "ic_launcher_round.png"))

    write(art, os.path.join(res, "drawable-nodpi", "ic_launcher_art.png"))
    write(monochrome(art), os.path.join(res, "drawable-nodpi", "ic_launcher_monochrome_art.png"))

    docs = os.path.join(ROOT, "docs", "assets")
    write(art, os.path.join(docs, "whaly-icon.png"))
    write(art.resize((64, 64), Image.NEAREST), os.path.join(docs, "favicon.png"))

    shutil.copyfile(source, os.path.join(ROOT, "branding", "whaly-icon-original" + os.path.splitext(source)[1]))
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
