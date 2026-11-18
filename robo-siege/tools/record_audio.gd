extends SceneTree
## Records what the game really sounds like, straight from its mixer, as a
## WAV file to listen to or to measure. Run it in real time (no --fixed-fps)
## with a display (on a server: xvfb-run, with --audio-driver Dummy).
##
##   godot --path . -s tools/record_audio.gd -- <output.wav> fight [seconds] [weapon id]
##   godot --path . -s tools/record_audio.gd -- <output.wav> guns
##   godot --path . -s tools/record_audio.gd -- <output.wav> hold <weapon id> <shots>
##
## "fight" plays a battle by itself. "guns" fires each weapon in turn, the
## way the armory's TEST FIRE button does. "hold" holds one weapon's trigger
## for that many shots, to set beside the recording it was made from.

const Weapons := preload("res://weapons.gd")


func _initialize() -> void:
	_run()


func _run() -> void:
	var args := OS.get_cmdline_user_args()
	var out: String = args[0] if args.size() > 0 else "user://game_audio.wav"
	var what: String = args[1] if args.size() > 1 else "fight"
	var main: Node = load("res://main.tscn").instantiate()
	main.save_path = "user://tool_run.cfg"
	DirAccess.remove_absolute(ProjectSettings.globalize_path(main.save_path))
	root.add_child(main)
	await process_frame
	# After everything else on the master channel, so the limiter is heard too.
	var tape := AudioEffectRecord.new()
	AudioServer.add_bus_effect(0, tape)

	if what == "hold":
		# One weapon, with the trigger held for a number of shots.
		var w := Weapons.by_id(args[2] if args.size() > 2 else "rifle")
		var shots: int = int(args[3]) if args.size() > 3 else 20
		await create_timer(0.3).timeout
		tape.set_recording_active(true)
		await create_timer(0.2).timeout
		for i in shots:
			main.sfx.fire(main.Sfx.fire_name(w), main.FIRE_VOLUME)
			await create_timer(float(w.interval) - (main.Player.RELEASE_AHEAD if i == shots - 1 else 0.0)).timeout
		main.sfx.fire_end()
		await create_timer(2.0).timeout
	elif what == "guns":
		await create_timer(0.3).timeout
		tape.set_recording_active(true)
		for w: Dictionary in Weapons.LIST:
			main._test_fire(w)
			var burst: float = float(w.interval) * 7.0 if w.auto else 1.0
			await create_timer(burst + 1.1).timeout
	else:
		var seconds: float = float(args[2]) if args.size() > 2 else 20.0
		if args.size() > 3:
			main.loadout[0] = args[3]
		main.start_game()
		main.player.autopilot = true
		main.player.auto_fire = true
		tape.set_recording_active(true)
		await create_timer(seconds).timeout

	tape.set_recording_active(false)
	var recording := tape.get_recording()
	if recording == null:
		print("nothing was recorded")
		quit(1)
		return
	recording.save_to_wav(out)
	print("saved %s (%.1f s), heard: %s" % [out, recording.get_length(), main.sfx.played])
	quit()
