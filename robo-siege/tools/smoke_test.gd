extends SceneTree
## Plays the game by itself, with no screen, and checks that the basics work:
## robots arrive, the trooper can destroy them, waves advance, upgrades apply
## and the trooper can be hurt.
##
##   godot --headless --fixed-fps 4 --path . -s tools/smoke_test.gd

func _initialize() -> void:
	Engine.max_physics_steps_per_frame = 30
	_run()


func _run() -> void:
	var main: Node = load("res://main.tscn").instantiate()
	root.add_child(main)
	await process_frame
	main.sfx.enabled = false
	main.start_game()
	main.player.autopilot = true
	main.player.auto_fire = true

	var steps := 0
	var hurt := false
	var upgrades := 0
	var seen_kinds := {}
	while steps < 60 * 240 and main.wave < 6 and main.state != main.State.OVER:
		await physics_frame
		steps += 1
		if main.player.hp < main.player.max_hp:
			hurt = true
		for e in get_nodes_in_group("enemies"):
			seen_kinds[e.kind] = true
		if main.state == main.State.UPGRADE and main.panel != null and paused:
			# Take whatever upgrade comes first.
			main._apply_upgrade(main.UPGRADES.keys()[upgrades % main.UPGRADES.size()])
			main.player.autopilot = true
			upgrades += 1

	var p = main.player
	print("simulated %.0f s: wave %d, score %d, kills %d, upgrades %d, hp %d/%d, hurt %s, robot kinds seen %d, state %d" % [
		steps / 60.0, main.wave, main.score, p.kills, upgrades, p.hp, p.max_hp, hurt, seen_kinds.size(), main.state])
	var ok: bool = p.kills >= 10 and main.wave >= 3 and upgrades >= 2 and hurt and seen_kinds.size() >= 2
	print("SMOKE TEST ", "PASSED" if ok else "FAILED")
	quit(0 if ok else 1)
