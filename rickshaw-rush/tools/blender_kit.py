"""Shared Blender helpers for the game's models: PBR materials, bevelled boxes, tubes,
textured panels, wheels, joining, glTF export and a Cycles preview render.

Import bpy before this module (bpy makes bmesh and mathutils importable).
"""
import math
from pathlib import Path

import bpy
import bmesh
from mathutils import Vector

scene = None
M = {}
TASSELS = []


def reset(tex_dir):
    """Empty scene plus the shared material set. tex_dir holds the truck-art images."""
    global scene, TASSELS
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    tex = Path(tex_dir)
    M.clear()
    M.update({
        "paint": material("GreenPaint", (0.02, 0.32, 0.12), 0.5, 0.28, coat=0.8),
        "white": material("WhitePaint", (0.85, 0.85, 0.82), 0.3, 0.3, coat=0.6),
        "yellow": material("YellowPaint", (0.9, 0.62, 0.02), 0.4, 0.3, coat=0.6),
        "chrome": material("Chrome", (0.9, 0.9, 0.92), 1.0, 0.08),
        "rexine": material("BlackRexine", (0.015, 0.015, 0.018), 0.0, 0.55),
        "seat": material("RedSeat", (0.45, 0.02, 0.03), 0.0, 0.4),
        "rubber": material("Tyre", (0.02, 0.02, 0.02), 0.0, 0.85),
        "rim": material("Rim", (0.6, 0.6, 0.62), 0.9, 0.3),
        "glass": material("Glass", (0.05, 0.07, 0.08), 0.9, 0.04),
        "steel": material("DarkSteel", (0.08, 0.08, 0.09), 0.7, 0.45),
        "head": material("Headlight", (1.0, 0.95, 0.8), 0.0, 0.1, emission=(1.0, 0.92, 0.7)),
        "tail": material("TailLight", (0.8, 0.02, 0.02), 0.0, 0.2, emission=(1.0, 0.05, 0.02)),
        "plate": material("Plate", (0.95, 0.95, 0.92), 0.0, 0.5),
        "art_rear": material("TruckArtRear", (1, 1, 1), 0.2, 0.35, image=tex / "truckart_rear.png"),
        "art_side": material("TruckArtSide", (1, 1, 1), 0.2, 0.35, image=tex / "truckart_side.png"),
    })
    TASSELS[:] = [material(f"Tassel{i}", c, 0.0, 0.6) for i, c in enumerate(
        [(0.8, 0.02, 0.05), (0.95, 0.65, 0.0), (0.0, 0.5, 0.2), (0.05, 0.25, 0.8), (0.7, 0.05, 0.5)])]


def material(name, color, metallic=0.0, roughness=0.5, emission=None, image=None, coat=0.0):
    m = bpy.data.materials.new(name)
    nt = m.node_tree
    bsdf = nt.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = (*color, 1.0)
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    if coat:
        bsdf.inputs["Coat Weight"].default_value = coat
    if emission:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1.0)
        bsdf.inputs["Emission Strength"].default_value = 4.0
    if image:
        tex = nt.nodes.new("ShaderNodeTexImage")
        tex.image = bpy.data.images.load(str(image))
        nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    return m


# ---------------------------------------------------------------- mesh helpers

def link(obj):
    scene.collection.objects.link(obj)
    return obj


def mesh_object(name, bm, mat):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    me.materials.append(mat)
    return link(bpy.data.objects.new(name, me))


def bevel(obj, width, segments=3):
    mod = obj.modifiers.new("Bevel", "BEVEL")
    mod.width = width
    mod.segments = segments
    mod.limit_method = "ANGLE"
    return obj


def smooth(obj):
    for p in obj.data.polygons:
        p.use_smooth = True
    obj.modifiers.new("Normals", "WEIGHTED_NORMAL").keep_sharp = True
    return obj


def hexa(name, pts, mat, bevel_w=0.0):
    """Box from 8 corner points: bottom (0-3) then top (4-7), counter-clockwise from above."""
    bm = bmesh.new()
    v = [bm.verts.new(p) for p in pts]
    for f in [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]:
        bm.faces.new([v[i] for i in f])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    obj = mesh_object(name, bm, mat)
    if bevel_w:
        bevel(obj, bevel_w)
        smooth(obj)
    return obj


def box(name, center, size, mat, bevel_w=0.0):
    cx, cy, cz = center
    sx, sy, sz = (s / 2 for s in size)
    pts = [(cx - sx, cy - sy, cz - sz), (cx + sx, cy - sy, cz - sz), (cx + sx, cy + sy, cz - sz), (cx - sx, cy + sy, cz - sz),
           (cx - sx, cy - sy, cz + sz), (cx + sx, cy - sy, cz + sz), (cx + sx, cy + sy, cz + sz), (cx - sx, cy + sy, cz + sz)]
    return hexa(name, pts, mat, bevel_w)


def tube(name, a, b, radius, mat, segments=12):
    """Cylinder between points a and b."""
    a, b = Vector(a), Vector(b)
    d = b - a
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=segments, radius1=radius, radius2=radius, depth=d.length)
    obj = mesh_object(name, bm, mat)
    obj.location = (a + b) / 2
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(d.normalized())
    return smooth(obj)


def panel(name, corners, mat, repeat=(1.0, 1.0)):
    """Single textured quad; corners in UV order (0,0) (1,0) (1,1) (0,1), i.e. bottom-left,
    bottom-right, top-right, top-left as seen from outside. `repeat` tiles the texture."""
    bm = bmesh.new()
    v = [bm.verts.new(c) for c in corners]
    f = bm.faces.new(v)
    uv = bm.loops.layers.uv.new("UVMap")
    for loop, (u, v_) in zip(f.loops, [(0, 0), (1, 0), (1, 1), (0, 1)]):
        loop[uv].uv = (u * repeat[0], v_ * repeat[1])
    return mesh_object(name, bm, mat)


def wheel(name, center, radius, width):
    """Tyre with rounded shoulders, rim and hub; origin at the axle, axle along X."""
    parts = []
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=32, radius1=radius, radius2=radius, depth=width)
    tyre = mesh_object(name + "_tyre", bm, M["rubber"])
    bevel(tyre, width * 0.35, 4)
    smooth(tyre)
    parts.append(tyre)
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=24, radius1=radius * 0.6, radius2=radius * 0.6, depth=width * 1.04)
    rim = mesh_object(name + "_rim", bm, M["rim"])
    bevel(rim, 0.01, 2)
    parts.append(smooth(rim))
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=16, radius1=radius * 0.22, radius2=radius * 0.18, depth=width * 1.2)
    parts.append(smooth(mesh_object(name + "_hub", bm, M["chrome"])))
    for i in range(5):  # bolts, so the spin is visible
        a = i / 5 * math.tau
        bm = bmesh.new()
        bmesh.ops.create_cone(bm, cap_ends=True, segments=8, radius1=0.012, radius2=0.012, depth=width * 1.12)
        bmesh.ops.translate(bm, verts=bm.verts, vec=(math.cos(a) * radius * 0.38, math.sin(a) * radius * 0.38, 0))
        parts.append(mesh_object(name + f"_bolt{i}", bm, M["chrome"]))
    obj = join(parts, name)
    # Cylinders are built along Z; turn the axle to X.
    obj.rotation_euler = (0, math.pi / 2, 0)
    apply_transform(obj)
    obj.location = center
    return obj


def join(objs, name):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        # Every part needs the same UV layer, or joining drops the texture coordinates.
        if not o.data.uv_layers:
            o.data.uv_layers.new(name="UVMap")
        o.select_set(True)
        bpy.context.view_layer.objects.active = o
        for mod in list(o.modifiers):
            bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    obj = bpy.context.view_layer.objects.active
    obj.name = name
    obj.data.name = name
    return obj


def apply_transform(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)



def export(objs, out):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(filepath=str(out), export_format="GLB", use_selection=True,
                              export_apply=True, export_yup=True, export_image_format="JPEG")
    print("exported", out, "faces:", sum(len(o.data.polygons) for o in objs))


def preview(path, hdri=None, views=((3.4, -4.2, 2.3), (1.2, -5.0, 2.4)), target=(0, 0, 0.9)):
    """Cycles renders from a few camera positions: path, path_1, ..."""
    path = Path(path)
    cam = bpy.data.objects.new("Cam", bpy.data.cameras.new("Cam"))
    link(cam)
    cam.data.lens = 45
    scene.camera = cam
    box("Ground", (0, 0, -0.05), (40, 40, 0.1), material("Ground", (0.25, 0.24, 0.23), 0.0, 0.8))
    world = bpy.data.worlds.new("World")
    scene.world = world
    if hdri:
        wn = world.node_tree
        env = wn.nodes.new("ShaderNodeTexEnvironment")
        env.image = bpy.data.images.load(str(hdri))
        wn.links.new(env.outputs["Color"], wn.nodes["Background"].inputs["Color"])
    sun = bpy.data.objects.new("Sun", bpy.data.lights.new("Sun", "SUN"))
    sun.data.energy = 3.0
    sun.rotation_euler = (math.radians(50), 0, math.radians(-40))
    link(sun)
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 48
    scene.cycles.use_denoising = True
    scene.render.resolution_x = 900
    scene.render.resolution_y = 700
    for i, loc in enumerate(views):
        cam.location = loc
        cam.rotation_euler = (Vector(target) - cam.location).to_track_quat("-Z", "Y").to_euler()
        scene.render.filepath = str(path if i == 0 else path.with_name(f"{path.stem}_{i}.png"))
        bpy.ops.render.render(write_still=True)
