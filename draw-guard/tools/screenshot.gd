extends SceneTree
## Saves pictures of the game for the README and for checking the look.
## Needs a display (on a server: xvfb-run).
##
##   godot --path . -s tools/screenshot.gd -- <output folder> [level numbers...]
##
## "gif:7" instead of a number saves every other frame of level 7 being played
## (run with --fixed-fps 30); "fail:7" plays level 7 with no line at all.

const Game := preload("res://game.gd")

var out_dir := "user://shots"


func _initialize() -> void:
	_run()


func _snap(name: String) -> void:
	# Long enough for a new screen to finish fading in.
	await create_timer(0.3).timeout
	await process_frame
	var img := root.get_texture().get_image()
	img.save_png(out_dir.path_join(name + ".png"))
	print("saved ", name)


func _wait(seconds: float) -> void:
	await create_timer(seconds).timeout


func _run() -> void:
	var args := OS.get_cmdline_user_args()
	var picks: Array = []
	if args.size() > 0:
		out_dir = args[0]
	var clips: Array = []
	var fails: Array = []
	for i in range(1, args.size()):
		if args[i].begins_with("gif:"):
			clips.append(int(args[i].substr(4)))
		elif args[i].begins_with("fail:"):
			fails.append(int(args[i].substr(5)))
		else:
			picks.append(int(args[i]))
	DirAccess.make_dir_recursive_absolute(out_dir)

	var main: Node = load("res://main.tscn").instantiate()
	root.add_child(main)
	await _wait(0.5)
	if picks.is_empty() and clips.is_empty() and fails.is_empty():
		await _snap("daily")
		main._close_popup()
		await _snap("home")
		main.show_levels()
		await _snap("levels")
		main.show_skins()
		await _snap("skins")
		picks = [1]

	for n: int in picks:
		main.start_level(n - 1)
		await _wait(0.3)
		main.game.show_hint()
		await _snap("level%02d_draw" % n)
		main.game.auto_draw(main.levels[n - 1].sol)
		var total: float = main.game.run_total
		for k in 3:
			await _wait(total * 0.3)
			await _snap("level%02d_run%d" % [n, k + 1])
		await _wait(total * 0.1 + 1.6)
		await _snap("level%02d_end" % n)

	for n: int in fails:
		main.start_level(n - 1)
		await _wait(0.4)
		main.game._start_run()
		await main.game.finished
		await _wait(0.25)
		await _snap("fail%02d_crack" % n)
		await _wait(1.6)
		await _snap("fail%02d_popup" % n)

	for n: int in clips:
		var dir := out_dir.path_join("gif%02d" % n)
		DirAccess.make_dir_recursive_absolute(dir)
		main.start_level(n - 1)
		var frame := 0
		var g: Node = main.game
		var state := {"done": false}
		g.finished.connect(func(_w): state.done = true)
		var line: PackedVector2Array = main.levels[n - 1].sol
		var drawn := 0
		var after := 0
		var tick := 0
		while after < 75:
			await process_frame
			tick += 1
			if tick > 20 and drawn < line.size():
				g._add_point(line[drawn])
				drawn += 1
			elif drawn == line.size():
				drawn += 1
				g._start_run()
			if state.done:
				after += 1
			if tick % 2 == 0:
				root.get_texture().get_image().save_png(dir.path_join("f%04d.png" % frame))
				frame += 1
		print("saved clip ", n, " frames ", frame)
	quit()
