"""Makes downloaded rifle models ready for the game.

    python3 tools/import_rifles.py <folder with the downloaded .glb files> assets/guns

Models from the web come every which way: any size, any way up, dozens of
loose parts, textures far larger than a gun on a phone screen needs. The
game wants every weapon the same way round, so this:

  - joins the parts into one mesh,
  - turns the model so it lies along X with the muzzle at +X and the sights
    up (the game then scales it and puts the grip in the trooper's hand),
  - thins out very dense meshes,
  - shrinks the textures to 512 pixels,
  - saves a single .glb.

Needs Blender as a Python module: pip install bpy
"""

import math
import os
import sys

import bpy

## `turn` is how far to turn the model about the vertical to bring its
## muzzle round to +X (found by looking at each one). `faces` is the most
## triangles to keep; 0 keeps them all.
MODELS = {
    "Rifle_Vanguard": {"file": "assault_rifle_pbr.glb", "turn": -90, "faces": 0},
    "Rifle_CyberAK": {"file": "cyber_ak_rifle.glb", "turn": 90, "faces": 0},
    "Rifle_Nanite": {"file": "nanite_systems_assault_rifle.glb", "turn": -90, "faces": 0},
    "Rifle_Titan": {"file": "semi_sci_fi_assault_rifle_free.glb", "turn": 0, "faces": 42000},
}
TEXTURE = 512


def find(folder, name):
    """The download, whatever was put in front of its name."""
    for entry in sorted(os.listdir(folder)):
        if entry.endswith(name):
            return os.path.join(folder, entry)
    raise SystemExit("no file ending in %s in %s" % (name, folder))


def convert(source, target, turn, faces):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=source)

    meshes = [o for o in bpy.data.objects if o.type == "MESH"]
    bpy.ops.object.select_all(action="DESELECT")
    for o in meshes:
        o.select_set(True)
    bpy.context.view_layer.objects.active = meshes[0]
    # Out from under their parents, keeping where they are, then one mesh.
    bpy.ops.object.parent_clear(type="CLEAR_KEEP_TRANSFORM")
    bpy.ops.object.make_single_user(object=True, obdata=True)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    if len(meshes) > 1:
        bpy.ops.object.join()
    gun = bpy.context.view_layer.objects.active
    for o in list(bpy.data.objects):
        if o != gun:
            bpy.data.objects.remove(o, do_unlink=True)

    # Imported objects turn by quaternion; switch to plain angles first.
    gun.rotation_mode = "XYZ"
    gun.rotation_euler = (0.0, 0.0, math.radians(turn))
    bpy.context.view_layer.objects.active = gun
    gun.select_set(True)
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)

    before = sum(len(p.vertices) - 2 for p in gun.data.polygons)
    if faces and before > faces:
        thin = gun.modifiers.new("thin", "DECIMATE")
        thin.ratio = faces / before
        bpy.ops.object.modifier_apply(modifier=thin.name)
    after = sum(len(p.vertices) - 2 for p in gun.data.polygons)

    for image in bpy.data.images:
        if image.size[0] > TEXTURE or image.size[1] > TEXTURE:
            image.scale(min(image.size[0], TEXTURE), min(image.size[1], TEXTURE))

    gun.name = os.path.splitext(os.path.basename(target))[0]
    bpy.ops.export_scene.gltf(
        filepath=target, export_format="GLB", export_yup=True, export_apply=True,
        export_image_format="JPEG", export_image_quality=88, export_animations=False)
    bpy.context.view_layer.update()
    size = gun.dimensions
    print("%-16s %6d -> %6d triangles, %d materials, %d images, %.2f x %.2f x %.2f, %.1f MB" % (
        gun.name, before, after, len(gun.data.materials), len(bpy.data.images),
        size.x, size.y, size.z, os.path.getsize(target) / 1e6))


def main():
    source, out = sys.argv[-2], sys.argv[-1]
    os.makedirs(out, exist_ok=True)
    for name, spec in MODELS.items():
        convert(find(source, spec["file"]), os.path.join(out, name + ".glb"), spec["turn"], spec["faces"])


if __name__ == "__main__":
    main()
