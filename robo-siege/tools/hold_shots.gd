extends SceneTree
## Pictures of the trooper holding each weapon, from the side, the front
## and above, to check by eye that the hands are on the gun and the barrel
## points where the trooper aims. Needs a display (on a server: xvfb-run).
##
##   godot --path . -s tools/hold_shots.gd -- <output folder> [weapon id ...]

const Rig := preload("res://rig.gd")
const Weapons := preload("res://weapons.gd")


func _initialize() -> void:
	_run()


func _run() -> void:
	var args := OS.get_cmdline_user_args()
	var out_dir: String = args[0] if args.size() > 0 else "user://hold"
	DirAccess.make_dir_recursive_absolute(out_dir)
	var ids: Array = args.slice(1)
	if ids.is_empty():
		for w: Dictionary in Weapons.LIST:
			ids.append(w.id)

	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color("8fa3b8")
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color.WHITE
	env.ambient_light_energy = 0.9
	var we := WorldEnvironment.new()
	we.environment = env
	root.add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-40, 30, 0)
	sun.light_energy = 1.4
	root.add_child(sun)
	var cam := Camera3D.new()
	cam.fov = 32.0
	root.add_child(cam)
	cam.make_current()

	for id: String in ids:
		var w := Weapons.by_id(id)
		var rig = Rig.new()
		root.add_child(rig)
		rig.build(Rig.Kind.TROOPER, 0, w)
		# A red ball on the muzzle, so a barrel pointing the wrong way shows.
		var dot := MeshInstance3D.new()
		var ball := SphereMesh.new()
		ball.radius = 0.025
		ball.height = 0.05
		dot.mesh = ball
		var red := StandardMaterial3D.new()
		red.albedo_color = Color.RED
		red.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		dot.material_override = red
		rig.muzzle.add_child(dot)
		for i in 12:
			await process_frame
		var views := {
			"right": Vector3(3.4, 1.3, -0.1),
			"left": Vector3(-3.4, 1.3, -0.1),
			"front": Vector3(1.6, 1.5, -3.0),
			"top": Vector3(0.3, 4.6, -0.3),
		}
		for view: String in views:
			cam.look_at_from_position(views[view], Vector3(0.05, 1.2, -0.25))
			await process_frame
			await process_frame
			root.get_texture().get_image().save_png(out_dir.path_join("%s_%s.png" % [id, view]))
		print("saved ", id)
		rig.queue_free()
		await process_frame
	quit()
