"""Makes the trooper's textures from the two pictures inside the model.

    python3 tools/build_hero_textures.py assets/soldier.glb assets/hero

The model comes with a colour picture and a normal map, both 1024 pixels
and saved as JPEG, and nothing to say what any of it is made of, so the
whole figure renders as if it were chalk. This writes three pictures, twice
the size, that between them describe a soldier in painted armour:

    albedo.jpg   the colours, sharpened, with the weave of the cloth and the
                 grain of the paint drawn in, and the paint a little less
                 bright so that light has somewhere to go
    normal.jpg   the surface's bumps: the model's own, plus fine ones for
                 each material (cloth weave, leather grain, brushed metal)
    orm.jpg      what it is made of, one channel each: red is how shadowed
                 the creases are, green how rough the surface is, blue how
                 much it is bare metal

What a pixel is made of is told from its colour: cream and red are paint,
green is cloth, near-black is the rubber suit under the armour, blue-grey
is metal, dark brown is leather, and the dark chips in the paint are bare
metal showing through.

Needs numpy and Pillow.
"""

import io
import json
import os
import struct
import sys

import numpy as np
from PIL import Image

SIZE = 2048


# ---------------------------------------------------------------- reading the model

def images_in(glb_path):
    """The pictures packed inside a .glb, by name."""
    with open(glb_path, "rb") as f:
        magic, _, total = struct.unpack("<III", f.read(12))
        assert magic == 0x46546C67, "not a .glb file"
        chunks = {}
        while f.tell() < total:
            length, kind = struct.unpack("<II", f.read(8))
            chunks[kind] = f.read(length)
    doc = json.loads(chunks[0x4E4F534A])
    binary = chunks[0x004E4942]
    out = {}
    for n, image in enumerate(doc.get("images", [])):
        view = doc["bufferViews"][image["bufferView"]]
        start = view.get("byteOffset", 0)
        out[image.get("name", "image%d" % n)] = Image.open(io.BytesIO(binary[start:start + view["byteLength"]]))
    material = doc["materials"][0]
    colour = doc["textures"][material["pbrMetallicRoughness"]["baseColorTexture"]["index"]]["source"]
    normal = doc["textures"][material["normalTexture"]["index"]]["source"]
    names = list(out)
    return out[names[colour]].convert("RGB"), out[names[normal]].convert("RGB")


# ---------------------------------------------------------------- small tools

def blurred(a, radius):
    """A soft blur, done in one go for the whole picture."""
    if radius <= 0:
        return a
    fy = np.fft.fftfreq(a.shape[0])[:, None]
    fx = np.fft.rfftfreq(a.shape[1])[None, :]
    falloff = np.exp(-2.0 * (np.pi * radius) ** 2 * (fx ** 2 + fy ** 2))
    return np.fft.irfft2(np.fft.rfft2(a) * falloff, s=a.shape)


def grown(picture, size):
    return np.asarray(picture.resize((size, size), Image.LANCZOS), dtype=np.float64) / 255.0


def hsv(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    top = rgb.max(axis=-1)
    low = rgb.min(axis=-1)
    spread = top - low
    hue = np.zeros_like(top)
    safe = np.maximum(spread, 1e-9)
    hue = np.where(top == r, ((g - b) / safe) % 6.0, hue)
    hue = np.where(top == g, (b - r) / safe + 2.0, hue)
    hue = np.where(top == b, (r - g) / safe + 4.0, hue)
    return hue * 60.0, spread / np.maximum(top, 1e-9), top


def between(x, lo, hi, soft):
    """1 inside lo..hi, fading to 0 over `soft` either side."""
    return np.clip((x - lo) / soft + 1.0, 0.0, 1.0) * np.clip((hi - x) / soft + 1.0, 0.0, 1.0)


def noise(rng, size, grain):
    """Random specks, each about `grain` pixels across, from -1 to 1."""
    specks = blurred(rng.standard_normal((size, size)), grain)
    return specks / max(np.abs(specks).max(), 1e-9)


def slopes(height):
    """How steeply a height picture climbs to the right and up the picture."""
    right = (np.roll(height, -1, axis=1) - np.roll(height, 1, axis=1)) * 0.5
    up = (np.roll(height, 1, axis=0) - np.roll(height, -1, axis=0)) * 0.5
    return right, up


def save(array, path, quality):
    Image.fromarray(np.uint8(np.clip(array, 0.0, 1.0) * 255.0 + 0.5)).save(path, quality=quality, subsampling=0)
    return os.path.getsize(path) / 1e6


# ---------------------------------------------------------------- the work

def main():
    source, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    colour_small, normal_small = images_in(source)
    colour = grown(colour_small, SIZE)
    normal = grown(normal_small, SIZE) * 2.0 - 1.0
    rng = np.random.default_rng(7)

    # What each pixel is made of, from its colour. A little blur first, so
    # the JPEG's blocks do not become edges.
    hue, sat, val = hsv(blurred(colour.reshape(-1, 3).reshape(SIZE, SIZE, 3).transpose(2, 0, 1), 0.0).transpose(1, 2, 0))
    warm = between(hue, 14.0, 48.0, 8.0)
    paint = warm * between(sat, 0.12, 0.62, 0.08) * between(val, 0.56, 1.0, 0.08)
    red = (between(hue, 0.0, 10.0, 6.0) + between(hue, 348.0, 360.0, 6.0)) * between(sat, 0.5, 1.0, 0.12) * between(val, 0.28, 1.0, 0.08)
    cloth = between(hue, 58.0, 150.0, 14.0) * between(sat, 0.10, 1.0, 0.05) * between(val, 0.13, 0.52, 0.05)
    metal = between(hue, 165.0, 235.0, 15.0) * between(sat, 0.07, 0.45, 0.04) * between(val, 0.22, 0.80, 0.06)
    rubber = between(val, 0.0, 0.13, 0.05)
    leather = warm * between(val, 0.14, 0.50, 0.06) * between(sat, 0.15, 1.0, 0.08)
    # Dark specks inside painted areas are chips, down to the metal.
    painted_near = blurred(np.clip(paint + red, 0, 1), 5.0)
    chips = np.clip(painted_near * 2.2, 0, 1) * between(val, 0.0, 0.50, 0.08) * (1.0 - cloth) * (1.0 - np.clip(metal, 0, 1))
    leather = leather * (1.0 - np.clip(chips * 1.5, 0, 1))
    painted = np.clip(paint + red, 0.0, 1.0)

    # --- bumps ------------------------------------------------------------
    # The model's own normal map gives the shape. Fine bumps for each
    # material are added as heights and turned into slopes.
    y, x = np.mgrid[0:SIZE, 0:SIZE].astype(np.float64)
    weave = np.sin((x + y) * (2 * np.pi / 7.0)) * np.sin((x - y) * (2 * np.pi / 7.0))
    dimples = np.sin(x * (2 * np.pi / 5.0)) * np.sin(y * (2 * np.pi / 5.0))
    brushed = blurred(rng.standard_normal((SIZE, SIZE)), 0.0)
    brushed = np.fft.irfft2(np.fft.rfft2(brushed) * np.exp(-2.0 * (np.pi ** 2) * ((6.0 * np.fft.rfftfreq(SIZE)[None, :]) ** 2 + (0.6 * np.fft.fftfreq(SIZE)[:, None]) ** 2)), s=(SIZE, SIZE))
    brushed /= np.abs(brushed).max()
    orange_peel = noise(rng, SIZE, 1.3)
    grain = noise(rng, SIZE, 2.4)
    scuffs = noise(rng, SIZE, 9.0)

    height = (painted * (orange_peel * 0.38 + scuffs * 0.5)
              + cloth * weave * 1.5
              + rubber * dimples * 0.55
              + leather * grain * 1.5
              + np.clip(metal + chips, 0, 1) * brushed * 0.9
              - chips * 1.2)
    fine_right, fine_up = slopes(height)
    nx = normal[..., 0] - fine_right * 0.85
    ny = normal[..., 1] - fine_up * 0.85
    nz = np.maximum(normal[..., 2], 0.25)
    length = np.sqrt(nx ** 2 + ny ** 2 + nz ** 2)
    bumps = np.stack([nx / length, ny / length, nz / length], axis=-1)

    # --- creases ----------------------------------------------------------
    # Where the surface folds inwards the normals lean together; that is
    # where dirt gathers and light does not reach. Where it folds outwards
    # paint wears thin.
    lean_right, _ = slopes(normal[..., 0])
    _, lean_up = slopes(normal[..., 1])
    bulge = blurred(lean_right + lean_up, 1.6)
    bulge /= max(np.percentile(np.abs(bulge), 99.5), 1e-9)
    crease = np.clip(-bulge, 0.0, 1.0)
    ridge = np.clip(bulge, 0.0, 1.0)
    shadow = 1.0 - np.clip(blurred(crease, 2.5) * 1.5, 0.0, 0.75)

    # --- colours ----------------------------------------------------------
    soft = np.stack([blurred(colour[..., c], 1.6) for c in range(3)], axis=-1)
    sharp = np.clip(colour + (colour - soft) * 0.85, 0.0, 1.0)
    # Paint as bright as this has no room left for sunlight on top of it.
    sharp = sharp * (1.0 - 0.08 * painted[..., None])
    detail = (1.0
              + painted * (orange_peel * 0.022 + scuffs * 0.03)
              + cloth * weave * 0.07
              + leather * grain * 0.05
              + rubber * dimples * 0.03
              + np.clip(metal, 0, 1) * brushed * 0.05)
    albedo = sharp * detail[..., None]
    albedo = albedo * (0.86 + 0.14 * shadow[..., None])
    # Worn edges: a touch lighter on the ridges of painted parts.
    albedo = albedo + (ridge * painted * 0.05)[..., None]
    # A little more colour everywhere.
    grey = albedo.mean(axis=-1, keepdims=True)
    albedo = np.clip(grey + (albedo - grey) * 1.10, 0.0, 1.0)

    # --- what it is made of -------------------------------------------------
    rough = np.full((SIZE, SIZE), 0.62)
    for mask, value in [(rubber, 0.58), (leather, 0.70), (cloth, 0.93), (painted, 0.45), (np.clip(metal, 0, 1), 0.30), (np.clip(chips, 0, 1), 0.34)]:
        rough = rough * (1.0 - mask) + value * mask
    rough = np.clip(rough + scuffs * 0.07 * painted + crease * 0.18, 0.05, 1.0)
    metallic = np.clip(np.clip(metal, 0, 1) * 0.9 + np.clip(chips, 0, 1) * 0.8, 0.0, 1.0)

    sizes = [
        save(albedo, os.path.join(out, "albedo.jpg"), 93),
        save(bumps * 0.5 + 0.5, os.path.join(out, "normal.jpg"), 95),
        save(np.stack([shadow, rough, metallic], axis=-1), os.path.join(out, "orm.jpg"), 90),
    ]
    print("albedo %.1f MB, normal %.1f MB, orm %.1f MB, each %d pixels square" % (sizes[0], sizes[1], sizes[2], SIZE))
    share = lambda m: 100.0 * float(np.clip(m, 0, 1).mean())
    print("made of: paint %.0f%%, cloth %.0f%%, rubber %.0f%%, leather %.0f%%, metal %.0f%%, chips %.0f%%" % (
        share(painted), share(cloth), share(rubber), share(leather), share(metal), share(chips)))


if __name__ == "__main__":
    main()
