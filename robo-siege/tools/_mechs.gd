extends SceneTree
## Lines the four mechs up in the arena, each holding the kit rifle.
func _initialize() -> void:
	_run()
func _frames(n: int) -> void:
	for i in n:
		await process_frame
func _run() -> void:
	var out: String = OS.get_cmdline_user_args()[0]
	DirAccess.make_dir_recursive_absolute(out)
	var world := Node3D.new()
	root.add_child(world)
	world.add_child(load("res://arena.gd").new())
	var names := ["george", "leela", "mike", "stan"]
	var x := -4.5
	for n in names:
		var m: Node3D = load("res://assets/models/%s.glb" % n).instantiate()
		world.add_child(m)
		var sk: Skeleton3D = m.find_child("Skeleton3D", true, false)
		var head := sk.find_bone("Head")
		var up: float = sk.get_bone_global_rest(head).origin.y
		var s := 1.62 / maxf(up, 0.01)
		m.scale = Vector3.ONE * s
		m.position = Vector3(x, 0, 0)
		m.rotation.y = PI
		var ap: AnimationPlayer = m.find_child("AnimationPlayer", true, false)
		var pick := ""
		for a in ap.get_animation_list():
			if a.ends_with("Idle"):
				pick = a
		if pick != "":
			ap.get_animation(pick).loop_mode = Animation.LOOP_LINEAR
			ap.play(pick)
		# The rifle, in the right hand.
		var hand := sk.find_bone("PalmI.R") if sk.find_bone("PalmI.R") >= 0 else sk.find_bone("Foot.R")
		print("%-7s scale %.3f  head %.2f  hand bone %d  anims %d" % [n, s, up, hand, ap.get_animation_list().size()])
		x += 3.0
	var cam := Camera3D.new()
	cam.fov = 50.0
	world.add_child(cam)
	cam.look_at_from_position(Vector3(0.5, 2.0, 9.0), Vector3(0.5, 1.0, 0))
	cam.make_current()
	await _frames(40)
	root.get_texture().get_image().save_png(out.path_join("mechs.png"))
	print("saved")
	quit()
