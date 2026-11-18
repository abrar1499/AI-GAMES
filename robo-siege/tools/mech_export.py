import bpy, sys, os
src_dir, tex_dir, out_dir = sys.argv[sys.argv.index('--')+1:sys.argv.index('--')+4]
os.makedirs(out_dir, exist_ok=True)
for name in ['George', 'Leela', 'Mike', 'Stan']:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.fbx(filepath=os.path.join(src_dir, name + '.fbx'))
    # Wire the pack's texture into every material.
    tex = os.path.join(tex_dir, name + '_Texture.png')
    img = bpy.data.images.load(tex) if os.path.exists(tex) else None
    for mat in bpy.data.materials:
        mat.use_nodes = True
        nodes, links = mat.node_tree.nodes, mat.node_tree.links
        nodes.clear()
        out = nodes.new('ShaderNodeOutputMaterial'); out.location = (500, 0)
        bsdf = nodes.new('ShaderNodeBsdfPrincipled'); bsdf.location = (200, 0)
        links.new(bsdf.outputs['BSDF'], out.inputs['Surface'])
        if img:
            t = nodes.new('ShaderNodeTexImage'); t.image = img; t.location = (-250, 0)
            links.new(t.outputs['Color'], bsdf.inputs['Base Color'])
        bsdf.inputs['Metallic'].default_value = 0.6
        bsdf.inputs['Roughness'].default_value = 0.4
    try:
        bpy.ops.file.pack_all()
    except RuntimeError as e:
        print('pack', e)
    arms = [o for o in bpy.data.objects if o.type == 'ARMATURE']
    bones = [b.name for b in arms[0].data.bones] if arms else []
    acts = [a.name for a in bpy.data.actions]
    meshes = [o for o in bpy.data.objects if o.type == 'MESH']
    tris = sum(sum(len(p.vertices) - 2 for p in o.data.polygons) for o in meshes)
    dim = max((o.dimensions for o in meshes), key=lambda d: d.z, default=None)
    path = os.path.join(out_dir, name.lower() + '.glb')
    bpy.ops.export_scene.gltf(filepath=path, export_format='GLB', export_animations=True,
        export_animation_mode='ACTIONS', export_nla_strips=False, export_yup=True)
    print('== %s  %d tris  %.2f MB  dims %s' % (name, tris, os.path.getsize(path)/1e6, tuple(round(v,2) for v in dim) if dim else None))
    print('   actions', [a.split('|')[-1] for a in acts])
    print('   bones', bones[:26])
