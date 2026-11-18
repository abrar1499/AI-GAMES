extends SceneTree
## Checks that every sound the game asks for exists, so that nothing can
## fall silent because of a misspelt name or a missing file.
##
##   godot --headless --path . -s tools/sound_test.gd
##
## It reads the game's own scripts for the names they play, then asks the
## sound player for each one.

const Weapons := preload("res://weapons.gd")
const Rig := preload("res://rig.gd")
const Robot := preload("res://robot.gd")

var failures := 0


func _initialize() -> void:
	_run()


func _check(what: String, ok: bool) -> void:
	if not ok:
		print("  FAIL  ", what)
		failures += 1


func _run() -> void:
	var sfx: Node = load("res://sfx.gd").new()
	root.add_child(sfx)
	await process_frame

	# Each weapon: its own recording, a reload, and a rate of fire that is
	# the rate of the shots on that recording.
	for w: Dictionary in Weapons.LIST:
		var fire: String = sfx.fire_name(w)
		var period: float = sfx.fire_period(fire)
		_check("%s has its recording (%s)" % [w.name, fire], sfx.has(fire) and period > 0.0)
		_check("%s fires in time with its recording (%.4f s a shot, the recording %.4f s)" % [w.name, w.interval, period],
			absf(float(w.interval) - period) < 0.0006)
		_check("%s has a reload sound (%s)" % [w.name, w.reload_sound], sfx.has(w.reload_sound))
		print("  %-14s %-14s one shot every %.4f s   reload: %s" % [w.name, fire, period, w.reload_sound])

	# Each kind of robot's fire.
	for kind: int in Robot.KINDS:
		_check("robot kind %d's fire is heard (%s)" % [kind, Robot.shot_sound(kind)], sfx.takes_of(Robot.shot_sound(kind)) >= 1)

	# Every name written out in the scripts.
	var asked := {}
	var pattern := RegEx.new()
	pattern.compile("(?:sound\\.emit|sound_at\\.emit|sfx\\.play|sfx\\.play_at)\\(\\s*\"([a-z_0-9]+)\"")
	for file: String in DirAccess.get_files_at("res://"):
		if not file.ends_with(".gd"):
			continue
		var text := FileAccess.get_file_as_string("res://" + file)
		for found in pattern.search_all(text):
			asked[found.get_string(1)] = file
	for sound_name: String in asked:
		_check("'%s' (asked for in %s) exists" % [sound_name, asked[sound_name]], sfx.has(sound_name))
	print("  %d sound names are written in the scripts: %s" % [asked.size(), ", ".join(asked.keys())])
	_check("the scripts name some sounds at all", asked.size() >= 10)

	# Footsteps are recordings, in two kinds.
	_check("walking steps are recorded", sfx.takes_of("step_walk") >= 4)
	_check("running steps are recorded", sfx.takes_of("step_run") >= 4)

	print("SOUND TEST ", "PASSED" if failures == 0 else "FAILED")
	quit(0 if failures == 0 else 1)
