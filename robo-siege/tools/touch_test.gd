extends SceneTree
## Plays the game the way a finger would: picks a weapon in the armory, taps
## DEPLOY, pushes the stick, holds FIRE, taps the other buttons, and checks
## that each one did something, including that it was heard.
## Catches the kind of bug the autopilot cannot: a button that ignores touch.
##
##   godot --headless --fixed-fps 30 --path . -s tools/touch_test.gd

var failures := 0


func _initialize() -> void:
	_run()


func _check(what: String, ok: bool) -> void:
	print("  %s  %s" % ["ok  " if ok else "FAIL", what])
	if not ok:
		failures += 1


## With no screen the window is tiny and the game is stretched to fit it.
## Touches arrive in window pixels, so positions in the game's own
## coordinates have to be converted before they are sent.
func _to_window(at: Vector2) -> Vector2:
	return root.get_final_transform() * at


func _touch(index: int, at: Vector2, pressed: bool) -> void:
	var e := InputEventScreenTouch.new()
	e.index = index
	e.position = _to_window(at)
	e.pressed = pressed
	Input.parse_input_event(e)


func _drag(index: int, from: Vector2, to: Vector2) -> void:
	var e := InputEventScreenDrag.new()
	e.index = index
	e.position = _to_window(to)
	e.relative = root.get_final_transform().basis_xform(to - from)
	Input.parse_input_event(e)


func _tap(at: Vector2) -> void:
	_touch(0, at, true)
	await _frames(3)
	_touch(0, at, false)
	await _frames(3)


func _frames(n: int) -> void:
	for i in n:
		await process_frame


## The first button whose words begin with `text`.
func _find_button(node: Node, text: String) -> BaseButton:
	if node is BaseButton and "text" in node and str(node.get("text")).begins_with(text):
		return node
	if node is BaseButton and str(node.get_meta("title", "")).begins_with(text):
		return node
	for c in node.get_children():
		var found := _find_button(c, text)
		if found:
			return found
	return null


## Taps the button called `text`; false if there is no such button.
func _press(main: Node, text: String) -> bool:
	var button := _find_button(main.ui, text)
	if button == null:
		return false
	await _tap(button.get_global_rect().get_center())
	await _frames(8)
	return true


func _run() -> void:
	var main: Node = load("res://main.tscn").instantiate()
	# A throwaway save, so the run starts from the defaults and leaves no mark.
	main.save_path = "user://tool_run.cfg"
	DirAccess.remove_absolute(ProjectSettings.globalize_path(main.save_path))
	root.add_child(main)
	await _frames(20)
	main.sfx.enabled = false
	main.hud.touch = true

	# The armory: look at another weapon, take it, and go back.
	_check("the lobby has an ARMORY button", await _press(main, "ARMORY"))
	_check("ARMORY opens the armory", _find_button(main.ui, "EQUIP") != null)
	_check("the armory lists the TITAN", await _press(main, "TITAN"))
	_check("the armory has an EQUIP button", await _press(main, "EQUIP"))
	_check("EQUIP puts the weapon in the lit slot", main.loadout[0] == "heavy")
	var titan: Dictionary = main.Weapons.by_id("heavy")
	_check("TEST FIRE can be pressed", await _press(main, "TEST FIRE"))
	_check("TEST FIRE plays the weapon's recording", int(main.sfx.played.get(main.Weapons.sound_of(titan), 0)) > 0)
	var before_sound: String = main.Weapons.sound_of(titan)
	_check("the armory has a SOUND button", await _press(main, "SOUND"))
	_check("SOUND gives the weapon another recording", main.Weapons.sound_of(titan) != before_sound)
	_check("the new recording is played at once", int(main.sfx.played.get(main.Weapons.sound_of(titan), 0)) > 0)
	_check("the armory has a BACK button", await _press(main, "<  BACK"))

	_check("the lobby has a DEPLOY button", await _press(main, "DEPLOY"))
	await _frames(10)
	_check("tapping DEPLOY starts the game", main.state == main.State.PLAYING and main.player != null)
	if main.player == null:
		print("TOUCH TEST FAILED")
		quit(1)
		return
	_check("the trooper carries the weapon chosen", main.player.weapon.id == "heavy")
	var recording: String = main.player.weapon.sound
	_check("and it plays the recording chosen", recording == main.Weapons.sound_of(titan))
	main.sfx.played.clear()

	var p = main.player
	var hud = main.hud
	p.auto_fire = false
	var size: Vector2 = hud.size

	# Stick: touch the left side and drag up.
	var start := Vector2(180, size.y - 180)
	var before: Vector3 = p.global_position
	_touch(1, start, true)
	await _frames(2)
	_drag(1, start, start + Vector2(0, -90))
	await _frames(30)
	_check("dragging the left side moves the trooper", p.global_position.distance_to(before) > 1.0)
	_check("moving makes footsteps", main.sfx.footfalls >= 2)
	_touch(1, start, false)
	await _frames(5)

	# Look: drag on the right side.
	var yaw: float = p.cam_yaw
	var look := Vector2(size.x * 0.6, size.y * 0.4)
	_touch(2, look, true)
	await _frames(2)
	_drag(2, look, look + Vector2(120, 0))
	await _frames(3)
	_touch(2, look + Vector2(120, 0), false)
	await _frames(3)
	_check("dragging the right side turns the view", absf(p.cam_yaw - yaw) > 0.2)

	# Fire: hold the button.
	var ammo: int = p.ammo
	_touch(3, hud._fire_at(), true)
	await _frames(20)
	_touch(3, hud._fire_at(), false)
	await _frames(3)
	_check("holding FIRE shoots", p.ammo < ammo)
	_check("each shot is heard", int(main.sfx.played.get(recording, 0)) >= ammo - p.ammo and ammo - p.ammo > 0)
	_check("the weapon's recording played while FIRE was held", float(main.sfx.fire_time.get(recording, 0.0)) > 0.3)
	await _frames(12)
	_check("letting go of FIRE ends the weapon's sound", not main.sfx.firing)

	_check("firing has used some of the magazine", p.ammo < p.mag_size)
	await _tap(hud._reload_at())
	_check("LOAD fills the magazine at once", p.ammo == p.mag_size)
	await _tap(hud._grenade_at())
	_check("BOMB throws a grenade", p.grenade_wait > 0.0)
	await _tap(hud._dash_at())
	_check("DASH dashes", p.dash_wait > 0.0)

	var first: String = p.weapon.id
	await _tap(hud._swap_at())
	_check("SWAP changes weapon", p.weapon.id != first)
	await _tap(hud._swap_at())
	_check("SWAP changes back", p.weapon.id == first)

	await _tap(hud._pause_at())
	await _frames(5)
	_check("the pause button pauses", main.state == main.State.PAUSED)
	_check("the pause menu has RESUME", await _press(main, "RESUME"))
	_check("tapping RESUME carries on", main.state == main.State.PLAYING)
	_check("no sound was asked for that does not exist", main.sfx.missing.is_empty())
	if not main.sfx.missing.is_empty():
		print("  missing sounds: ", main.sfx.missing.keys())

	print("TOUCH TEST ", "PASSED" if failures == 0 else "FAILED")
	quit(0 if failures == 0 else 1)
