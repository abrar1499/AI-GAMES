extends BaseButton
## One of the three upgrades offered between waves: a tall card with a
## drawn symbol, a name, and what it does.

const UI := preload("res://ui.gd")

var title := ""
var text := ""
var color := UI.ORANGE
## Which symbol to draw: the upgrade's id from main.gd.
var symbol := ""


func _init() -> void:
	focus_mode = Control.FOCUS_NONE
	custom_minimum_size = Vector2(292, 318)
	mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND


func _ready() -> void:
	for s in [button_down, button_up, mouse_entered, mouse_exited]:
		s.connect(queue_redraw)
	pressed.connect(func():
		if UI.on_click.is_valid():
			UI.on_click.call())


func _draw() -> void:
	var mode := get_draw_mode()
	var down := mode == DRAW_PRESSED or mode == DRAW_HOVER_PRESSED
	var over := mode == DRAW_HOVER
	var box := Rect2(Vector2.ZERO, size)
	if down:
		box = box.grow(-3.0)
	var points := UI.cut_box(box, 22.0)
	var body := UI.PANEL.lerp(color, 0.10 if not (over or down) else 0.22)
	UI.fill_shaded(self, points, Color(body, 0.97), Color(UI.INK, 0.97))
	UI.outline(self, points, color if (over or down) else Color(color, 0.6), 2.0)
	# A band of the upgrade's colour across the top.
	draw_rect(Rect2(box.position.x + 24.0, box.position.y + 1.0, box.size.x - 25.0, 5.0), color)

	var centre := Vector2(box.get_center().x, box.position.y + 96.0)
	draw_arc(centre, 54.0, 0.0, TAU, 48, Color(color, 0.35), 2.0, true)
	draw_circle(centre, 46.0, Color(color, 0.12))
	_draw_symbol(centre, color)

	var bold := UI.font("bold", 1)
	draw_string(bold, Vector2(box.position.x + 14.0, box.position.y + 196.0), title.to_upper(), HORIZONTAL_ALIGNMENT_CENTER, box.size.x - 28.0, 30, color)
	draw_multiline_string(UI.font("semi"), Vector2(box.position.x + 22.0, box.position.y + 228.0), text, HORIZONTAL_ALIGNMENT_CENTER, box.size.x - 44.0, 20, 3, UI.TEXT)
	draw_string(UI.font("bold", 3), Vector2(box.position.x, box.end.y - 18.0), "TAP TO INSTALL", HORIZONTAL_ALIGNMENT_CENTER, box.size.x, 14, Color(UI.DIM, 0.9))


func _draw_symbol(c: Vector2, ink: Color) -> void:
	match symbol:
		"damage":
			# A bullet, nose up.
			draw_colored_polygon([c + Vector2(-9, 26), c + Vector2(-9, -6), c + Vector2(0, -28), c + Vector2(9, -6), c + Vector2(9, 26)], ink)
			draw_rect(Rect2(c + Vector2(-13, 20), Vector2(26, 6)), ink.darkened(0.3))
		"rate":
			# Three chevrons: faster, faster, faster.
			for i in 3:
				var x := -22.0 + i * 16.0
				draw_polyline([c + Vector2(x, -20), c + Vector2(x + 16, 0), c + Vector2(x, 20)], Color(ink, 0.45 + 0.27 * i), 6.0, true)
		"mag":
			# A magazine with rounds showing.
			draw_colored_polygon([c + Vector2(-12, -26), c + Vector2(14, -26), c + Vector2(8, 26), c + Vector2(-18, 26)], Color(ink, 0.35))
			for i in 4:
				draw_rect(Rect2(c + Vector2(-12 - i * 1.6, -20 + i * 11.0), Vector2(22, 6)), ink)
		"armour":
			# A shield.
			draw_colored_polygon([c + Vector2(-24, -24), c + Vector2(24, -24), c + Vector2(24, 2), c + Vector2(0, 30), c + Vector2(-24, 2)], ink)
			draw_colored_polygon([c + Vector2(-13, -14), c + Vector2(13, -14), c + Vector2(13, 0), c + Vector2(0, 16), c + Vector2(-13, 0)], UI.INK)
		"regen":
			# A cross.
			draw_rect(Rect2(c + Vector2(-8, -26), Vector2(16, 52)), ink)
			draw_rect(Rect2(c + Vector2(-26, -8), Vector2(52, 16)), ink)
		"speed":
			# A bolt of lightning.
			draw_colored_polygon([c + Vector2(6, -30), c + Vector2(-18, 4), c + Vector2(-2, 4), c + Vector2(-8, 30), c + Vector2(18, -6), c + Vector2(2, -6)], ink)
		"grenade":
			# A bomb with a short fuse.
			draw_circle(c + Vector2(0, 6), 22.0, ink)
			draw_rect(Rect2(c + Vector2(-7, -24), Vector2(14, 10)), ink.darkened(0.25))
			draw_polyline([c + Vector2(0, -24), c + Vector2(6, -32), c + Vector2(16, -30)], ink.lightened(0.3), 4.0, true)
		_:
			draw_circle(c, 18.0, ink)
