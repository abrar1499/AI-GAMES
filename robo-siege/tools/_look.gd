extends SceneTree
## Lines the robots up in the arena so their look can be checked.
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
	var arena: Node3D = load("res://arena.gd").new()
	world.add_child(arena)
	var names := ["blaster", "crawler", "drone", "lobber", "roller"]
	var x := -8.0
	for n in names:
		var scene: Node3D = load("res://assets/models/%s.glb" % n).instantiate()
		world.add_child(scene)
		var aabb := _bounds(scene)
		# Scale each robot so it is about two metres tall, and stand it on the floor.
		var s := 2.0 / maxf(aabb.size.y, 0.01)
		scene.scale = Vector3.ONE * s
		scene.position = Vector3(x, -aabb.position.y * s, 0)
		var ap: AnimationPlayer = scene.find_child("AnimationPlayer", true, false)
		if ap:
			ap.play("move")
		print("%-9s raw %s  scale %.3f" % [n, str(aabb.size.round()), s])
		x += 4.0
	var cam := Camera3D.new()
	cam.fov = 55.0
	world.add_child(cam)
	cam.look_at_from_position(Vector3(-1, 3.2, 11), Vector3(-1, 1.0, 0))
	cam.make_current()
	await _frames(40)
	root.get_texture().get_image().save_png(out.path_join("robots.png"))
	cam.look_at_from_position(Vector3(10, 6, 16), Vector3(0, 1.5, -2))
	await _frames(10)
	root.get_texture().get_image().save_png(out.path_join("arena.png"))
	print("saved")
	quit()
func _bounds(n: Node) -> AABB:
	var out := AABB()
	var first := true
	for m in _all(n):
		var b: AABB = (m as MeshInstance3D).get_aabb()
		out = b if first else out.merge(b)
		first = false
	return out
func _all(n: Node) -> Array:
	var r: Array = []
	if n is MeshInstance3D:
		r.append(n)
	for c in n.get_children():
		r.append_array(_all(c))
	return r
