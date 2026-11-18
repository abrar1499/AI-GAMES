extends SceneTree
## A close look at each robot in the arena, doing what it does in play.
func _initialize() -> void:
	_run()
func _frames(n: int) -> void:
	for i in n:
		await process_frame
func _run() -> void:
	var out: String = OS.get_cmdline_user_args()[0]
	DirAccess.make_dir_recursive_absolute(out)
	var main: Node = load("res://main.tscn").instantiate()
	root.add_child(main)
	await _frames(10)
	main.sfx.enabled = false
	main.start_game()
	main.player.autopilot = false
	main.player.auto_fire = false
	await _frames(40)
	var kinds := [main.Rig.Kind.GRUNT, main.Rig.Kind.RUSHER, main.Rig.Kind.HEAVY, main.Rig.Kind.BOSS]
	var names := ["grunt", "rusher", "heavy", "boss"]
	for i in 4:
		# Hold the wave open, or clearing the arena pops the upgrade screen.
		main._wave_gap = 9999.0
		main._to_spawn.clear()
		for e in main.get_tree().get_nodes_in_group("enemies"):
			e.queue_free()
		await _frames(4)
		main.player.global_position = Vector3(2.5, 0.1, 7.5)
		main.player.hp = main.player.max_hp
		main.player.cam_yaw = 0.3
		main.player.cam_pitch = -0.05
		main.wave = 1
		main._spawn(kinds[i])
		main._wave_gap = 9999.0
		await _frames(2)
		for e in main.get_tree().get_nodes_in_group("enemies"):
			e.global_position = Vector3(0, 0.1, 0)
		# Hold the robot in place so it can be seen, and let it finish arriving.
		await _frames(60)
		for e in main.get_tree().get_nodes_in_group("enemies"):
			e.global_position = Vector3(0, 0.1, 0)
			e.velocity = Vector3.ZERO
			e.rig.rotation.y = 0.0
		await _frames(2)
		main.hud.visible = false
		await _frames(2)
		root.get_texture().get_image().save_png(out.path_join(names[i] + ".png"))
		main.hud.visible = true
		print("saved ", names[i])
	quit()
