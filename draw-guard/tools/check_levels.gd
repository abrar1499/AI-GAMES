extends SceneTree
## Plays every level twice without a screen: once with its stored solution
## (must win) and once with no line at all (must lose).
##
##   godot --headless --fixed-fps 2 --path . -s tools/check_levels.gd
##   godot --headless --fixed-fps 2 --path . -s tools/check_levels.gd -- 7 9   (only levels 7 and 9)

const Game := preload("res://game.gd")
const Levels := preload("res://levels.gd")


func _initialize() -> void:
	Engine.max_physics_steps_per_frame = 60
	_run()


func _play(level: Dictionary, with_line: bool) -> Dictionary:
	var g := Game.new()
	g.setup(level)
	root.add_child(g)
	await physics_frame
	await physics_frame
	var refused := 0
	if with_line:
		refused = g.auto_draw(level.sol)
	else:
		g._start_run()
	var steps := 0
	while g.phase != Game.Phase.DONE and steps < 60 * 40:
		await physics_frame
		steps += 1
	var out := {"won": g.won, "at": g.run_t, "refused": refused, "ink": g.ink_used,
		"s3": g.star3, "s2": g.star2, "max": g.ink_max, "stars": g.stars()}
	g.free()
	await physics_frame
	return out


func _run() -> void:
	var levels := Levels.all()
	var only: Array = []
	for a in OS.get_cmdline_user_args():
		only.append(int(a))
	var bad := 0
	for i in levels.size():
		if not only.is_empty() and not only.has(i + 1):
			continue
		var level: Dictionary = levels[i]
		var a: Dictionary = await _play(level, true)
		var b: Dictionary = await _play(level, false)
		var ok: bool = a.won and not b.won and a.refused == 0 and a.stars == 3
		if not ok:
			bad += 1
		print("%s level %2d  solution: %s at %.1fs (refused %d, ink %d, 3 stars <= %d, 2 stars <= %d, max %d)   no line: %s at %.1fs" % [
			"ok  " if ok else "FAIL", i + 1, "win " if a.won else "LOSE", a.at, a.refused, a.ink, a.s3, a.s2, a.max,
			"WIN " if b.won else "lose", b.at])
	print("%d levels checked, %d problems" % [levels.size() if only.is_empty() else only.size(), bad])
	quit(1 if bad > 0 else 0)
