"""Render realistic suit / shirt overlays as transparent PNGs with Blender (bpy).

Usage: python tools/make_suits.py www/dress
Output PNGs are 1000x800, neck centre at x=500, y=40 (top), shoulders span full width.
"""
import sys, os, math, bpy
from mathutils import Vector

OUT = sys.argv[-1] if len(sys.argv) > 1 else "www/dress"
os.makedirs(OUT, exist_ok=True)


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    s = bpy.context.scene
    s.render.engine = "CYCLES"
    s.cycles.samples = int(os.environ.get("SAMPLES", 96))
    s.cycles.device = "CPU"
    s.render.film_transparent = True
    s.render.resolution_x, s.render.resolution_y = 1000, 800
    s.view_settings.view_transform = "Standard"
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = 1.0
    cam.location = (0, -3, -0.22)
    cam.rotation_euler = (math.pi / 2, 0, 0)
    s.collection.objects.link(cam)
    s.camera = cam
    w = bpy.data.worlds.new("w"); s.world = w; w.use_nodes = True
    w.node_tree.nodes["Background"].inputs[0].default_value = (0.6, 0.6, 0.62, 1)
    w.node_tree.nodes["Background"].inputs[1].default_value = 0.12
    for name, loc, en, size in [("key", (-1.5, -2.5, 1.5), 110, 2.0), ("fill", (2, -2, 0.3), 30, 3.0), ("rim", (0, 2, 1.5), 60, 1.0)]:
        L = bpy.data.lights.new(name, "AREA"); L.energy = en; L.size = size
        o = bpy.data.objects.new(name, L); o.location = loc
        o.rotation_euler = (Vector((0, 0, -0.2)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
        s.collection.objects.link(o)


def fabric(name, rgb, rough=0.75, scale=900.0, bump=0.25, sheen=0.08):
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree; b = nt.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*rgb, 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Sheen Weight"].default_value = sheen
    tc = nt.nodes.new("ShaderNodeTexCoord")
    wv = nt.nodes.new("ShaderNodeTexWave"); wv.inputs["Scale"].default_value = scale
    wv.inputs["Distortion"].default_value = 2; wv.bands_direction = "DIAGONAL"
    nz = nt.nodes.new("ShaderNodeTexNoise"); nz.inputs["Scale"].default_value = 40
    mix = nt.nodes.new("ShaderNodeMath"); mix.operation = "ADD"
    bp = nt.nodes.new("ShaderNodeBump"); bp.inputs["Strength"].default_value = bump
    nt.links.new(tc.outputs["Object"], wv.inputs["Vector"])
    nt.links.new(tc.outputs["Object"], nz.inputs["Vector"])
    nt.links.new(wv.outputs["Fac"], mix.inputs[0]); nt.links.new(nz.outputs["Fac"], mix.inputs[1])
    nt.links.new(mix.outputs[0], bp.inputs["Height"]); nt.links.new(bp.outputs["Normal"], b.inputs["Normal"])
    return m


def body(name, mat, r=1.0, front_flat=1.0):
    """Ellipsoid torso + shoulders + arms, voxel-remeshed into one smooth surface."""
    parts = [((0, 0, -0.5), (0.33, 0.16 * front_flat, 0.56)),
             ((0, 0.01, -0.12), (0.38, 0.13, 0.11)),
             ((0.33, 0.01, -0.22), (0.1, 0.11, 0.12)), ((-0.33, 0.01, -0.22), (0.1, 0.11, 0.12)),
             ((0.38, 0.01, -0.6), (0.1, 0.11, 0.45)), ((-0.38, 0.01, -0.6), (0.1, 0.11, 0.45))]
    objs = []
    for loc, sc in parts:
        bpy.ops.mesh.primitive_uv_sphere_add(segments=48, ring_count=24, location=loc)
        o = bpy.context.active_object; o.scale = tuple(v * r for v in sc); objs.append(o)
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs: o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.transform_apply(scale=True)
    bpy.ops.object.join()
    o = bpy.context.active_object; o.name = name
    rm = o.modifiers.new("r", "REMESH"); rm.mode = "VOXEL"; rm.voxel_size = 0.008
    bpy.ops.object.modifier_apply(modifier="r")
    sm = o.modifiers.new("sm", "SMOOTH"); sm.iterations = 25; sm.factor = 1.0
    bpy.ops.object.modifier_apply(modifier="sm")
    bpy.ops.object.shade_smooth()
    o.data.materials.append(mat)
    TARGET[0] = o
    return o


def poly(name, pts, y, mat, depth=0.01, bevel=0.004):
    """Flat shape in XZ plane from 2D points (x,z), extruded toward the camera."""
    import bmesh
    me = bpy.data.meshes.new(name); bm = bmesh.new()
    vs = [bm.verts.new((x, y, z)) for x, z in pts]
    f = bm.faces.new(vs)
    ext = bmesh.ops.extrude_face_region(bm, geom=[f])
    for v in [e for e in ext["geom"] if isinstance(e, bmesh.types.BMVert)]:
        v.co.y -= depth
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(name, me); bpy.context.scene.collection.objects.link(o)
    if bevel:
        b = o.modifiers.new("b", "BEVEL"); b.width = bevel; b.segments = 3
    o.modifiers.new("s", "SUBSURF").levels = 1
    me.materials.append(mat)
    for p in me.polygons: p.use_smooth = True
    return o


def front_y(x, z, obj):
    """Ray-cast toward body from camera to find front surface depth."""
    dg = bpy.context.evaluated_depsgraph_get()
    ok, loc, *_ = bpy.context.scene.ray_cast(dg, Vector((x, -3, z)), Vector((0, 1, 0)))
    return loc.y if ok else -0.12


TARGET = [None]


def place(o, offset):
    """Shift flat piece (built at y=0) so it rides the body surface + offset."""
    bpy.context.view_layer.update()
    tgt = TARGET[0]
    dg = bpy.context.evaluated_depsgraph_get()
    tev = tgt.evaluated_get(dg)
    for v in o.data.vertices:
        ok, loc, *_ = tev.ray_cast(Vector((v.co.x, -3, v.co.z)), Vector((0, 1, 0)))
        base = loc.y if ok else -0.1
        v.co.y += base + offset


def neck_hole(target):
    """Cut the neck opening so the person's neck shows through."""
    bpy.ops.mesh.primitive_cylinder_add(radius=0.1, depth=0.3, location=(0, 0.0, 0.1))
    c = bpy.context.active_object; c.scale = (1, 0.9, 1)
    m = target.modifiers.new("neck", "BOOLEAN"); m.object = c; m.operation = "DIFFERENCE"
    c.hide_render = True; c.hide_viewport = True


def shirt_front(white, v_bottom, collar=True, mat_col=None):
    # visible shirt triangle between lapels
    s = poly("shirtv", [(-0.13, 0.04), (0.13, 0.04), (0, v_bottom)], 0, white, depth=0.005, bevel=0)
    place(s, -0.004)
    if collar:
        for sx in (-1, 1):
            c = poly(f"collar{sx}", [(0.105 * sx, 0.07), (0.015 * sx, -0.05), (0.07 * sx, -0.11), (0.135 * sx, 0.0)], 0, white, depth=0.008)
            place(c, -0.016)


def tie(col):
    m = fabric("tie", col, rough=0.45, scale=300, bump=0.15, sheen=0.1)
    knot = poly("knot", [(-0.03, -0.035), (0.03, -0.035), (0.022, -0.085), (-0.022, -0.085)], 0, m, depth=0.02, bevel=0.008)
    place(knot, -0.03)
    blade = poly("blade", [(-0.02, -0.08), (0.02, -0.08), (0.042, -0.34), (0, -0.39), (-0.042, -0.34)], 0, m, depth=0.008)
    place(blade, -0.016)


def lapels(mat, v_bottom, notch=True, roll=0.02):
    for sx in (-1, 1):
        if notch:
            pts = [(0.115, 0.05), (0.15, -0.02), (0.17, -0.12), (0.215, -0.14), (0.2, -0.2), (0.0, v_bottom), (0.0, v_bottom + 0.04), (0.11, -0.05)]
        else:
            pts = [(0.115, 0.05), (0.17, -0.06), (0.17, -0.18), (0.0, v_bottom), (0.0, v_bottom + 0.04), (0.1, -0.05)]
        pts = [(x * sx, z) for x, z in pts]
        if sx < 0: pts.reverse()
        lp = poly(f"lapel{sx}", pts, 0, mat, depth=0.012, bevel=0.005)
        place(lp, -0.012 - roll)
    # button
    bm = bpy.data.materials.new("btn"); bm.use_nodes = True
    bm.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.02, 0.02, 0.02, 1)
    bm.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 0.3
    for z in (v_bottom - 0.06,):
        bpy.ops.mesh.primitive_cylinder_add(radius=0.016, depth=0.008, location=(0.0, front_y(0, z, None) - 0.008, z), rotation=(math.pi / 2, 0, 0))
        bpy.context.active_object.data.materials.append(bm)


def jacket_variant(fname, col, tie_col=None, v_bottom=-0.47, notch=True, shirt_col=(0.8, 0.8, 0.78)):
    reset()
    jm = fabric("jacket", col)
    j = body("jacket", jm)
    neck_hole(j)
    white = fabric("shirt", shirt_col, rough=0.6, scale=1500, bump=0.08, sheen=0.1)
    shirt_front(white, v_bottom)
    if tie_col: tie(tie_col)
    lapels(fabric("lapel", tuple(c * 0.85 for c in col), rough=0.7), v_bottom, notch)
    render(fname)


def shirt_variant(fname, col):
    reset()
    sm = fabric("shirtbody", col, rough=0.6, scale=1500, bump=0.08, sheen=0.1)
    s = body("shirtbody", sm, r=0.95)
    neck_hole(s)
    for sx in (-1, 1):
        c = poly(f"collar{sx}", [(0.105 * sx, 0.07), (0.01 * sx, -0.06), (0.075 * sx, -0.13), (0.14 * sx, -0.01)], 0, sm, depth=0.01)
        place(c, -0.012)
    pl = poly("placket", [(-0.022, -0.06), (0.022, -0.06), (0.022, -0.8), (-0.022, -0.8)], 0, sm, depth=0.004, bevel=0.002)
    place(pl, -0.003)
    bm = bpy.data.materials.new("btn"); bm.use_nodes = True
    bm.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.95, 0.95, 0.92, 1)
    for z in (-0.12, -0.25, -0.38, -0.51):
        bpy.ops.mesh.primitive_cylinder_add(radius=0.009, depth=0.004, location=(0, front_y(0, z, None) - 0.006, z), rotation=(math.pi / 2, 0, 0))
        bpy.context.active_object.data.materials.append(bm)
    render(fname)


def render(fname):
    bpy.context.scene.render.filepath = os.path.abspath(os.path.join(OUT, fname + ".png"))
    bpy.ops.render.render(write_still=True)
    print("wrote", fname)


VARIANTS = {
    "suit_black": lambda: jacket_variant("suit_black", (0.01, 0.01, 0.012), tie_col=(0.2, 0.008, 0.012)),
    "suit_navy": lambda: jacket_variant("suit_navy", (0.02, 0.035, 0.1), tie_col=(0.5, 0.42, 0.1)),
    "suit_grey": lambda: jacket_variant("suit_grey", (0.12, 0.12, 0.13), tie_col=(0.04, 0.06, 0.2)),
    "suit_black_notie": lambda: jacket_variant("suit_black_notie", (0.01, 0.01, 0.012)),
    "blazer_women": lambda: jacket_variant("blazer_women", (0.012, 0.012, 0.014), v_bottom=-0.36, notch=False),
    "shirt_white": lambda: shirt_variant("shirt_white", (0.8, 0.8, 0.78)),
    "shirt_blue": lambda: shirt_variant("shirt_blue", (0.45, 0.6, 0.82)),
}
only = os.environ.get("ONLY")
for k, f in VARIANTS.items():
    if not only or k in only.split(","):
        f()
