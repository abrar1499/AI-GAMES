"""Builds a Pakistani CNG auto-rickshaw in Blender and exports it as glTF binary for the game.

Run with Blender's Python module (pip install bpy):
    python make_rickshaw.py TEXTURE_DIR OUT.glb [PREVIEW.png [HDRI]]
(TEXTURE_DIR comes from truck_art.py.)

Blender axes: +Y is the front of the rickshaw, +Z is up. The glTF exporter turns this into
Godot's convention (front = -Z, up = +Y). Wheels are separate objects named Wheel_F, Wheel_RL
and Wheel_RR with their origin at the axle, so the game can spin them around X.
"""
import math
import sys
from pathlib import Path

import bpy  # noqa: F401  (must come first: it makes bmesh and mathutils importable)
import bmesh

sys.path.insert(0, str(Path(__file__).parent))
import blender_kit as kit
from blender_kit import M, TASSELS, box, hexa, tube, panel, wheel, join, mesh_object, bevel, smooth

kit.reset(sys.argv[1])
OUT = sys.argv[2]
PREVIEW = sys.argv[3] if len(sys.argv) > 3 else None
HDRI = sys.argv[4] if len(sys.argv) > 4 else None

# ---------------------------------------------------------------- the rickshaw

parts = []
W = 0.66           # half width of the passenger tub
FLOOR = 0.42

# Chassis floor and the passenger tub (engine sits underneath the rear seat).
parts.append(box("Floor", (0, -0.05, FLOOR - 0.05), (1.24, 2.4, 0.1), M["steel"], 0.02))
parts.append(box("Tub", (0, -0.72, 0.66), (W * 2, 1.12, 0.5), M["paint"], 0.05))
parts.append(box("TubTrim", (0, -0.72, 0.915), (W * 2 + 0.02, 1.14, 0.04), M["chrome"], 0.01))

# Front nose: leans forward, narrower at the bottom, houses the headlight.
parts.append(hexa("Nose", [
    (-0.42, 1.05, FLOOR), (0.42, 1.05, FLOOR), (0.30, 1.38, FLOOR), (-0.30, 1.38, FLOOR),
    (-0.6, 0.86, 1.12), (0.6, 0.86, 1.12), (0.5, 1.13, 1.12), (-0.5, 1.13, 1.12)], M["paint"], 0.07))
parts.append(hexa("NoseStripe", [
    (-0.31, 1.33, 0.70), (0.31, 1.33, 0.70), (0.29, 1.395, 0.70), (-0.29, 1.395, 0.70),
    (-0.37, 1.28, 0.82), (0.37, 1.28, 0.82), (0.35, 1.345, 0.82), (-0.35, 1.345, 0.82)], M["yellow"], 0.01))
# Low side walls of the driver's cabin.
for s in (-1, 1):
    parts.append(box(f"CabinSide{s}", (s * 0.6, 0.5, 0.66), (0.05, 0.8, 0.5), M["paint"], 0.02))
    parts.append(box(f"SideTrim{s}", (s * (W + 0.005), -0.72, 0.56), (0.012, 1.0, 0.16), M["white"], 0.0))

# Windshield with chrome frame.
parts.append(panel("Windshield", [(0.52, 1.0, 1.13), (-0.52, 1.0, 1.13), (-0.5, 0.8, 1.74), (0.5, 0.8, 1.74)], M["glass"]))
for s in (-1, 1):
    parts.append(tube(f"WsFrame{s}", (s * 0.53, 1.0, 1.12), (s * 0.51, 0.79, 1.76), 0.018, M["chrome"]))
parts.append(tube("WsTop", (-0.53, 0.79, 1.76), (0.53, 0.79, 1.76), 0.018, M["chrome"]))
parts.append(tube("WsBottom", (-0.55, 1.0, 1.12), (0.55, 1.0, 1.12), 0.02, M["chrome"]))
parts.append(tube("Wiper", (0.05, 0.99, 1.15), (-0.3, 0.92, 1.38), 0.008, M["steel"], 6))

# Canopy: arched rexine roof with a rolled edge, front lip over the windshield.
bm = bmesh.new()
nx, ny = 14, 8
y0, y1 = -1.28, 0.9
grid = []
for j in range(ny + 1):
    y = y0 + (y1 - y0) * j / ny
    row = []
    for i in range(nx + 1):
        u = -1 + 2 * i / nx
        x = u * 0.76
        z = 1.9 + 0.07 * (1 - u * u) - 0.1 * max(0.0, abs(u) - 0.8) / 0.2
        if j == ny:
            z -= 0.05
        row.append(bm.verts.new((x, y, z)))
    grid.append(row)
for j in range(ny):
    for i in range(nx):
        bm.faces.new([grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i]])
canopy = mesh_object("Canopy", bm, M["rexine"])
mod = canopy.modifiers.new("Thick", "SOLIDIFY")
mod.thickness = 0.04
bevel(canopy, 0.015, 2)
parts.append(smooth(canopy))
# Truck-art strip along both canopy edges and the back.
for s in (-1, 1):
    ya, yb = (y0, y1) if s > 0 else (y1, y0)
    parts.append(panel(f"CanopyArt{s}",
                       [(s * 0.775, ya, 1.76), (s * 0.775, yb, 1.76), (s * 0.775, yb, 1.86), (s * 0.775, ya, 1.86)],
                       M["art_side"]))
parts.append(panel("CanopyArtBack", [(-0.76, y0 - 0.005, 1.76), (0.76, y0 - 0.005, 1.76), (0.76, y0 - 0.005, 1.86), (-0.76, y0 - 0.005, 1.86)], M["art_side"]))
# Tassel fringe hanging from the canopy edges.
k = 0
for s in (-1, 1):
    y = y0 + 0.05
    while y < 0.85:
        parts.append(box(f"Tassel{k}", (s * 0.775, y, 1.715), (0.03, 0.05, 0.09), TASSELS[k % len(TASSELS)]))
        y += 0.09
        k += 1

# Pillars holding the canopy.
for s in (-1, 1):
    parts.append(tube(f"PillarF{s}", (s * 0.6, 0.86, 1.1), (s * 0.66, 0.8, 1.9), 0.022, M["chrome"]))
    parts.append(tube(f"PillarM{s}", (s * W, -0.15, 0.9), (s * 0.7, -0.15, 1.9), 0.022, M["chrome"]))
    parts.append(tube(f"PillarR{s}", (s * W, -1.25, 0.9), (s * 0.7, -1.25, 1.9), 0.022, M["chrome"]))
    parts.append(tube(f"GrabBar{s}", (s * 0.69, -1.2, 1.3), (s * 0.69, -0.2, 1.3), 0.015, M["chrome"]))

# Rear wall of the cabin (rexine) with a small back window, and the passenger bench.
parts.append(box("BackWall", (0, -1.27, 1.42), (W * 2 + 0.06, 0.04, 0.96), M["rexine"], 0.01))
parts.append(panel("BackArt", [(-0.66, -1.295, 0.97), (0.66, -1.295, 0.97), (0.66, -1.295, 1.42), (-0.66, -1.295, 1.42)], M["art_rear"]))
parts.append(box("BackArtFrame", (0, -1.297, 1.435), (1.36, 0.012, 0.03), M["chrome"]))
for s in (-1, 1):
    parts.append(box(f"Reflector{s}", (s * 0.55, -1.3, 1.6), (0.1, 0.02, 0.1), M["tail"], 0.01))
parts.append(panel("BackWindow", [(-0.35, -1.295, 1.5), (0.35, -1.295, 1.5), (0.35, -1.295, 1.72), (-0.35, -1.295, 1.72)], M["glass"]))
parts.append(box("SeatBase", (0, -0.8, 1.0), (1.22, 0.62, 0.14), M["seat"], 0.05))
parts.append(box("SeatBack", (0, -1.17, 1.3), (1.22, 0.14, 0.5), M["seat"], 0.05))
parts.append(box("SeatPiping", (0, -1.1, 1.3), (1.0, 0.012, 0.03), M["white"]))
parts.append(box("DriverSeat", (0, 0.35, 0.92), (0.62, 0.42, 0.12), M["seat"], 0.04))
parts.append(box("DriverBack", (0, 0.13, 1.12), (0.62, 0.1, 0.34), M["seat"], 0.04))

# Handlebar, steering column and front fork.
parts.append(tube("Column", (0, 1.05, 0.95), (0, 0.88, 1.12), 0.025, M["steel"]))
parts.append(tube("Handlebar", (-0.34, 0.86, 1.12), (0.34, 0.86, 1.12), 0.018, M["chrome"]))
for s in (-1, 1):
    parts.append(tube(f"Grip{s}", (s * 0.34, 0.86, 1.12), (s * 0.44, 0.86, 1.12), 0.024, M["rubber"]))
    parts.append(tube(f"Fork{s}", (s * 0.11, 1.18, 0.24), (s * 0.11, 1.12, FLOOR + 0.05), 0.022, M["chrome"]))
    parts.append(tube(f"MirrorArm{s}", (s * 0.6, 0.95, 1.12), (s * 0.72, 0.98, 1.3), 0.01, M["chrome"], 6))
    parts.append(box(f"Mirror{s}", (s * 0.75, 0.98, 1.34), (0.12, 0.03, 0.08), M["chrome"], 0.015))

# Headlight on the nose.
bm = bmesh.new()
bmesh.ops.create_cone(bm, cap_ends=True, segments=24, radius1=0.1, radius2=0.11, depth=0.08)
hl = mesh_object("HeadRing", bm, M["chrome"])
hl.rotation_euler = (math.radians(-70), 0, 0)
hl.location = (0, 1.27, 0.98)
parts.append(smooth(hl))
bm = bmesh.new()
bmesh.ops.create_cone(bm, cap_ends=True, segments=24, radius1=0.085, radius2=0.085, depth=0.09)
lens = mesh_object("HeadLens", bm, M["head"])
lens.rotation_euler = (math.radians(-70), 0, 0)
lens.location = (0, 1.275, 0.985)
parts.append(lens)

# Rear: truck-art panel, tail lights, number plate, bumper, exhaust.
parts.append(panel("RearArt", [(-W + 0.02, -1.285, 0.45), (W - 0.02, -1.285, 0.45), (W - 0.02, -1.285, 0.88), (-W + 0.02, -1.285, 0.88)], M["art_rear"]))
for s in (-1, 1):
    parts.append(box(f"Tail{s}", (s * 0.52, -1.29, 0.95), (0.16, 0.04, 0.08), M["tail"], 0.01))
    parts.append(panel(f"SideArt{s}", [(s * (W + 0.006), y, z) for y, z in
                                         ([(-1.24, 0.48), (-0.2, 0.48), (-0.2, 0.86), (-1.24, 0.86)] if s > 0 else
                                          [(-0.2, 0.48), (-1.24, 0.48), (-1.24, 0.86), (-0.2, 0.86)])], M["art_side"]))
parts.append(box("Plate", (0, -1.3, 0.38), (0.34, 0.02, 0.12), M["plate"], 0.005))
parts.append(box("Bumper", (0, -1.33, 0.33), (1.3, 0.06, 0.05), M["chrome"], 0.02))
parts.append(tube("Exhaust", (0.35, -1.0, 0.3), (0.35, -1.36, 0.3), 0.03, M["chrome"]))

# Mudguards over the rear wheels and the front wheel.
def mudguard(name, cx, cy, cz, r, width):
    bm = bmesh.new()
    segs = 10
    rows = []
    for i in range(segs + 1):
        a = math.pi * (0.1 + 0.8 * i / segs)
        y, z = cy + math.cos(a) * r, cz + math.sin(a) * r
        rows.append([bm.verts.new((cx - width / 2, y, z)), bm.verts.new((cx + width / 2, y, z))])
    for i in range(segs):
        bm.faces.new([rows[i][0], rows[i][1], rows[i + 1][1], rows[i + 1][0]])
    obj = mesh_object(name, bm, M["paint"])
    obj.modifiers.new("Thick", "SOLIDIFY").thickness = 0.02
    return smooth(obj)


RW = 0.25  # wheel radius
for s in (-1, 1):
    parts.append(mudguard(f"RearGuard{s}", s * 0.62, -0.75, RW, RW + 0.07, 0.2))
parts.append(mudguard("FrontGuard", 0, 1.18, RW, RW + 0.06, 0.18))

body = join(parts, "RickshawBody")
wheels = [wheel("Wheel_F", (0, 1.18, RW), RW, 0.12),
          wheel("Wheel_RL", (-0.62, -0.75, RW), RW, 0.14),
          wheel("Wheel_RR", (0.62, -0.75, RW), RW, 0.14)]


# ---------------------------------------------------------------- export and preview

kit.export([body, *wheels], OUT)
if PREVIEW:
    kit.preview(PREVIEW, HDRI)
