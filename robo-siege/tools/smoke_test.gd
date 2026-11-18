extends SceneTree
## Plays the game by itself, with no screen, and checks that the basics work:
## robots arrive, the trooper can destroy them, waves advance, upgrades apply,
## the trooper can be hurt, and the shots and footsteps are heard.
##
##   godot --headless --fixed-fps 4 --path . -s tools/smoke_test.gd

func _initialize() -> void:
	Engine.max_physics_steps_per_frame = 30
	_run()


func _run() -> void:
	var main: Node = load("res://main.tscn").instantiate()
	# A throwaway save, so the run starts from the defaults and leaves no mark.
	main.save_path = "user://tool_run.cfg"
	DirAccess.remove_absolute(ProjectSettings.globalize_path(main.save_path))
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
	var painted := {}
	while steps < 60 * 240 and main.wave < 6 and main.state != main.State.OVER:
		await physics_frame
		steps += 1
		if main.player.hp < main.player.max_hp:
			hurt = true
		for e in get_nodes_in_group("enemies"):
			seen_kinds[e.kind] = true
			# Each robot must be in its own colours, not the trooper's.
			var body = e.rig._body
			for mi in body.meshes:
				if body.weapon_meshes.has(mi):
					continue
				var paint: Material = mi.get_surface_override_material(0)
				if paint is StandardMaterial3D and not (paint as StandardMaterial3D).albedo_color.is_equal_approx(Color.WHITE):
					painted[e.kind] = true
		if main.state == main.State.UPGRADE and main.panel != null and paused:
			# Take whatever upgrade comes first.
			main._apply_upgrade(main.UPGRADES.keys()[upgrades % main.UPGRADES.size()])
			main.player.autopilot = true
			upgrades += 1

	var p = main.player
	print("simulated %.0f s: wave %d, score %d, kills %d, upgrades %d, hp %d/%d, hurt %s, robot kinds seen %d, state %d" % [
		steps / 60.0, main.wave, main.score, p.kills, upgrades, p.hp, p.max_hp, hurt, seen_kinds.size(), main.state])
	# Sound is off for the test, but what would have played is still counted.
	var heard: Dictionary = main.sfx.played
	var shots := int(heard.get(str(p.weapon.sound), 0))
	var footsteps: int = main.sfx.footfalls
	var cries := int(heard.get("grunt", 0))
	var deaths := int(heard.get("die_male", 0)) + int(heard.get("die_collapse", 0))
	var falls := int(heard.get("die_fall", 0))

	# Something on the ground for the weapon: standing on it takes it.
	var level_before: int = p.gun_level
	var damage_before: float = p.damage
	var lying := get_nodes_in_group("pickups").size()
	if not p.dead:
		main._put_pickup(p.global_position, "power")
		main._take_pickups()
	var took: bool = p.dead or (p.gun_level == level_before + 1 and p.damage > damage_before * 1.2)
	print("weapon: gun level %d, %d bullets a shot, damage %.1f, %d pickups lying about, taking one works %s" % [
		p.gun_level, p.bullets(), p.damage, lying, took])
	var robot_shots := 0
	for w: Dictionary in main.Weapons.LIST:
		robot_shots += int(heard.get(str(w.far), 0)) + int(heard.get("%s_shot" % w.far, 0))
	print("sounds: %d shots, %d robot shots, %d footfalls, %d cries, %d deaths, %d falls, %d kinds in all, missing %s" % [shots, robot_shots, footsteps, cries, deaths, falls, heard.size(), main.sfx.missing.keys()])
	var ok: bool = p.kills >= 10 and main.wave >= 3 and upgrades >= 2 and hurt and seen_kinds.size() >= 2 \
		and shots >= 10 and robot_shots >= 10 and footsteps >= 10 and cries >= 3 and deaths >= 3 and falls >= 3 and took and lying >= 1 and main.sfx.missing.is_empty() \
		and painted.size() == seen_kinds.size()
	if painted.size() != seen_kinds.size():
		print("robots not in their own colours: saw kinds %s, painted %s" % [seen_kinds.keys(), painted.keys()])
	print("SMOKE TEST ", "PASSED" if ok else "FAILED")
	quit(0 if ok else 1)
