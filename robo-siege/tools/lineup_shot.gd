extends SceneTree
## One picture of the trooper and every kind of robot standing in the
## outpost, to check their sizes, colours and weapons by eye. Needs a
## display (on a server: xvfb-run).
##
##   godot --path . -s tools/lineup_shot.gd -- <output file.png>

const Rig := preload("res://rig.gd")
const Robot := preload("res://robot.gd")
const Arena := preload("res://arena.gd")
const Weapons := preload("res://weapons.gd")


func _initialize() -> void:
	_run()


func _run() -> void:
	var args := OS.get_cmdline_user_args()
	var out: String = args[0] if args.size() > 0 else "user://lineup.png"
	var arena := Arena.new()
	root.add_child(arena)
	var x := -4.4
	var trooper := Rig.new()
	root.add_child(trooper)
	trooper.build(Rig.Kind.TROOPER, 0, Weapons.LIST[0])
	trooper.position = Vector3(x, 0, 0)
	trooper.rotation.y = PI + 0.5
	for kind: int in [Rig.Kind.GRUNT, Rig.Kind.RUSHER, Rig.Kind.HEAVY, Rig.Kind.BOSS]:
		x += 1.9 if kind != Rig.Kind.BOSS else 2.6
		var robot := Robot.new()
		root.add_child(robot)
		robot.build(kind)
		robot.position = Vector3(x, 0, 0)
		robot.rotation.y = PI + 0.5
	var cam := Camera3D.new()
	cam.fov = 44.0
	root.add_child(cam)
	cam.look_at_from_position(Vector3(0.6, 2.0, 9.5), Vector3(0.4, 1.5, 0))
	cam.make_current()
	for i in 30:
		await process_frame
	root.get_texture().get_image().save_png(out)
	print("saved ", out)
	quit()
