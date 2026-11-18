extends BaseButton
## The button every menu uses: a cut-cornered slab with a line of type, an
## optional smaller second line, and an optional tag at its right end.
##
## A `primary` button is the one thing the screen wants pressed: it is
## filled with colour and a band of light sweeps across it.

const UI := preload("res://ui.gd")

var text := "":
	set(value):
		text = value
		queue_redraw()
## A smaller second line under the text.
var sub := "":
	set(value):
		sub = value
		queue_redraw()
## A short tag at the right end, such as "ON" or "1".
var tag := "":
	set(value):
		tag = value
		queue_redraw()
var tag_color := UI.CYAN:
	set(value):
		tag_color = value
		queue_redraw()
## The body colour of an ordinary button, and the fill of a primary one.
var color := UI.PANEL_HI
## The edge and marker colour.
var accent := UI.CYAN
var primary := false
var font_size := 26
var sub_size := 16
var align := HORIZONTAL_ALIGNMENT_LEFT
var cut := 14.0
## Room left empty at the left end, for a picture laid over the button.
var inset := 0.0
## Space between the edge and the type, and how wide the tag is.
var pad := 22.0
var tag_width := 56.0
## Stays lit, as the chosen one of a set.
var selected := false:
	set(value):
		selected = value
		queue_redraw()

var _time := 0.0
var _press := 0.0


func _init() -> void:
	focus_mode = Control.FOCUS_NONE
	mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	custom_minimum_size = Vector2(240, 60)


func _ready() -> void:
	button_down.connect(queue_redraw)
	button_up.connect(queue_redraw)
	mouse_entered.connect(queue_redraw)
	mouse_exited.connect(queue_redraw)
	pressed.connect(func():
		if UI.on_click.is_valid():
			UI.on_click.call())
	set_process(primary)


func _notification(what: int) -> void:
	if what == NOTIFICATION_RESIZED:
		pivot_offset = size * 0.5
		queue_redraw()


func _process(dt: float) -> void:
	_time += dt
	queue_redraw()


func _draw() -> void:
	var mode := get_draw_mode()
	var down := mode == DRAW_PRESSED or mode == DRAW_HOVER_PRESSED
	var over := mode == DRAW_HOVER
	var off := mode == DRAW_DISABLED
	var box := Rect2(Vector2.ZERO, size)
	if down:
		box = box.grow(-2.0)
	var points := UI.cut_box(box, cut)

	if primary:
		var top := color.lightened(0.18 if not down else 0.0)
		var bottom := color.darkened(0.12 if not down else 0.3)
		if off:
			top = Color(0.3, 0.33, 0.38)
			bottom = Color(0.22, 0.25, 0.3)
		UI.fill_shaded(self, points, top, bottom)
		UI.outline(self, points, Color(1, 1, 1, 0.55 if not off else 0.15), 2.0)
		if not off:
			_shine(box)
	else:
		var body := color
		if selected:
			body = color.lerp(accent, 0.22)
		elif over:
			body = color.lightened(0.08)
		if down:
			body = body.darkened(0.2)
		UI.fill_shaded(self, points, Color(body, 0.96), Color(body.darkened(0.35), 0.96))
		var line := accent if selected else Color(accent, 0.45)
		if off:
			line = Color(1, 1, 1, 0.12)
		UI.outline(self, points, line, 2.0 if selected else 1.5)
		# The marker down the left side.
		draw_rect(Rect2(box.position.x, box.position.y + cut, 4.0, box.size.y - cut - 6.0), accent if not off else Color(1, 1, 1, 0.15))

	var ink := UI.TEXT if not off else Color(1, 1, 1, 0.35)
	if primary and not off:
		ink = Color.WHITE
	var bold := UI.font("bold", 1)
	var left := box.position.x + pad + inset
	var room := box.size.x - pad * 2.0 - inset
	if tag != "":
		room -= tag_width + 8.0
	var block := float(font_size) * 0.74
	if sub != "":
		block += 4.0 + float(sub_size) * 0.8
	var y := box.position.y + (box.size.y - block) * 0.5 + float(font_size) * 0.74
	draw_string(bold, Vector2(left, y), text, align, room, font_size, ink)
	if sub != "":
		var soft := Color(1, 1, 1, 0.82) if primary else UI.DIM
		draw_string(UI.font("semi", 1), Vector2(left, y + 4.0 + float(sub_size) * 0.86), sub, align, room, sub_size, soft if not off else Color(1, 1, 1, 0.25))
	if tag != "":
		var pill := Rect2(box.end.x - tag_width - 16.0, box.position.y + box.size.y * 0.5 - 14.0, tag_width, 28.0)
		draw_rect(pill, Color(tag_color, 0.16))
		draw_rect(pill, Color(tag_color, 0.8), false, 1.5)
		draw_string(bold, Vector2(pill.position.x, pill.position.y + 21.0), tag, HORIZONTAL_ALIGNMENT_CENTER, pill.size.x, 18, tag_color)


## The band of light that crosses a primary button every few seconds.
func _shine(box: Rect2) -> void:
	var period := 2.8
	var k := fmod(_time, period) / 0.9
	if k > 1.0:
		return
	var width := 46.0
	var lean := box.size.y * 0.6
	var x := lerpf(box.position.x - width - lean, box.end.x, k)
	var band := PackedVector2Array([
		Vector2(x + lean, box.position.y), Vector2(x + lean + width, box.position.y),
		Vector2(x + width, box.end.y), Vector2(x, box.end.y)])
	# Keep the band inside the button: fade it out near each end.
	var inside := clampf(minf(x + lean - box.position.x, box.end.x - (x + width + lean)) / 40.0, 0.0, 1.0)
	if inside <= 0.0:
		return
	draw_colored_polygon(band, Color(1, 1, 1, 0.2 * inside))
