extends Control
## The controls and everything drawn over the game: crosshair, health,
## ammo, radar, damage arrows and (on a touch screen) the buttons.
##
## Touch: left side of the screen is a stick that appears under the thumb,
## the right side turns the view, and there are buttons for fire, reload,
## grenade and dash. Keyboard and mouse: WASD, mouse, left click, R, G or
## right click, Space or Shift.

signal pause_pressed

const TOUCH_LOOK := 0.0046
const MOUSE_LOOK := 0.0026
const STICK_RADIUS := 95.0
const RADAR_RANGE := 42.0

const C_ORANGE := Color("ff7a1a")
const C_CYAN := Color("35e0ff")
const C_RED := Color("ff3b30")
const C_DARK := Color(0.06, 0.08, 0.12, 0.55)

var player: Node3D
## False in menus: nothing is drawn and no input reaches the trooper.
var active := false
var touch := false

var wave := 1
var robots_left := 0
var score := 0
var boss: Node3D

var _font: Font
var _time := 0.0
var _touches := {}           # finger index -> "stick", "look" or "fire"
var _stick_from := Vector2.ZERO
var _stick_now := Vector2.ZERO
var _stick_on := false
var _mouse_fire := false
var _hit := 0.0
var _hit_kill := false
var _hurt := 0.0
var _arrows: Array = []      # each [angle, seconds left]
var _banner := ""
var _banner_sub := ""
var _banner_left := 0.0
var _banner_total := 1.0
var _popups: Array = []      # each [text, age, colour]


func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	_font = ThemeDB.fallback_font
	touch = DisplayServer.is_touchscreen_available()


func set_active(on: bool) -> void:
	active = on
	_touches.clear()
	_stick_on = false
	_mouse_fire = false
	if player:
		player.move = Vector2.ZERO
		player.want_fire = false
	if not touch:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED if on else Input.MOUSE_MODE_VISIBLE
	queue_redraw()


func show_banner(text: String, sub := "", seconds := 2.2) -> void:
	_banner = text
	_banner_sub = sub
	_banner_left = seconds
	_banner_total = seconds


func on_shot_hit(killed: bool, _crit: bool) -> void:
	_hit = 0.18
	_hit_kill = killed


func on_hurt(angle: float) -> void:
	_hurt = 1.0
	_arrows.append([angle, 1.2])
	if touch:
		Input.vibrate_handheld(40)


func add_popup(text: String, color := Color.WHITE) -> void:
	_popups.append([text, 0.0, color])
	if _popups.size() > 5:
		_popups.pop_front()


# ---------------------------------------------------------------- buttons

func _fire_at() -> Vector2:
	return Vector2(size.x - 140, size.y - 150)


func _grenade_at() -> Vector2:
	return Vector2(size.x - 290, size.y - 100)


func _reload_at() -> Vector2:
	return Vector2(size.x - 280, size.y - 225)


func _dash_at() -> Vector2:
	return Vector2(size.x - 120, size.y - 310)


func _pause_at() -> Vector2:
	return Vector2(size.x - 56, 52)


# ---------------------------------------------------------------- input

func _input(event: InputEvent) -> void:
	if not active or player == null:
		return
	if event is InputEventScreenTouch:
		touch = true
		if event.pressed:
			_touch_down(event.index, event.position)
		else:
			_touch_up(event.index)
	elif event is InputEventScreenDrag:
		match _touches.get(event.index, ""):
			"stick":
				_stick_now = event.position
			"look", "fire":
				player.add_look(event.relative * TOUCH_LOOK * (1280.0 / maxf(size.x, 1.0)))
	elif event is InputEventMouseMotion and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		player.add_look(event.relative * MOUSE_LOOK)
	elif event is InputEventMouseButton and not touch:
		if event.button_index == MOUSE_BUTTON_LEFT:
			if event.pressed and Input.mouse_mode != Input.MOUSE_MODE_CAPTURED:
				Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
			_mouse_fire = event.pressed
		elif event.button_index == MOUSE_BUTTON_RIGHT and event.pressed:
			player.throw_grenade()
	elif event is InputEventKey and event.pressed and not event.echo:
		match event.physical_keycode:
			KEY_R:
				player.reload()
			KEY_G:
				player.throw_grenade()
			KEY_SPACE, KEY_SHIFT:
				player.dash()
			KEY_ESCAPE, KEY_P:
				pause_pressed.emit()


func _touch_down(index: int, at: Vector2) -> void:
	if at.distance_to(_pause_at()) < 46.0:
		pause_pressed.emit()
	elif at.distance_to(_fire_at()) < 92.0:
		_touches[index] = "fire"
	elif at.distance_to(_grenade_at()) < 58.0:
		player.throw_grenade()
	elif at.distance_to(_reload_at()) < 54.0:
		player.reload()
	elif at.distance_to(_dash_at()) < 58.0:
		player.dash()
	elif at.x < size.x * 0.42 and not _stick_on:
		_touches[index] = "stick"
		_stick_on = true
		_stick_from = at
		_stick_now = at
	else:
		_touches[index] = "look"


func _touch_up(index: int) -> void:
	if _touches.get(index, "") == "stick":
		_stick_on = false
	_touches.erase(index)


func _process(dt: float) -> void:
	_time += dt
	_hit = maxf(0.0, _hit - dt)
	_hurt = maxf(0.0, _hurt - dt * 1.4)
	_banner_left = maxf(0.0, _banner_left - dt)
	for a in _arrows:
		a[1] -= dt
	_arrows = _arrows.filter(func(a): return a[1] > 0.0)
	for p in _popups:
		p[1] += dt
	_popups = _popups.filter(func(p): return p[1] < 1.0)

	if active and player and not player.autopilot:
		var move := Vector2.ZERO
		if _stick_on:
			move = (_stick_now - _stick_from) / STICK_RADIUS
			move.y = -move.y
			if move.length() > 1.0:
				move = move.normalized()
			if move.length() < 0.12:
				move = Vector2.ZERO
		else:
			if Input.is_physical_key_pressed(KEY_W) or Input.is_physical_key_pressed(KEY_UP):
				move.y += 1.0
			if Input.is_physical_key_pressed(KEY_S) or Input.is_physical_key_pressed(KEY_DOWN):
				move.y -= 1.0
			if Input.is_physical_key_pressed(KEY_D) or Input.is_physical_key_pressed(KEY_RIGHT):
				move.x += 1.0
			if Input.is_physical_key_pressed(KEY_A) or Input.is_physical_key_pressed(KEY_LEFT):
				move.x -= 1.0
		player.move = move
		player.want_fire = _mouse_fire or _touches.values().has("fire")
	queue_redraw()


# ---------------------------------------------------------------- drawing

func _text(at: Vector2, text: String, font_size: int, color: Color, align := HORIZONTAL_ALIGNMENT_LEFT, width := -1.0) -> void:
	var p := at
	if align == HORIZONTAL_ALIGNMENT_CENTER and width < 0.0:
		var w := _font.get_string_size(text, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size).x
		p.x -= w * 0.5
	elif align == HORIZONTAL_ALIGNMENT_RIGHT and width < 0.0:
		p.x -= _font.get_string_size(text, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size).x
	draw_string_outline(_font, p, text, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size, maxi(4, font_size / 5), Color(0, 0, 0, 0.75 * color.a))
	draw_string(_font, p, text, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size, color)


func _draw() -> void:
	if player == null:
		return
	var centre := size * 0.5
	_draw_hurt()
	if _banner_left > 0.0:
		_draw_banner()
	if not active:
		return
	_draw_crosshair(centre)
	_draw_health()
	_draw_radar(Vector2(96, 172))
	_draw_top()
	_draw_ammo()
	if boss and is_instance_valid(boss) and not boss.dead:
		_draw_boss_bar()
	for i in _popups.size():
		var p: Array = _popups[i]
		var col: Color = p[2]
		col.a = 1.0 - float(p[1])
		_text(centre + Vector2(70, -30 - float(p[1]) * 40.0 - i * 4.0), p[0], 26, col)
	if touch:
		_draw_touch()


func _draw_crosshair(c: Vector2) -> void:
	var col := C_RED if player.on_target else Color.WHITE
	var gap: float = 9.0 + player.bloom * 24.0
	for d: Vector2 in [Vector2.RIGHT, Vector2.LEFT, Vector2.UP, Vector2.DOWN]:
		draw_line(c + d * gap, c + d * (gap + 12.0), Color(0, 0, 0, 0.6), 5.0)
		draw_line(c + d * gap, c + d * (gap + 12.0), col, 2.5)
	draw_circle(c, 2.5, col)
	if _hit > 0.0:
		var hc := C_RED if _hit_kill else Color.WHITE
		hc.a = _hit / 0.18
		var s := 12.0 + (0.18 - _hit) * 50.0
		for d: Vector2 in [Vector2(1, 1), Vector2(-1, 1), Vector2(1, -1), Vector2(-1, -1)]:
			draw_line(c + d * s * 0.5, c + d * s, hc, 3.5 if _hit_kill else 2.5)
	if player.reloading > 0.0:
		var k: float = 1.0 - player.reloading / player.reload_time
		draw_arc(c, 34.0, -PI * 0.5, -PI * 0.5 + TAU * k, 32, C_ORANGE, 5.0, true)
		_text(c + Vector2(0, 70), "RELOADING", 20, C_ORANGE, HORIZONTAL_ALIGNMENT_CENTER)


func _bar(rect: Rect2, fill: float, color: Color) -> void:
	draw_rect(rect.grow(3), C_DARK)
	draw_rect(Rect2(rect.position, Vector2(rect.size.x * clampf(fill, 0.0, 1.0), rect.size.y)), color)
	draw_rect(rect.grow(3), Color(1, 1, 1, 0.35), false, 2.0)


func _draw_health() -> void:
	var frac: float = player.hp / player.max_hp
	var col := Color("4cd964") if frac > 0.5 else (Color("ffc400") if frac > 0.25 else C_RED)
	_bar(Rect2(28, 30, 300, 22), frac, col)
	_text(Vector2(338, 50), str(int(ceil(player.hp))), 26, Color.WHITE)
	_text(Vector2(28, 80), "ARMOUR", 16, Color(1, 1, 1, 0.7))


func _draw_top() -> void:
	var x := size.x * 0.5
	_text(Vector2(x, 44), "WAVE %d" % wave, 34, Color.WHITE, HORIZONTAL_ALIGNMENT_CENTER)
	_text(Vector2(x, 72), "Robots left: %d" % robots_left, 20, Color(1, 1, 1, 0.85), HORIZONTAL_ALIGNMENT_CENTER)
	_text(Vector2(size.x - 110, 46), str(score), 34, C_ORANGE, HORIZONTAL_ALIGNMENT_RIGHT)
	_text(Vector2(size.x - 110, 70), "SCORE", 16, Color(1, 1, 1, 0.7), HORIZONTAL_ALIGNMENT_RIGHT)
	# Pause button.
	var p := _pause_at()
	draw_circle(p, 30.0, C_DARK)
	draw_rect(Rect2(p + Vector2(-11, -12), Vector2(8, 24)), Color.WHITE)
	draw_rect(Rect2(p + Vector2(3, -12), Vector2(8, 24)), Color.WHITE)


func _draw_ammo() -> void:
	var at := Vector2(size.x - 40, size.y - 40) if not touch else Vector2(size.x * 0.5 + 40, size.y - 44)
	var low: bool = player.ammo <= player.mag_size / 4
	_text(at, "%d" % player.ammo, 52, C_RED if low else Color.WHITE, HORIZONTAL_ALIGNMENT_RIGHT)
	_text(at + Vector2(0, 24), "/ %d" % player.mag_size, 20, Color(1, 1, 1, 0.7), HORIZONTAL_ALIGNMENT_RIGHT)
	if not touch:
		_text(Vector2(28, size.y - 30), "WASD move   Mouse aim   Click fire   R reload   G grenade   Space dash", 16, Color(1, 1, 1, 0.55))
		# Cooldowns.
		var g: float = 1.0 - player.grenade_wait / player.grenade_cooldown
		var d: float = 1.0 - player.dash_wait / player.dash_cooldown
		_bar(Rect2(size.x - 300, size.y - 64, 110, 10), g, C_ORANGE)
		_text(Vector2(size.x - 300, size.y - 72), "GRENADE", 14, Color(1, 1, 1, 0.8))
		_bar(Rect2(size.x - 300, size.y - 28, 110, 10), d, C_CYAN)
		_text(Vector2(size.x - 300, size.y - 36), "DASH", 14, Color(1, 1, 1, 0.8))


func _draw_radar(c: Vector2) -> void:
	var r := 68.0
	draw_circle(c, r, C_DARK)
	draw_arc(c, r, 0, TAU, 40, Color(1, 1, 1, 0.35), 2.0, true)
	draw_arc(c, r * 0.5, 0, TAU, 28, Color(1, 1, 1, 0.12), 1.0, true)
	# The trooper is the arrow in the middle; up is where the camera looks.
	draw_colored_polygon(PackedVector2Array([c + Vector2(0, -8), c + Vector2(6, 6), c + Vector2(-6, 6)]), C_CYAN)
	var yaw: float = player.cam_yaw
	for e: Node3D in get_tree().get_nodes_in_group("enemies"):
		if e.dead:
			continue
		var rel: Vector3 = e.global_position - player.global_position
		var flat := Vector2(rel.x, rel.z).rotated(yaw)
		var d := flat.length() / RADAR_RANGE
		var at := c + flat.normalized() * minf(d, 1.0) * (r - 6.0)
		var big: bool = e.max_hp > 250.0
		draw_circle(at, 5.5 if big else 3.5, C_RED if d <= 1.0 else Color(C_RED, 0.5))


func _draw_boss_bar() -> void:
	var w := minf(520.0, size.x * 0.4)
	_bar(Rect2(size.x * 0.5 - w * 0.5, 96, w, 14), boss.hp / boss.max_hp, Color("ff2d6f"))
	_text(Vector2(size.x * 0.5, 134), "WAR MACHINE", 18, Color("ff2d6f"), HORIZONTAL_ALIGNMENT_CENTER)


func _draw_hurt() -> void:
	# Red at the edges of the screen when hit, and all the time when nearly dead.
	var low: float = 0.0
	if active and player.hp < player.max_hp * 0.3 and not player.dead:
		low = 0.35 + 0.2 * sin(_time * 7.0)
	var strength := maxf(_hurt * 0.45, low)
	if strength > 0.01:
		var edge := Color(0.9, 0.05, 0.05, strength)
		var clear := Color(0.9, 0.05, 0.05, 0.0)
		var d := minf(size.x, size.y) * 0.28
		draw_polygon(PackedVector2Array([Vector2.ZERO, Vector2(size.x, 0), Vector2(size.x, d), Vector2(0, d)]), PackedColorArray([edge, edge, clear, clear]))
		draw_polygon(PackedVector2Array([Vector2(0, size.y), Vector2(size.x, size.y), Vector2(size.x, size.y - d), Vector2(0, size.y - d)]), PackedColorArray([edge, edge, clear, clear]))
		draw_polygon(PackedVector2Array([Vector2.ZERO, Vector2(0, size.y), Vector2(d, size.y), Vector2(d, 0)]), PackedColorArray([edge, edge, clear, clear]))
		draw_polygon(PackedVector2Array([Vector2(size.x, 0), Vector2(size.x, size.y), Vector2(size.x - d, size.y), Vector2(size.x - d, 0)]), PackedColorArray([edge, edge, clear, clear]))
	# An arc on the side each hit came from.
	var c := size * 0.5
	for a: Array in _arrows:
		var angle: float = -float(a[0]) - PI * 0.5
		var col := Color(1, 0.2, 0.15, clampf(float(a[1]), 0.0, 1.0))
		draw_arc(c, 120.0, angle - 0.3, angle + 0.3, 12, col, 9.0, true)


func _draw_banner() -> void:
	var k := _banner_left / _banner_total
	var alpha := clampf(minf(k * 4.0, (1.0 - k) * 8.0), 0.0, 1.0)
	var c := Vector2(size.x * 0.5, size.y * 0.3)
	_text(c, _banner, 72, Color(1, 1, 1, alpha), HORIZONTAL_ALIGNMENT_CENTER)
	if _banner_sub != "":
		_text(c + Vector2(0, 44), _banner_sub, 28, Color(C_ORANGE, alpha), HORIZONTAL_ALIGNMENT_CENTER)


func _button(at: Vector2, radius: float, label: String, ready: float, color: Color, pressed := false) -> void:
	draw_circle(at, radius, Color(color, 0.5 if pressed else 0.28))
	draw_arc(at, radius, 0, TAU, 40, Color(1, 1, 1, 0.75), 3.0, true)
	if ready < 1.0:
		# A dark wedge shrinks as the cooldown runs out.
		var pts := PackedVector2Array([at])
		var steps := 24
		for i in steps + 1:
			var a := -PI * 0.5 + TAU * (ready + (1.0 - ready) * i / steps)
			pts.append(at + Vector2.from_angle(a) * radius)
		if pts.size() >= 3:
			draw_colored_polygon(pts, Color(0, 0, 0, 0.55))
	_text(at + Vector2(0, radius * 0.16), label, int(radius * 0.42), Color(1, 1, 1, 0.95 if ready >= 1.0 else 0.5), HORIZONTAL_ALIGNMENT_CENTER)


func _draw_touch() -> void:
	var firing: bool = _touches.values().has("fire")
	_button(_fire_at(), 84.0, "FIRE", 1.0, C_RED, firing)
	_button(_grenade_at(), 52.0, "BOMB", 1.0 - player.grenade_wait / player.grenade_cooldown, C_ORANGE)
	_button(_dash_at(), 52.0, "DASH", 1.0 - player.dash_wait / player.dash_cooldown, C_CYAN)
	_button(_reload_at(), 48.0, "LOAD", 1.0, Color.WHITE)
	if _stick_on:
		draw_circle(_stick_from, STICK_RADIUS, Color(1, 1, 1, 0.1))
		draw_arc(_stick_from, STICK_RADIUS, 0, TAU, 40, Color(1, 1, 1, 0.5), 3.0, true)
		var knob := _stick_from + (_stick_now - _stick_from).limit_length(STICK_RADIUS)
		draw_circle(knob, 40.0, Color(1, 1, 1, 0.45))
	else:
		var rest := Vector2(170, size.y - 170)
		draw_arc(rest, STICK_RADIUS, 0, TAU, 40, Color(1, 1, 1, 0.22), 3.0, true)
		draw_circle(rest, 36.0, Color(1, 1, 1, 0.15))
