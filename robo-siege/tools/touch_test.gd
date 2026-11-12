extends SceneTree
## Plays the game the way a finger would: taps PLAY, pushes the stick, holds
## FIRE, taps the other buttons, and checks that each one did something.
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


func _find_button(node: Node, text: String) -> Button:
	if node is Button and (node as Button).text.begins_with(text):
		return node
	for c in node.get_children():
		var found := _find_button(c, text)
		if found:
			return found
	return null


func _run() -> void:
	var main: Node = load("res://main.tscn").instantiate()
	root.add_child(main)
	await _frames(20)
	main.sfx.enabled = false
	main.hud.touch = true

	var play := _find_button(main.ui, "PLAY")
	_check("menu has a PLAY button", play != null)
	await _tap(play.get_global_rect().get_center())
	await _frames(10)
	_check("tapping PLAY starts the game", main.state == main.State.PLAYING and main.player != null)
	if main.player == null:
		print("TOUCH TEST FAILED")
		quit(1)
		return

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

	await _tap(hud._reload_at())
	_check("LOAD starts a reload", p.reloading > 0.0)
	await _tap(hud._grenade_at())
	_check("BOMB throws a grenade", p.grenade_wait > 0.0)
	await _tap(hud._dash_at())
	_check("DASH dashes", p.dash_wait > 0.0)

	await _tap(hud._pause_at())
	await _frames(5)
	_check("the pause button pauses", main.state == main.State.PAUSED)
	var resume := _find_button(main.ui, "Resume")
	_check("pause menu has Resume", resume != null)
	if resume:
		await _tap(resume.get_global_rect().get_center())
		await _frames(5)
		_check("tapping Resume carries on", main.state == main.State.PLAYING)

	print("TOUCH TEST ", "PASSED" if failures == 0 else "FAILED")
	quit(0 if failures == 0 else 1)
