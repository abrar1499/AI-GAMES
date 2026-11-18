extends SceneTree
## Pictures of every menu screen, to check the layout by eye. Needs a
## display (on a server: xvfb-run).
##
##   godot --path . --resolution 1280x720 -s tools/ui_shots.gd -- <output folder> [touch] [fight]
##
## "touch" shows the on-screen buttons. "fight" skips the lobby and armory
## and takes only the screens of a run: the fight, pause, upgrades, the end.

var out_dir := "user://ui"


func _initialize() -> void:
	_run()


func _snap(name: String) -> void:
	await process_frame
	await process_frame
	root.get_texture().get_image().save_png(out_dir.path_join(name + ".png"))
	print("saved ", name)


func _frames(n: int) -> void:
	for i in n:
		await process_frame


func _run() -> void:
	var args := OS.get_cmdline_user_args()
	if args.size() > 0:
		out_dir = args[0]
	DirAccess.make_dir_recursive_absolute(out_dir)
	var main: Node = load("res://main.tscn").instantiate()
	# A throwaway save, so the run starts from the defaults and leaves no mark.
	main.save_path = "user://tool_run.cfg"
	DirAccess.remove_absolute(ProjectSettings.globalize_path(main.save_path))
	root.add_child(main)
	await process_frame
	main.sfx.enabled = false
	if args.has("touch"):
		main.hud.touch = true
		main._show_menu()
	if not args.has("fight"):
		await _frames(50)
		await _snap("lobby")

		main._show_weapons(0)
		await _frames(45)
		await _snap("armory")
		# Look at a weapon that is not carried, to see the comparison.
		main.panel.looking = "heavy"
		main.panel._refresh()
		await _frames(40)
		await _snap("armory_compare")
		main.panel.slot = 1
		main.panel.looking = "carbine"
		main.panel._refresh()
		await _frames(40)
		await _snap("armory_secondary")

	main.start_game()
	main.player.autopilot = true
	await _frames(120)
	await _snap("play")
	main._toggle_pause()
	await _frames(20)
	await _snap("pause")
	main._toggle_pause()
	main.wave = 3
	main.state = main.State.UPGRADE
	main.hud.set_active(false)
	main._show_upgrades()
	await _frames(30)
	await _snap("upgrade")
	main._apply_upgrade("damage")
	main.score = 12450
	main.player.take_damage(9999.0, Vector3(0, 0, -10))
	await _frames(110)
	await _snap("over")
	quit()
