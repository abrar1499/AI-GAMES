"""Builds the living things for the game in Blender: a water buffalo and a passenger in
shalwar kameez waving down a rickshaw.

Organic shapes use Blender's Skin modifier: a stick skeleton (vertices + edges) with a radius
per vertex is wrapped in a smooth skin, then subdivided.

Run with Blender's Python module (pip install bpy):
    python make_creatures.py TEXTURE_DIR OUT_DIR [PREVIEW_DIR [HDRI]]
Writes OUT_DIR/buffalo.glb and OUT_DIR/person.glb. The person's clothes use the materials
"Kameez" and "Shalwar" so the game can give every passenger different colours.

Blender axes: +Y is the front, +Z is up (the glTF exporter converts to Godot's -Z forward).
"""
import math
import sys
from pathlib import Path

import bpy  # noqa: F401  (must come first: it makes bmesh and mathutils importable)
import bmesh

sys.path.insert(0, str(Path(__file__).parent))
import blender_kit as kit
from blender_kit import join, material, mesh_object, smooth

TEX, OUT = sys.argv[1], Path(sys.argv[2])
PREVIEW = Path(sys.argv[3]) if len(sys.argv) > 3 else None
HDRI = sys.argv[4] if len(sys.argv) > 4 else None


def skin_body(name, points, edges, mat, subdiv=2, root=0):
    """points: list of ((x, y, z), radius) or ((x, y, z), (rx, ry)); edges: index pairs."""
    me = bpy.data.meshes.new(name)
    me.from_pydata([p for p, _ in points], edges, [])
    obj = kit.link(bpy.data.objects.new(name, me))
    obj.modifiers.new("Skin", "SKIN").use_smooth_shade = True
    for i, (_, r) in enumerate(points):
        rx, ry = (r, r) if isinstance(r, (int, float)) else r
        me.skin_vertices[0].data[i].radius = (rx, ry)
    me.skin_vertices[0].data[root].use_root = True
    sub = obj.modifiers.new("Subdiv", "SUBSURF")
    sub.levels = subdiv
    sub.render_levels = subdiv
    me.materials.append(mat)
    for p in me.polygons:
        p.use_smooth = True
    return obj


def ellipsoid(name, center, radii, mat, segments=16, rings=10):
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=segments, v_segments=rings, radius=1.0)
    bmesh.ops.scale(bm, vec=radii, verts=bm.verts)
    bmesh.ops.translate(bm, vec=center, verts=bm.verts)
    return smooth(mesh_object(name, bm, mat))


def curve_tube(name, pts, radii, mat, resolution=6):
    """Tapered tube along a smooth curve (horns, tail tuft)."""
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = 1.0
    cu.bevel_resolution = 3
    cu.resolution_u = resolution
    cu.use_fill_caps = True
    sp = cu.splines.new("NURBS")
    sp.points.add(len(pts) - 1)
    for p, (x, y, z), r in zip(sp.points, pts, radii):
        p.co = (x, y, z, 1.0)
        p.radius = r
    sp.use_endpoint_u = True
    sp.order_u = 3
    cu.materials.append(mat)
    obj = kit.link(bpy.data.objects.new(name, cu))
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.convert(target="MESH")
    return bpy.context.view_layer.objects.active


# ---------------------------------------------------------------- buffalo

def build_buffalo():
    kit.reset(TEX)
    hide = material("BuffaloHide", (0.035, 0.034, 0.038), 0.0, 0.62)
    horn = material("Horn", (0.18, 0.16, 0.14), 0.0, 0.45)
    hoof = material("Hoof", (0.02, 0.02, 0.02), 0.0, 0.5)
    nose = material("Nose", (0.06, 0.05, 0.05), 0.0, 0.25)
    eye = material("Eye", (0.01, 0.01, 0.01), 0.0, 0.05)

    pts = [
        ((0, -0.78, 1.12), (0.42, 0.40)),   # 0 hips (root)
        ((0, -0.1, 1.12), (0.50, 0.52)),    # 1 belly / barrel
        ((0, 0.55, 1.2), (0.46, 0.50)),     # 2 shoulders
        ((0, 0.92, 1.12), (0.27, 0.32)),    # 3 neck
        ((0, 1.18, 0.98), (0.22, 0.24)),    # 4 head
        ((0, 1.46, 0.77), (0.15, 0.13)),    # 5 muzzle
        ((0, -0.98, 1.08), 0.05),           # 6 tail root
        ((0, -1.06, 0.75), 0.035),          # 7 tail
        ((0, -1.05, 0.45), 0.03),           # 8 tail end
    ]
    edges = [(0, 1), (1, 2), (2, 3), (3, 4), (4, 5), (0, 6), (6, 7), (7, 8)]
    # Legs: thigh, knee/hock, fetlock, hoof.
    for sx in (-1, 1):
        for front in (True, False):
            y = 0.52 if front else -0.72
            top = 2 if front else 0
            base = len(pts)
            pts += [
                ((sx * 0.27, y, 0.95), 0.23 if front else 0.27),
                ((sx * 0.27, y + (0.04 if front else -0.1), 0.5), 0.12),
                ((sx * 0.27, y, 0.2), 0.09),
                ((sx * 0.27, y + 0.02, 0.0), 0.1),   # subdivision pulls the end up to the hoof
            ]
            edges += [(top, base), (base, base + 1), (base + 1, base + 2), (base + 2, base + 3)]
    parts = [skin_body("BuffaloBody", pts, edges, hide)]

    # Hooves, eyes, nostrils, ears.
    for sx in (-1, 1):
        for y in (0.54, -0.7):
            parts.append(ellipsoid(f"Hoof{sx}{y}", (sx * 0.27, y + 0.03, 0.04), (0.1, 0.115, 0.045), hoof, 10, 6))
        parts.append(ellipsoid(f"Eye{sx}", (sx * 0.17, 1.24, 1.02), (0.035, 0.035, 0.03), eye, 10, 6))
        parts.append(ellipsoid(f"Ear{sx}", (sx * 0.3, 1.08, 1.06), (0.14, 0.05, 0.06), hide, 10, 6))
        # Water-buffalo horns: sweep sideways and back in a crescent.
        parts.append(curve_tube(f"Horn{sx}", [
            (sx * 0.1, 1.12, 1.16), (sx * 0.38, 1.1, 1.22), (sx * 0.6, 0.92, 1.24),
            (sx * 0.6, 0.72, 1.32), (sx * 0.45, 0.62, 1.42)], [0.075, 0.07, 0.055, 0.035, 0.008], horn))
    parts.append(ellipsoid("TailTuft", (0, -1.05, 0.38), (0.05, 0.05, 0.1), hoof, 8, 6))

    body = join(parts, "Buffalo")
    kit.export([body], OUT / "buffalo.glb")
    if PREVIEW:
        kit.preview(PREVIEW / "buffalo.png", HDRI, views=((3.2, 3.6, 1.8), (-3.6, -1.2, 1.6)), target=(0, 0.1, 0.9))


# ---------------------------------------------------------------- person

def build_person():
    kit.reset(TEX)
    skin = material("Skin", (0.45, 0.28, 0.18), 0.0, 0.55)
    kameez = material("Kameez", (0.85, 0.85, 0.8), 0.0, 0.8)
    shalwar = material("Shalwar", (0.8, 0.8, 0.76), 0.0, 0.85)
    hair = material("Hair", (0.02, 0.015, 0.012), 0.0, 0.6)
    cap = material("Topi", (0.92, 0.92, 0.9), 0.0, 0.7)
    sandal = material("Sandal", (0.18, 0.1, 0.05), 0.0, 0.6)

    # Kameez: torso and sleeves, plus sleeves (right arm waving up).
    pts = [
        ((0, 0, 1.08), (0.17, 0.12)),      # 0 waist (root)
        ((0, 0, 1.32), (0.2, 0.13)),       # 1 chest
        ((0, 0, 1.45), (0.17, 0.11)),      # 2 shoulders line
        ((0, 0, 0.9), (0.18, 0.125)),      # 3 hips (end hidden inside the daaman)
        ((-0.2, 0, 1.43), 0.06),           # 4 left shoulder
        ((-0.25, 0.01, 1.18), 0.055),      # 5 left elbow
        ((-0.27, 0.04, 0.98), 0.05),       # 6 left cuff
        ((0.2, 0, 1.43), 0.06),            # 7 right shoulder
        ((0.33, 0.02, 1.62), 0.055),       # 8 right elbow (raised)
        ((0.36, 0.06, 1.82), 0.048),       # 9 right cuff
    ]
    edges = [(0, 1), (1, 2), (0, 3), (2, 4), (4, 5), (5, 6), (2, 7), (7, 8), (8, 9)]
    parts = [skin_body("Kameez", pts, edges, kameez)]
    # Daaman: the knee-length lower part of the kameez, an open flared cone.
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=False, segments=24, radius1=0.27, radius2=0.2, depth=0.52)
    bmesh.ops.scale(bm, vec=(1.0, 0.72, 1.0), verts=bm.verts)
    bmesh.ops.translate(bm, vec=(0, 0.005, 0.69), verts=bm.verts)
    daaman = mesh_object("Daaman", bm, kameez)
    daaman.modifiers.new("Thick", "SOLIDIFY").thickness = 0.012
    parts.append(smooth(daaman))

    # Shalwar: baggy trousers, gathered at the ankle.
    pts = [((0, 0, 0.8), (0.15, 0.1))]   # waist of the shalwar, hidden under the kameez
    edges = []
    for sx in (-1, 1):
        b = len(pts)
        pts += [((sx * 0.1, 0, 0.82), 0.11), ((sx * 0.11, 0.03, 0.48), 0.1),
                ((sx * 0.11, 0, 0.16), 0.065), ((sx * 0.11, 0.01, 0.07), 0.045)]
        edges += [(0, b), (b, b + 1), (b + 1, b + 2), (b + 2, b + 3)]
    parts.append(skin_body("Shalwar", pts, edges, shalwar))

    # Neck, head, hands, feet.
    parts.append(skin_body("Neck", [((0, 0, 1.44), 0.055), ((0, 0.01, 1.56), 0.05)], [(0, 1)], skin, 1))
    parts.append(ellipsoid("Head", (0, 0.02, 1.67), (0.095, 0.11, 0.12), skin))
    parts.append(ellipsoid("Nose", (0, 0.125, 1.66), (0.018, 0.025, 0.03), skin, 8, 6))
    for sx in (-1, 1):
        parts.append(ellipsoid(f"Ear{sx}", (sx * 0.095, 0.01, 1.67), (0.015, 0.03, 0.035), skin, 8, 6))
        parts.append(ellipsoid(f"Foot{sx}", (sx * 0.11, 0.05, 0.035), (0.045, 0.11, 0.035), skin, 10, 6))
        parts.append(ellipsoid(f"Sandal{sx}", (sx * 0.11, 0.05, 0.012), (0.055, 0.125, 0.014), sandal, 10, 4))
    parts.append(ellipsoid("HandL", (-0.272, 0.045, 0.94), (0.038, 0.028, 0.06), skin, 10, 6))
    parts.append(ellipsoid("HandR", (0.362, 0.062, 1.86), (0.038, 0.028, 0.065), skin, 10, 6))
    # Beard and hair, prayer cap on top.
    parts.append(ellipsoid("Beard", (0, 0.06, 1.6), (0.085, 0.07, 0.07), hair, 12, 8))
    parts.append(ellipsoid("Hair", (0, 0.0, 1.71), (0.1, 0.11, 0.09), hair, 12, 8))
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=20, radius1=0.1, radius2=0.098, depth=0.07)
    bmesh.ops.translate(bm, vec=(0, 0.0, 1.78), verts=bm.verts)
    parts.append(smooth(mesh_object("Topi", bm, cap)))
    # Buttons placket on the kameez front.
    parts.append(ellipsoid("Placket", (0, 0.125, 1.33), (0.02, 0.01, 0.1), material("Button", (0.6, 0.6, 0.58), 0.3, 0.4), 8, 6))

    body = join(parts, "Person")
    kit.export([body], OUT / "person.glb")
    if PREVIEW:
        kit.preview(PREVIEW / "person.png", HDRI, views=((1.6, 2.6, 1.4), (-1.2, -2.6, 1.3)), target=(0, 0, 1.0))


build_buffalo()
build_person()
