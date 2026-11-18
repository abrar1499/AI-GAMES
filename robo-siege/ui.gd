extends RefCounted
## The look of the menus: the colours, the type, and a few shapes. Every
## screen draws with these, so they all belong to the same game.
##
## The drawn parts themselves are in the ui folder: a plate (panel), a
## tech button, a meter and a weapon view.

const ORANGE := Color("ff7a1a")
const AMBER := Color("ffb14a")
const CYAN := Color("3fd8ff")
const INK := Color("060a12")
const PANEL := Color("0e1725")
const PANEL_HI := Color("1a2a40")
const LINE := Color("2d4763")
const TEXT := Color("eef4fb")
const DIM := Color("8ea3ba")
const GREEN := Color("56e08a")
const RED := Color("ff5648")
const GOLD := Color("ffc94a")

const FONTS := {
	"title": "res://assets/fonts/Orbitron.ttf",
	"bold": "res://assets/fonts/Rajdhani-Bold.ttf",
	"semi": "res://assets/fonts/Rajdhani-SemiBold.ttf",
	"medium": "res://assets/fonts/Rajdhani-Medium.ttf",
}

static var _fonts := {}
## Called whenever a button is pressed; main.gd points it at the click sound.
static var on_click := Callable()


## A typeface: "title" for names and headlines, "bold", "semi" and "medium"
## for everything else. `spacing` spreads the letters, for small capitals.
static func font(kind := "bold", spacing := 0) -> Font:
	var key := "%s/%d" % [kind, spacing]
	if _fonts.has(key):
		return _fonts[key]
	var file: Font = load(FONTS[kind])
	var made: Font = file
	if kind == "title" or spacing != 0:
		var varied := FontVariation.new()
		varied.base_font = file
		if kind == "title":
			# The heavy cut of the face. The axis has to be named by its tag.
			var weight := TextServerManager.get_primary_interface().name_to_tag("wght")
			varied.variation_opentype = {weight: 800}
		varied.spacing_glyph = spacing
		made = varied
	_fonts[key] = made
	return made


## Makes the game's own type the default for anything that asks for none.
static func install() -> void:
	ThemeDB.fallback_font = font("semi")
	ThemeDB.fallback_font_size = 20


static func label(text: String, size: int, color := TEXT, kind := "bold", spacing := 0) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_override("font", font(kind, spacing))
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return l


## A headline: heavy type with a dark edge so it reads over the 3D scene.
static func headline(text: String, size: int, color := TEXT) -> Label:
	var l := label(text, size, color, "title")
	l.add_theme_constant_override("outline_size", maxi(4, size / 7))
	l.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.55))
	return l


## A small heading with a coloured bar before it: "| LOADOUT".
static func section(text: String, color := CYAN) -> Control:
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	row.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var bar := ColorRect.new()
	bar.color = color
	bar.custom_minimum_size = Vector2(4, 18)
	bar.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	bar.mouse_filter = Control.MOUSE_FILTER_IGNORE
	row.add_child(bar)
	row.add_child(label(text, 18, DIM, "bold", 3))
	return row


## A box with some of its corners cut off at 45 degrees. `corners` says
## which: top-left, top-right, bottom-right, bottom-left.
static func cut_box(at: Rect2, cut: float, corners := [true, false, true, false]) -> PackedVector2Array:
	var c := minf(cut, minf(at.size.x, at.size.y) * 0.5)
	var p := at.position
	var e := at.end
	var out := PackedVector2Array()
	if corners[0]:
		out.append(Vector2(p.x, p.y + c))
		out.append(Vector2(p.x + c, p.y))
	else:
		out.append(p)
	if corners[1]:
		out.append(Vector2(e.x - c, p.y))
		out.append(Vector2(e.x, p.y + c))
	else:
		out.append(Vector2(e.x, p.y))
	if corners[2]:
		out.append(Vector2(e.x, e.y - c))
		out.append(Vector2(e.x - c, e.y))
	else:
		out.append(e)
	if corners[3]:
		out.append(Vector2(p.x + c, e.y))
		out.append(Vector2(p.x, e.y - c))
	else:
		out.append(Vector2(p.x, e.y))
	return out


## Fills a cut box with a colour that shades from `top` to `bottom`.
static func fill_shaded(on: CanvasItem, points: PackedVector2Array, top: Color, bottom: Color) -> void:
	var low := INF
	var high := -INF
	for p in points:
		low = minf(low, p.y)
		high = maxf(high, p.y)
	var colors := PackedColorArray()
	for p in points:
		colors.append(top.lerp(bottom, (p.y - low) / maxf(high - low, 1.0)))
	on.draw_polygon(points, colors)


static func outline(on: CanvasItem, points: PackedVector2Array, color: Color, width := 1.5) -> void:
	var closed := points.duplicate()
	closed.append(points[0])
	on.draw_polyline(closed, color, width, true)


## A soft dark wash fading across the screen, so type reads over the scene.
## `from` and `to` are corners of the unit square the shade runs between.
static func shade(color: Color, from: Vector2, to: Vector2, strength := 1.0) -> TextureRect:
	var gradient := Gradient.new()
	gradient.set_color(0, Color(color, color.a * strength))
	gradient.set_color(1, Color(color, 0.0))
	var tex := GradientTexture2D.new()
	tex.gradient = gradient
	tex.fill_from = from
	tex.fill_to = to
	tex.width = 64
	tex.height = 64
	var rect := TextureRect.new()
	rect.texture = tex
	rect.stretch_mode = TextureRect.STRETCH_SCALE
	rect.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return rect


## 12500 -> "12,500".
static func thousands(n: int) -> String:
	var digits := str(absi(n))
	var out := ""
	for i in digits.length():
		if i > 0 and (digits.length() - i) % 3 == 0:
			out += ","
		out += digits[i]
	return ("-" if n < 0 else "") + out


## Slides a control in from `offset` away while it fades up, after `delay`.
## The control must already be in the scene.
static func enter(c: Control, offset: Vector2, delay := 0.0, time := 0.35) -> void:
	c.modulate.a = 0.0
	var state := {"home": Vector2.ZERO}
	var tw := c.create_tween()
	# Never at once: the control has not been laid out until the next frame.
	tw.tween_interval(maxf(delay, 0.03))
	tw.tween_callback(func(): state.home = c.position)
	tw.tween_property(c, "modulate:a", 1.0, time * 0.7)
	tw.parallel().tween_method(
		func(k: float): c.position = (state.home as Vector2) + offset * (1.0 - k),
		0.0, 1.0, time).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
