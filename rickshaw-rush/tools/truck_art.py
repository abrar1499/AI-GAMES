"""Generates truck-art style panel textures (bright borders, chevrons, flowers, peacock-eye dots)
in the style painted on Pakistani rickshaws and trucks. Pure Pillow, no input files.

Usage: python truck_art.py OUT_DIR
"""
import math
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw

PALETTE = [
    (230, 30, 60), (255, 200, 20), (20, 160, 90), (30, 110, 220), (250, 120, 20),
    (200, 40, 160), (255, 255, 255), (20, 20, 30), (0, 190, 200),
]


def flower(d: ImageDraw.ImageDraw, cx: float, cy: float, r: float, petals: int, c1, c2, c3) -> None:
    for i in range(petals):
        a = i / petals * math.tau
        px, py = cx + math.cos(a) * r * 0.55, cy + math.sin(a) * r * 0.55
        d.ellipse([px - r * 0.45, py - r * 0.45, px + r * 0.45, py + r * 0.45], fill=c1, outline=(20, 20, 30), width=2)
    d.ellipse([cx - r * 0.42, cy - r * 0.42, cx + r * 0.42, cy + r * 0.42], fill=c2, outline=(20, 20, 30), width=2)
    d.ellipse([cx - r * 0.18, cy - r * 0.18, cx + r * 0.18, cy + r * 0.18], fill=c3)


def chevrons(d: ImageDraw.ImageDraw, x0: int, y0: int, x1: int, y1: int, colors) -> None:
    h = y1 - y0
    step = h
    x = x0 - h
    i = 0
    while x < x1:
        c = colors[i % len(colors)]
        d.polygon([(x, y1), (x + step / 2, y0), (x + step, y0), (x + step / 2, y1)], fill=c)
        x += step / 2
        i += 1


def border(d: ImageDraw.ImageDraw, w: int, h: int, rng: random.Random) -> int:
    inset = 0
    for _ in range(3):
        c = rng.choice(PALETTE)
        t = rng.randint(10, 18)
        d.rectangle([inset, inset, w - 1 - inset, h - 1 - inset], outline=c, width=t)
        inset += t
    return inset


def rear_panel(path: Path, seed: int) -> None:
    """Wide rear panel: chevron bands top and bottom, flowers and dots in the middle."""
    rng = random.Random(seed)
    w, h = 1024, 384
    img = Image.new("RGB", (w, h), (20, 120, 70))
    d = ImageDraw.Draw(img)
    inset = border(d, w, h, rng)
    band = 44
    chevrons(d, inset, inset, w - inset, inset + band, [(230, 30, 60), (255, 200, 20), (30, 110, 220), (255, 255, 255)])
    chevrons(d, inset, h - inset - band, w - inset, h - inset, [(255, 200, 20), (200, 40, 160), (20, 160, 90), (255, 255, 255)])
    d.rectangle([inset, inset + band, w - inset, h - inset - band], fill=(250, 240, 220))
    mid_y = h / 2
    for i, cx in enumerate(range(130, w - 100, 190)):
        flower(d, cx, mid_y, 62, 8, PALETTE[(i * 2) % 6], PALETTE[(i * 2 + 1) % 6], (255, 255, 255))
    for cx in range(225, w - 150, 190):
        for dy in (-55, 55):
            r = 16
            d.ellipse([cx - r, mid_y + dy - r, cx + r, mid_y + dy + r], fill=(30, 110, 220), outline=(20, 20, 30), width=2)
            d.ellipse([cx - 7, mid_y + dy - 7, cx + 7, mid_y + dy + 7], fill=(255, 200, 20))
    img.save(path, quality=90)


def side_strip(path: Path, seed: int) -> None:
    """Long thin strip for the body sides and canopy edge."""
    rng = random.Random(seed)
    w, h = 1024, 128
    img = Image.new("RGB", (w, h), (255, 255, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, w, 14], fill=(230, 30, 60))
    d.rectangle([0, h - 14, w, h], fill=(230, 30, 60))
    chevrons(d, 0, 14, w, 44, [(255, 200, 20), (20, 160, 90), (30, 110, 220)])
    for i, cx in enumerate(range(40, w, 80)):
        flower(d, cx, 86, 26, 6, rng.choice(PALETTE[:6]), (255, 200, 20), (230, 30, 60))
    img.save(path, quality=90)


if __name__ == "__main__":
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    rear_panel(out / "truckart_rear.png", 7)
    side_strip(out / "truckart_side.png", 11)
    print("textures written to", out)
