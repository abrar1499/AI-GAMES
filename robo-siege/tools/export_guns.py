"""Exports the guns used in the weapon menu to GLB."""
import bpy, sys, os
src, out_dir = sys.argv[sys.argv.index('--')+1:sys.argv.index('--')+3]
names = sys.argv[sys.argv.index('--')+3:]
os.makedirs(out_dir, exist_ok=True)
for name in names:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.fbx(filepath=os.path.join(src, name + '.fbx'))
    meshes = [o for o in bpy.data.objects if o.type == 'MESH']
    tris = sum(sum(len(p.vertices) - 2 for p in o.data.polygons) for o in meshes)
    mats = sorted({m.name for o in meshes for m in o.data.materials if m})
    # The pack ships material names but no colours. Give each part a
    # sensible one; the game repaints only the "Main" parts per weapon.
    palette = {
        'Black': (0.05, 0.05, 0.06, 1), 'Black2': (0.08, 0.08, 0.09, 1),
        'DarkMetal': (0.13, 0.14, 0.16, 1), 'Metal': (0.42, 0.44, 0.48, 1),
        'LightMetal': (0.66, 0.68, 0.72, 1), 'Grey': (0.3, 0.31, 0.34, 1),
        'Wood': (0.38, 0.24, 0.13, 1), 'DarkWood': (0.22, 0.13, 0.07, 1),
        'Green': (0.2, 0.26, 0.15, 1), 'Glass': (0.05, 0.09, 0.14, 1),
        'Main': (0.55, 0.57, 0.6, 1), 'MainDark': (0.3, 0.31, 0.34, 1),
        'MainLight': (0.75, 0.77, 0.8, 1),
    }
    metals = {'DarkMetal', 'Metal', 'LightMetal', 'Grey', 'Main', 'MainDark', 'MainLight'}
    for mat in bpy.data.materials:
        mat.use_nodes = True
        nodes, links = mat.node_tree.nodes, mat.node_tree.links
        nodes.clear()
        out = nodes.new('ShaderNodeOutputMaterial'); out.location = (400, 0)
        bsdf = nodes.new('ShaderNodeBsdfPrincipled'); bsdf.location = (120, 0)
        links.new(bsdf.outputs['BSDF'], out.inputs['Surface'])
        key = mat.name.split('.')[0]
        bsdf.inputs['Base Color'].default_value = palette.get(key, (0.5, 0.5, 0.52, 1))
        bsdf.inputs['Metallic'].default_value = 0.8 if key in metals else 0.0
        bsdf.inputs['Roughness'].default_value = 0.3 if key in metals else 0.7
    dims = max((o.dimensions for o in meshes), key=lambda d: max(d)) if meshes else None
    path = os.path.join(out_dir, name + '.glb')
    bpy.ops.export_scene.gltf(filepath=path, export_format='GLB', export_yup=True)
    print('%-18s %5d tris  %5.1f KB  dims %s  mats %s' % (
        name, tris, os.path.getsize(path)/1024, tuple(round(v,2) for v in dims) if dims else None, mats))
