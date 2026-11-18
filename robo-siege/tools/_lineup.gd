extends SceneTree
## The player's mech and the four enemy machines, side by side.
func _initialize() -> void:
	_run()
func _frames(n: int) -> void:
	for i in n:
		await process_frame
func _run() -> void:
	var out: String = OS.get_cmdline_user_args()[0]
	DirAccess.make_dir_recursive_absolute(out)
	var Rig = load("res://rig.gd")
	var Robot = load("res://robot.gd")
	var world := Node3D.new()
	root.add_child(world)
	world.add_child(load("res://arena.gd").new())

	var player: Node3D = Rig.new()
	world.add_child(player)
	player.build(0, 0)
	player.position = Vector3(-5.2, 0, 0)
	player.rotation.y = 0.0

	var kinds := [1, 2, 3, 4]
	var x := -1.6
	for k in kinds:
		var e: Node3D = Robot.new()
		world.add_child(e)
		e.build(k)
		e.position = Vector3(x, 0, 0)
		e.rotation.y = 0.0
		x += 2.6 if k < 3 else 3.4
	var cam := Camera3D.new()
	cam.fov = 48.0
	world.add_child(cam)
	cam.look_at_from_position(Vector3(1.0, 2.3, 13.5), Vector3(1.0, 1.4, 0))
	cam.make_current()
	await _frames(50)
	root.get_texture().get_image().save_png(out.path_join("lineup.png"))
	print("saved")
	quit()
