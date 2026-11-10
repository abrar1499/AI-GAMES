"""Builds the traffic for the game in Blender: a sedan car and a truck-art mini-bus.

Run with Blender's Python module (pip install bpy):
    python make_vehicles.py TEXTURE_DIR OUT_DIR [PREVIEW_DIR [HDRI]]
Writes OUT_DIR/car.glb and OUT_DIR/bus.glb. TEXTURE_DIR comes from truck_art.py.

Blender axes: +Y is the front, +Z is up (the glTF exporter converts to Godot's -Z forward).
The car's body colour is the material named "CarPaint", so the game can recolour each car.
"""
import sys
from pathlib import Path

import bpy  # noqa: F401  (must come first: it makes bmesh and mathutils importable)

sys.path.insert(0, str(Path(__file__).parent))
import blender_kit as kit
from blender_kit import M, box, hexa, tube, panel, wheel, join, material

TEX, OUT = sys.argv[1], Path(sys.argv[2])
PREVIEW = Path(sys.argv[3]) if len(sys.argv) > 3 else None
HDRI = sys.argv[4] if len(sys.argv) > 4 else None


def build_car():
    kit.reset(TEX)
    paint = material("CarPaint", (0.75, 0.76, 0.78), 0.6, 0.25, coat=1.0)
    trim = material("BlackTrim", (0.02, 0.02, 0.025), 0.0, 0.5)
    parts = []
    # Lower body with rounded edges: hood lower than the boot line, slightly tapered nose.
    parts.append(hexa("Body", [
        (-0.83, -1.92, 0.3), (0.83, -1.92, 0.3), (0.80, 1.95, 0.3), (-0.80, 1.95, 0.3),
        (-0.83, -1.88, 0.82), (0.83, -1.88, 0.82), (0.78, 1.9, 0.74), (-0.78, 1.9, 0.74)], paint, 0.12))
    # Cabin (greenhouse), narrower at the roof.
    parts.append(hexa("Cabin", [
        (-0.8, -1.08, 0.78), (0.8, -1.08, 0.78), (0.8, 0.7, 0.76), (-0.8, 0.7, 0.76),
        (-0.68, -0.78, 1.4), (0.68, -0.78, 1.4), (0.68, 0.22, 1.4), (-0.68, 0.22, 1.4)], paint, 0.07))

    # Windows sit just outside the cabin faces.
    def side_x(z):  # cabin side surface x at height z
        return 0.8 - 0.12 * (z - 0.78) / 0.62 + 0.008

    def y_span(z):
        t = (z - 0.78) / 0.62
        return -1.08 + 0.3 * t, 0.7 - 0.48 * t

    zb, zt = 0.86, 1.34
    for s in (-1, 1):
        (yb0, yb1), (yt0, yt1) = y_span(zb), y_span(zt)
        xb, xt = s * side_x(zb), s * side_x(zt)
        c = [(xb, yb0 + 0.1, zb), (xb, yb1 - 0.1, zb), (xt, yt1 - 0.08, zt), (xt, yt0 + 0.08, zt)]
        if s < 0:
            c = [c[1], c[0], c[3], c[2]]
        parts.append(panel(f"SideGlass{s}", c, M["glass"]))
        parts.append(box(f"BPillar{s}", (s * side_x(1.1) + s * 0.004, -0.2, 1.1), (0.012, 0.08, 0.5), trim))
        parts.append(box(f"Mirror{s}", (s * 0.9, 0.62, 0.92), (0.14, 0.06, 0.1), trim, 0.02))
        parts.append(box(f"DoorLine{s}", (s * 0.835, -0.25, 0.55), (0.004, 0.01, 0.42), trim))
        parts.append(box(f"Handle{s}", (s * 0.838, 0.15, 0.7), (0.01, 0.14, 0.025), M["chrome"]))
        parts.append(box(f"Handle2{s}", (s * 0.838, -0.65, 0.7), (0.01, 0.14, 0.025), M["chrome"]))
    parts.append(panel("Windshield", [(0.72, 0.72, 0.8), (-0.72, 0.72, 0.8), (-0.64, 0.24, 1.38), (0.64, 0.24, 1.38)], M["glass"]))
    parts.append(panel("RearGlass", [(-0.72, -1.1, 0.82), (0.72, -1.1, 0.82), (0.64, -0.8, 1.38), (-0.64, -0.8, 1.38)], M["glass"]))

    # Front: grille, headlights, bumper. Rear: tail lights, plate, bumper, boot line.
    parts.append(box("Grille", (0, 1.955, 0.58), (0.7, 0.03, 0.16), trim, 0.01))
    parts.append(box("GrilleChrome", (0, 1.965, 0.66), (0.72, 0.02, 0.025), M["chrome"]))
    for s in (-1, 1):
        parts.append(box(f"Head{s}", (s * 0.6, 1.93, 0.64), (0.34, 0.05, 0.13), M["head"], 0.02))
        parts.append(box(f"Tail{s}", (s * 0.6, -1.93, 0.7), (0.36, 0.04, 0.14), M["tail"], 0.02))
        parts.append(box(f"Indicator{s}", (s * 0.6, -1.935, 0.6), (0.36, 0.03, 0.04), material("Amber", (1.0, 0.45, 0.0), 0.0, 0.3)))
    parts.append(box("FrontBumper", (0, 1.96, 0.4), (1.66, 0.1, 0.18), trim, 0.04))
    parts.append(box("RearBumper", (0, -1.96, 0.42), (1.68, 0.1, 0.2), trim, 0.04))
    parts.append(box("RearPlate", (0, -1.95, 0.6), (0.52, 0.02, 0.12), M["plate"], 0.005))
    parts.append(box("FrontPlate", (0, 2.0, 0.42), (0.52, 0.02, 0.12), M["plate"], 0.005))
    parts.append(box("BootLine", (0, -1.4, 0.825), (1.5, 0.01, 0.004), trim))
    parts.append(box("Underbody", (0, 0, 0.26), (1.5, 3.4, 0.1), trim))

    body = join(parts, "CarBody")
    wheels = [wheel(f"Wheel{i}", (sx * 0.73, sy * 1.25, 0.31), 0.31, 0.2)
              for i, (sx, sy) in enumerate([(-1, 1), (1, 1), (-1, -1), (1, -1)])]
    kit.export([body, *wheels], OUT / "car.glb")
    if PREVIEW:
        kit.preview(PREVIEW / "car.png", HDRI, views=((4.0, -5.5, 2.4), (1.2, -6.5, 2.2)), target=(0, 0, 0.7))


def build_bus():
    kit.reset(TEX)
    paint = material("BusPaint", (0.85, 0.85, 0.82), 0.3, 0.3, coat=0.6)
    stripe = material("BusStripe", (0.75, 0.05, 0.08), 0.3, 0.3, coat=0.6)
    tarp = [material(f"Tarp{i}", c, 0.0, 0.8) for i, c in enumerate(
        [(0.05, 0.25, 0.6), (0.6, 0.35, 0.05), (0.1, 0.4, 0.15), (0.5, 0.05, 0.05)])]
    L, Wd = 3.0, 1.05   # half length, half width
    parts = [box("Body", (0, 0, 1.55), (Wd * 2, L * 2, 2.1), paint, 0.12),
             box("Skirt", (0, 0, 0.48), (Wd * 2 - 0.1, L * 2 - 0.3, 0.2), M["steel"])]
    # Window band with pillars on both sides.
    for s in (-1, 1):
        x = s * (Wd + 0.004)
        c = [(x, -L + 0.4, 1.62), (x, L - 0.6, 1.62), (x, L - 0.6, 2.28), (x, -L + 0.4, 2.28)]
        if s < 0:
            c = [c[1], c[0], c[3], c[2]]
        parts.append(panel(f"Windows{s}", c, M["glass"]))
        y = -L + 0.4
        while y < L - 0.5:
            parts.append(box(f"Pillar{s}_{y:.1f}", (s * (Wd + 0.01), y, 1.95), (0.02, 0.08, 0.7), paint))
            y += 0.9
        # Truck-art strip and red stripe along the side.
        c = [(x * 1.001, -L + 0.15, 0.75), (x * 1.001, L - 0.15, 0.75), (x * 1.001, L - 0.15, 1.3), (x * 1.001, -L + 0.15, 1.3)]
        if s < 0:
            c = [c[1], c[0], c[3], c[2]]
        parts.append(panel(f"SideArt{s}", c, M["art_side"], repeat=(1.4, 1.0)))
        parts.append(box(f"Stripe{s}", (s * (Wd + 0.006), 0, 1.45), (0.012, L * 2 - 0.3, 0.12), stripe))
        parts.append(box(f"Mirror{s}", (s * (Wd + 0.2), L - 0.1, 2.0), (0.06, 0.08, 0.3), M["steel"], 0.02))
    # Front and back.
    parts.append(panel("Windshield", [(Wd - 0.08, L + 0.004, 1.4), (-Wd + 0.08, L + 0.004, 1.4), (-Wd + 0.08, L + 0.004, 2.4), (Wd - 0.08, L + 0.004, 2.4)], M["glass"]))
    parts.append(panel("RearWindow", [(-Wd + 0.15, -L - 0.004, 1.85), (Wd - 0.15, -L - 0.004, 1.85), (Wd - 0.15, -L - 0.004, 2.35), (-Wd + 0.15, -L - 0.004, 2.35)], M["glass"]))
    parts.append(panel("RearArt", [(-Wd + 0.08, -L - 0.006, 0.8), (Wd - 0.08, -L - 0.006, 0.8), (Wd - 0.08, -L - 0.006, 1.72), (-Wd + 0.08, -L - 0.006, 1.72)], M["art_rear"]))
    for s in (-1, 1):
        parts.append(box(f"Tail{s}", (s * 0.8, -L - 0.02, 0.62), (0.3, 0.04, 0.14), M["tail"], 0.02))
        parts.append(box(f"Head{s}", (s * 0.75, L + 0.02, 0.8), (0.3, 0.04, 0.16), M["head"], 0.02))
        # Ladder to the roof rack on the back.
        parts.append(tube(f"Ladder{s}", (s * 0.75 - 0.25 * s, -L - 0.08, 0.9), (s * 0.75 - 0.25 * s, -L - 0.08, 2.75), 0.02, M["chrome"]))
    for z in (1.0, 1.3, 1.6, 1.9, 2.2, 2.5):
        parts.append(tube(f"Rung{z}", (-0.5, -L - 0.08, z), (0.5, -L - 0.08, z), 0.015, M["chrome"]))
    parts.append(box("RearPlate", (0, -L - 0.02, 0.55), (0.52, 0.02, 0.12), M["plate"], 0.005))
    parts.append(box("RearBumper", (0, -L - 0.05, 0.42), (Wd * 2, 0.12, 0.16), M["chrome"], 0.03))
    parts.append(box("FrontBumper", (0, L + 0.05, 0.45), (Wd * 2, 0.12, 0.2), M["chrome"], 0.03))
    # Roof rack with bundles of luggage under tarps.
    for s in (-1, 1):
        parts.append(tube(f"Rail{s}", (s * 0.95, -L + 0.2, 2.78), (s * 0.95, L - 0.4, 2.78), 0.025, M["chrome"]))
    y = -L + 0.4
    while y < L - 0.6:
        parts.append(tube(f"RackBar{y:.1f}", (-0.95, y, 2.78), (0.95, y, 2.78), 0.02, M["chrome"]))
        y += 0.6
    for i, (bx, by, sx, sy, sz) in enumerate([(-0.4, -1.8, 0.8, 1.1, 0.45), (0.35, -1.7, 0.7, 0.9, 0.35),
                                               (0.0, -0.4, 1.4, 1.2, 0.55), (-0.3, 1.0, 0.9, 0.8, 0.4), (0.45, 1.2, 0.6, 0.7, 0.3)]):
        parts.append(box(f"Luggage{i}", (bx, by, 2.62 + sz / 2), (sx, sy, sz), tarp[i % len(tarp)], 0.08))
    body = join(parts, "BusBody")
    wheels = [wheel(f"Wheel{i}", (sx * 0.86, sy * 2.0, 0.45), 0.45, 0.3)
              for i, (sx, sy) in enumerate([(-1, 1), (1, 1), (-1, -1), (1, -1)])]
    kit.export([body, *wheels], OUT / "bus.glb")
    if PREVIEW:
        kit.preview(PREVIEW / "bus.png", HDRI, views=((6.0, -8.5, 3.4), (1.5, -9.5, 2.8)), target=(0, 0, 1.4))


build_car()
build_bus()
