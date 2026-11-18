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

	# Each weapon: a recording for the trooper's hands and one for a
	# robot's, a reload, and a rate of fire that is the rate of the shots on
	# its recording.
	for w: Dictionary in Weapons.LIST:
		var tuned := Weapons.tuned(w)
		var period: float = sfx.fire_period(tuned.sound)
		_check("%s has its recording (%s)" % [w.name, tuned.sound], sfx.has(tuned.sound) and period > 0.0)
		_check("%s fires in time with its recording" % w.name, absf(float(tuned.interval) - period) < 0.0001)
		_check("%s does its damage a second" % w.name, absf(float(tuned.damage) / float(tuned.interval) - float(w.dps)) < 0.5)
		var far: String = w.far
		_check("%s is heard when a robot fires it (%s)" % [w.name, far], sfx.has(far) if Weapons.is_burst(far) else sfx.takes_of("%s_shot" % far) >= 1)
		_check("%s has a reload sound (%s)" % [w.name, w.reload_sound], sfx.has(w.reload_sound))
		print("  %-14s close: %-5s %4d a minute   far: %-5s   reload: %s" % [w.name, tuned.sound, Weapons.rpm(tuned), far, w.reload_sound])

	# Every recording can be chosen in the armory, so every one must play.
	for recording: String in Weapons.recordings():
		_check("the recording '%s' plays" % recording, sfx.fire_period(recording) > 0.0)
	_check("there are several recordings to choose from", Weapons.recordings().size() >= 4)

	# Every kind of robot carries a weapon from the list.
	for kind: int in Robot.KINDS:
		for weapon_id: String in Robot.KINDS[kind].weapons:
			_check("robot kind %d's weapon '%s' is in the list" % [kind, weapon_id], Weapons.exists(weapon_id))

	# Every name written out in the scripts.
	var asked := {}
	var pattern := RegEx.new()
	pattern.compile("(?:sound\\.emit|sound_at\\.emit|sfx\\.play|sfx\\.play_at|sfx\\.play_later\\([0-9.]+,)\\(?\\s*\"([a-z_0-9]+)\"")
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

	# The recordings that hold several sounds: every one of them is there.
	for sound_name: String in sfx.SLICES:
		var want: int = (sfx.SLICES[sound_name][1] as Array).size()
		_check("'%s' has its %d sounds" % [sound_name, want], sfx.takes_of(sound_name) == want)
	# Cries, deaths and footfalls are recordings, not sounds made in code.
	for sound_name: String in ["grunt", "footfall", "die_male", "die_collapse", "die_fall", "die_breath"]:
		_check("'%s' is a recording" % sound_name, sfx.takes_of(sound_name) >= 1)

	print("SOUND TEST ", "PASSED" if failures == 0 else "FAILED")
	quit(0 if failures == 0 else 1)
