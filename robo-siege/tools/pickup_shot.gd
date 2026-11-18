extends SceneTree
## Saves two pictures of the weapon pickups: lying on the ground in front
## of the trooper, and the fight a few seconds later. Needs a display (on a
## server: xvfb-run).
##
##   godot --path . --fixed-fps 30 -s tools/pickup_shot.gd -- <output folder> [touch]

func _initialize() -> void:
	_run()


func _run() -> void:
	var args := OS.get_cmdline_user_args()
	var out_dir: String = args[0] if args.size() > 0 else "user://shots"
	DirAccess.make_dir_recursive_absolute(out_dir)
	var main: Node = load("res://main.tscn").instantiate()
	main.save_path = "user://tool_run.cfg"
	DirAccess.remove_absolute(ProjectSettings.globalize_path(main.save_path))
	root.add_child(main)
	await process_frame
	main.sfx.enabled = false
	if args.has("touch"):
		main.hud.touch = true
	main.start_game()
	var p = main.player
	p.gun_level = 8
	var ahead: Vector3 = -p.view_basis().z
	ahead = Vector3(ahead.x, 0, ahead.z).normalized()
	var side := ahead.cross(Vector3.UP)
	var kinds: Array = main.Pickup.KINDS.keys()
	for i in kinds.size():
		main._put_pickup(p.global_position + ahead * (5.0 + absf(i - 1.0)) + side * (i - 1.0) * 2.4, kinds[i])
	for i in 45:
		await process_frame
	root.get_texture().get_image().save_png(out_dir.path_join("pickups.png"))
	p.autopilot = true
	for i in 150:
		await process_frame
	root.get_texture().get_image().save_png(out_dir.path_join("fight.png"))
	quit()
