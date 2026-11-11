extends SceneTree
## Saves pictures of the game playing itself. Needs a display (on a server:
## xvfb-run) and works best with --fixed-fps 30.
##
##   godot --path . --fixed-fps 30 -s tools/screenshot.gd -- <output folder> [touch] [clip]
##
## "touch" shows the on-screen buttons; "clip" also saves every other frame
## of the first fight into <output folder>/clip for making a GIF.

var out_dir := "user://shots"


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
	root.add_child(main)
	await process_frame
	main.sfx.enabled = false
	if args.has("touch"):
		main.hud.touch = true
	await _frames(20)
	await _snap("menu")

	var clip := args.has("clip")
	if clip:
		DirAccess.make_dir_recursive_absolute(out_dir.path_join("clip"))
	main.start_game()
	main.player.autopilot = true
	var shot := 0
	var frame := 0
	var upgrade_shown := false
	while shot < 6 and frame < 30 * 150:
		await process_frame
		frame += 1
		if main.state == main.State.UPGRADE and main.panel != null and paused:
			if not upgrade_shown:
				upgrade_shown = true
				await _frames(15)
				await _snap("upgrade")
			main._apply_upgrade("damage")
		elif main.state == main.State.OVER:
			break
		elif clip and frame > 75 and frame <= 345 and frame % 2 == 0:
			root.get_texture().get_image().save_png(out_dir.path_join("clip/f%04d.png" % frame))
		elif frame % 150 == 100 and main.state == main.State.PLAYING:
			shot += 1
			await _snap("play%02d" % shot)
	# Let the trooper fall, then show the end screen.
	main.player.take_damage(9999.0, Vector3(0, 0, -10))
	await _frames(70)
	await _snap("over")
	quit()
