#!/usr/bin/env python3
"""Apply a square source image as the Whaly launcher icon.

Usage: python branding/apply_icon.py path\\to\\icon.png

Writes every mipmap density, the adaptive-icon art, the monochrome silhouette,
the docs icon and keeps the source in branding/.
"""
from __future__ import annotations

import os
import shutil
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

LEGACY_SIZES = {
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


def circular(image: Image.Image) -> Image.Image:
    size = image.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    rounded = image.convert("RGBA")
    rounded.putalpha(mask)
    return rounded


def monochrome(image: Image.Image, size: int = 432) -> Image.Image:
    """White silhouette with alpha taken from darkness, for themed icons."""
    grey = image.convert("L").resize((size, size), Image.NEAREST)
    alpha = grey.point(lambda value: 255 - value)
    white = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    white.putalpha(alpha)
    return white


def write(image: Image.Image, path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, "PNG", optimize=True)
    print("  ", os.path.relpath(path, ROOT))


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: apply_icon.py <source image>")
        return 2
    source_path = sys.argv[1]
    if not os.path.exists(source_path):
        print("missing source:", source_path)
        return 1

    art = square(Image.open(source_path).convert("RGBA"))
    shutil.copyfile(source_path, os.path.join(ROOT, "branding", "whaly-icon-source.png"))

    print("legacy mipmaps")
    for folder, size in LEGACY_SIZES.items():
        scaled = art.resize((size, size), Image.NEAREST)
        write(scaled, os.path.join(RES, folder, "ic_launcher.png"))
        write(circular(scaled), os.path.join(RES, folder, "ic_launcher_round.png"))

    print("adaptive art")
    write(art.resize((512, 512), Image.NEAREST), os.path.join(RES, "drawable-nodpi", "ic_launcher_art.png"))
    write(monochrome(art), os.path.join(RES, "drawable-nodpi", "ic_launcher_monochrome_art.png"))

    print("docs")
    write(art.resize((512, 512), Image.NEAREST), os.path.join(ROOT, "docs", "assets", "whaly-icon.png"))

    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
