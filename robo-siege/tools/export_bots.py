"""Exports each robot .blend to a GLB with all its animations and textures."""
import bpy, sys, os

src_dir, out_dir = sys.argv[sys.argv.index('--')+1:sys.argv.index('--')+3]
os.makedirs(out_dir, exist_ok=True)

for name in ['blaster', 'crawler', 'drone', 'lobber', 'roller', 'rocket']:
    path = os.path.join(src_dir, name + '.blend')
    bpy.ops.wm.open_mainfile(filepath=path)
    # Drop the odd duplicate actions some files carry (idle.001, move.001 ...).
    for a in list(bpy.data.actions):
        if a.name.split('.')[-1].isdigit() and len(a.name.split('.')[-1]) == 3:
            bpy.data.actions.remove(a)
    # The .blend files point at the author's own machine; find each texture
    # in the pack's own tex/ folder instead, and drop the reference images.
    tex_dir = os.path.join(src_dir, 'tex')
    have = {f.lower(): os.path.join(tex_dir, f) for f in os.listdir(tex_dir)}
    for img in list(bpy.data.images):
        if img.name == 'Render Result' or img.packed_file:
            continue
        base = os.path.basename(img.filepath.replace('\\', '/')).lower()
        # "orange plastic albedo.png.001" -> "orange plastic albedo.png"
        if base.rsplit('.', 1)[-1].isdigit():
            base = base.rsplit('.', 1)[0]
        if base in have:
            img.filepath = have[base]
            img.reload()
    for img in list(bpy.data.images):
        if img.name != 'Render Result' and not img.packed_file and not os.path.exists(bpy.path.abspath(img.filepath)):
            print('   dropping unused image', img.name)
            bpy.data.images.remove(img)
    # The materials name their texture set but have nothing wired up, so
    # build a proper Principled BSDF for each: colour map, normal map, and
    # roughness taken from the specular map (bright = smooth, so invert it).
    sets = {
        'orange plastic': ('orange plastic albedo.png', 'orange plastic norm.png', 'orange plastic specular.png'),
        'metal grid': ('metal grid albedo.png', 'metal grid normal.png', 'metal grid specular.png'),
        'black rubber': ('black rubber albedo.png', 'black rubber norm.png', 'black rubber spec.png'),
    }

    def image_for(file_name):
        path = have.get(file_name.lower())
        if not path:
            return None
        for img in bpy.data.images:
            if os.path.basename(bpy.path.abspath(img.filepath) or '').lower() == file_name.lower():
                return img
        return bpy.data.images.load(path)

    for mat in bpy.data.materials:
        key = mat.name.strip().lower()
        if key not in sets:
            continue
        colour_file, normal_file, spec_file = sets[key]
        mat.use_nodes = True
        nodes, links = mat.node_tree.nodes, mat.node_tree.links
        nodes.clear()
        out = nodes.new('ShaderNodeOutputMaterial'); out.location = (600, 0)
        bsdf = nodes.new('ShaderNodeBsdfPrincipled'); bsdf.location = (260, 0)
        links.new(bsdf.outputs['BSDF'], out.inputs['Surface'])
        colour = image_for(colour_file)
        if colour:
            tex = nodes.new('ShaderNodeTexImage'); tex.image = colour; tex.location = (-380, 220)
            links.new(tex.outputs['Color'], bsdf.inputs['Base Color'])
        normal = image_for(normal_file)
        if normal:
            tex = nodes.new('ShaderNodeTexImage'); tex.image = normal; tex.location = (-380, -300)
            tex.image.colorspace_settings.name = 'Non-Color'
            nmap = nodes.new('ShaderNodeNormalMap'); nmap.location = (-60, -300)
            links.new(tex.outputs['Color'], nmap.inputs['Color'])
            links.new(nmap.outputs['Normal'], bsdf.inputs['Normal'])
        spec = image_for(spec_file)
        if spec:
            tex = nodes.new('ShaderNodeTexImage'); tex.image = spec; tex.location = (-380, -40)
            tex.image.colorspace_settings.name = 'Non-Color'
            inv = nodes.new('ShaderNodeInvert'); inv.location = (-60, -40)
            links.new(tex.outputs['Color'], inv.inputs['Color'])
            links.new(inv.outputs['Color'], bsdf.inputs['Roughness'])
        if 'metal' in key:
            bsdf.inputs['Metallic'].default_value = 0.85

    try:
        bpy.ops.file.pack_all()
    except RuntimeError as e:
        print('pack warning', name, e)
    # Only the robot: no cameras, lights or stray helpers.
    for o in list(bpy.data.objects):
        if o.type not in ('MESH', 'ARMATURE'):
            bpy.data.objects.remove(o, do_unlink=True)
    for o in bpy.data.objects:
        o.hide_set(False)
        o.hide_viewport = False
        o.hide_render = False
    out = os.path.join(out_dir, name + '.glb')
    bpy.ops.export_scene.gltf(
        filepath=out, export_format='GLB',
        export_animations=True, export_animation_mode='ACTIONS',
        export_nla_strips=False, export_bake_animation=True,
        export_optimize_animation_size=True,
        export_apply=False, export_yup=True,
        export_image_format='AUTO', use_visible=False, use_renderable=False)
    size = os.path.getsize(out)
    print('EXPORTED %-10s %7.2f MB  actions %s' % (name, size/1e6, [a.name for a in bpy.data.actions]))
