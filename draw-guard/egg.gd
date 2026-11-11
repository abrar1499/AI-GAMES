extends RigidBody2D
## The egg the player has to keep safe. It cracks when a hazard touches it.

signal cracked

const RX := 27.0
const RY := 35.0

## Colours for each skin: shell, shade, pattern colour, pattern type.
const SKINS := [
	{"name": "Classic", "price": 0, "shell": Color("fff3d6"), "shade": Color("e8cf9c"), "mark": Color("e8cf9c"), "pattern": 0},
	{"name": "Robin", "price": 150, "shell": Color("9fdcec"), "shade": Color("6bb7cc"), "mark": Color("4a8496"), "pattern": 1},
	{"name": "Candy", "price": 300, "shell": Color("ffc1d6"), "shade": Color("f08fb0"), "mark": Color("ffffff"), "pattern": 2},
	{"name": "Dino", "price": 500, "shell": Color("b8e986"), "shade": Color("86bf55"), "mark": Color("4f8a2e"), "pattern": 1},
	{"name": "Gold", "price": 800, "shell": Color("ffd84d"), "shade": Color("e0a91c"), "mark": Color("fff6c2"), "pattern": 3},
	{"name": "Galaxy", "price": 1200, "shell": Color("7b5cd6"), "shade": Color("4a339c"), "mark": Color("ffe98a"), "pattern": 3},
]

var skin := 0
var broken := false
var happy := false
## Where the egg is looking, in world space (the nearest danger).
var look_at_point := Vector2.INF
## 0 = calm, 1 = terrified. Set by the level from how close danger is.
var fear := 0.0

var _blink := 0.0
var _next_blink := 2.0
var _time := 0.0
var _squash := 0.0
var _squash_v := 0.0
var _fear_now := 0.0
var _broken_t := 0.0
var _happy_t := 0.0


static func outline(n := 22, grow := 0.0) -> PackedVector2Array:
	var pts := PackedVector2Array()
	for i in n:
		var a := TAU * i / n
		var y := -cos(a) * (RY + grow)
		var widen := 1.0 + 0.16 * (y / RY)
		pts.append(Vector2(sin(a) * (RX + grow) * widen, y))
	return pts


func _ready() -> void:
	var shape := ConvexPolygonShape2D.new()
	shape.set_point_cloud(outline(14))
	var cs := CollisionShape2D.new()
	cs.shape = shape
	add_child(cs)
	var mat := PhysicsMaterial.new()
	mat.friction = 0.55
	mat.bounce = 0.1
	physics_material_override = mat
	mass = 1.0
	# A low centre of mass keeps the egg standing on flat ground.
	center_of_mass_mode = RigidBody2D.CENTER_OF_MASS_MODE_CUSTOM
	center_of_mass = Vector2(0, 9)
	contact_monitor = true
	max_contacts_reported = 8
	can_sleep = false
	body_entered.connect(_on_body_entered)
	_next_blink = 1.0 + randf() * 2.5


func _process(delta: float) -> void:
	_time += delta
	_next_blink -= delta
	if _next_blink <= 0.0:
		_blink = 0.14
		_next_blink = 2.0 + randf() * 3.0
	if _blink > 0.0:
		_blink -= delta
	# A soft spring makes the egg wobble like jelly when something bumps it.
	_squash_v += (-_squash * 220.0 - _squash_v * 11.0) * delta
	_squash += _squash_v * delta
	_fear_now = lerpf(_fear_now, fear, minf(1.0, delta * 8.0))
	if broken:
		_broken_t += delta
	if happy:
		_happy_t += delta
	queue_redraw()


## Squash the egg; positive flattens it first.
func bump(amount: float) -> void:
	_squash_v += amount * 9.0


func _on_body_entered(body: Node) -> void:
	bump(clampf(linear_velocity.length() / 500.0, 0.25, 1.2))
	if body.is_in_group("hazard"):
		crack()


func crack() -> void:
	if broken or freeze:
		return
	broken = true
	cracked.emit()
	queue_redraw()


func _draw() -> void:
	var breathe := 0.02 * sin(_time * 2.6)
	var sq := clampf(_squash, -0.45, 0.45) * 0.3 + breathe
	var sx := 1.0 + sq
	var sy := 1.0 - sq
	var offset := Vector2(0, RY * (1.0 - sy))
	if _fear_now > 0.05 and not broken and not happy:
		offset.x += sin(_time * 58.0) * 1.8 * _fear_now
	if happy:
		# Jump for joy, straight up whatever way the egg is lying.
		offset += Vector2(0, -absf(sin(_happy_t * 7.5)) * 20.0).rotated(-global_rotation)
	draw_set_transform(offset, 0.0, Vector2(sx, sy))
	draw_egg(self, skin, broken, happy, _look_dir(), _blink > 0.0, _time, _fear_now, _broken_t)


func _look_dir() -> Vector2:
	if look_at_point == Vector2.INF:
		return Vector2.ZERO
	return (look_at_point - global_position).rotated(-global_rotation).normalized()


## Draws an egg centred on the origin of `ci`. Also used by the menus.
static func draw_egg(ci: CanvasItem, skin_id: int, is_broken := false, is_happy := false,
		look := Vector2.ZERO, blinking := false, time := 0.0, scared := 0.0, broken_t := 9.0) -> void:
	var s: Dictionary = SKINS[clampi(skin_id, 0, SKINS.size() - 1)]
	var shell: Color = s.shell
	var shade: Color = s.shade
	var mark: Color = s.mark
	var line := shade.darkened(0.35)
	var ink := Color("2b2d42")

	if is_broken:
		_draw_broken(ci, shell, shade, line, ink, broken_t)
		return

	var pts := outline(28)
	ci.draw_colored_polygon(outline(28, 3.0), line)
	ci.draw_colored_polygon(pts, shell)
	# Soft shadow along the bottom-right.
	var shadow := PackedVector2Array()
	for i in range(3, 16):
		shadow.append(pts[i])
	for i in range(15, 2, -1):
		shadow.append(pts[i] * 0.82 + Vector2(-5, -6))
	ci.draw_colored_polygon(shadow, Color(shade, 0.75))

	match int(s.pattern):
		1:  # speckles
			for p: Vector2 in [Vector2(-12, -18), Vector2(10, -22), Vector2(15, 14), Vector2(-16, 16), Vector2(0, 24), Vector2(-3, -27)]:
				ci.draw_circle(p, 3.2, mark)
		2:  # stripes
			for y: float in [-20.0, 19.0]:
				var half: float = RX * (1.0 + 0.16 * (y / RY)) * sqrt(1.0 - pow(y / RY, 2)) - 3.0
				ci.draw_line(Vector2(-half, y), Vector2(half, y), mark, 5.0, true)
		3:  # sparkles
			for p: Vector2 in [Vector2(-13, -17), Vector2(13, 16), Vector2(-14, 18), Vector2(9, -24)]:
				var tw: float = 2.4 + 1.2 * sin(time * 3.0 + p.x)
				ci.draw_line(p - Vector2(tw * 2, 0), p + Vector2(tw * 2, 0), mark, 2.0, true)
				ci.draw_line(p - Vector2(0, tw * 2), p + Vector2(0, tw * 2), mark, 2.0, true)

	# Highlight.
	ci.draw_circle(Vector2(-10, -19), 5.0, Color(1, 1, 1, 0.55))

	var eye_l := Vector2(-9, -3)
	var eye_r := Vector2(9, -3)
	if is_happy:
		for e: Vector2 in [eye_l, eye_r]:
			ci.draw_arc(e + Vector2(0, 2), 5.5, PI * 1.1, PI * 1.9, 8, ink, 3.0, true)
		ci.draw_circle(Vector2(0, 9), 6.0, ink)
		ci.draw_rect(Rect2(-7, 2, 14, 7), shell)
		ci.draw_circle(Vector2(-17, 6), 4.5, Color(1, 0.45, 0.5, 0.45))
		ci.draw_circle(Vector2(17, 6), 4.5, Color(1, 0.45, 0.5, 0.45))
	else:
		var eye_r_size := 6.5 + 2.0 * scared
		for e: Vector2 in [eye_l, eye_r]:
			if blinking and scared < 0.3:
				ci.draw_line(e + Vector2(-5, 0), e + Vector2(5, 0), ink, 2.5, true)
			else:
				ci.draw_circle(e, eye_r_size, Color.WHITE)
				ci.draw_circle(e + look * (2.6 + scared), 3.4 - scared * 0.8, ink)
		if scared > 0.3:
			# Worried brows, an open mouth and a drop of sweat.
			ci.draw_line(eye_l + Vector2(-7, -9), eye_l + Vector2(5, -13), ink, 2.5, true)
			ci.draw_line(eye_r + Vector2(7, -9), eye_r + Vector2(-5, -13), ink, 2.5, true)
			ci.draw_circle(Vector2(0, 13), 3.0 + 3.0 * scared, ink)
			var slide := fmod(time * 1.4, 1.0)
			var drop := Vector2(21, -20 + slide * 22.0)
			var drop_col := Color(0.55, 0.85, 1.0, 1.0 - slide)
			ci.draw_circle(drop, 4.0, drop_col)
			ci.draw_colored_polygon(PackedVector2Array([drop + Vector2(-3.5, -1.5), drop + Vector2(0, -9), drop + Vector2(3.5, -1.5)]), drop_col)
		elif look != Vector2.ZERO:
			ci.draw_circle(Vector2(0, 11), 3.5, ink)
		else:
			ci.draw_arc(Vector2(0, 8), 5.0, PI * 0.15, PI * 0.85, 8, ink, 2.5, true)


## A cracked egg: the top of the shell flies off and the yolk spills out.
static func _draw_broken(ci: CanvasItem, shell: Color, shade: Color, line: Color, ink: Color, t: float) -> void:
	var zig := [Vector2(27, -2), Vector2(18, -12), Vector2(9, -1), Vector2(0, -12), Vector2(-9, -1), Vector2(-18, -12), Vector2(-27, -2)]
	var pts := outline(28)
	var grow := clampf(t / 0.25, 0.0, 1.0)

	# Egg white and yolk spilling over the edge.
	var white := Color(1, 1, 1, 0.95)
	ci.draw_circle(Vector2(-2, -6), 22.0 * grow, white)
	ci.draw_circle(Vector2(-22, 2), 12.0 * grow, white)
	ci.draw_circle(Vector2(21, 4), 13.0 * grow, white)
	ci.draw_circle(Vector2(24, 10 + 18.0 * clampf(t, 0.0, 1.0)), 7.0 * grow, white)
	ci.draw_circle(Vector2(3, -12), 12.0 * grow, Color("ffb300"))
	ci.draw_circle(Vector2(-1, -16), 3.5 * grow, Color(1, 1, 1, 0.7))

	# Bottom half of the shell.
	var bottom := PackedVector2Array()
	for p in pts:
		if p.y >= -2.0:
			bottom.append(p)
	bottom = _sorted_ring(bottom, zig)
	ci.draw_colored_polygon(bottom, shell)
	var rim := bottom.duplicate()
	rim.append(bottom[0])
	ci.draw_polyline(rim, line, 3.0, true)
	ci.draw_circle(Vector2(12, 22), 6.0, Color(shade, 0.6))
	for e: Vector2 in [Vector2(-10, 12), Vector2(10, 12)]:
		ci.draw_line(e + Vector2(-4.5, -4.5), e + Vector2(4.5, 4.5), ink, 3.0, true)
		ci.draw_line(e + Vector2(-4.5, 4.5), e + Vector2(4.5, -4.5), ink, 3.0, true)
	ci.draw_arc(Vector2(0, 28), 5.0, PI * 1.15, PI * 1.85, 8, ink, 2.5, true)

	# Top half tumbling away.
	if t < 1.4:
		var top := PackedVector2Array()
		for p in pts:
			if p.y <= -4.0:
				top.append(p)
		top = _sorted_ring(top, zig, true)
		var where := Vector2(-150.0 * t, -30.0 - 420.0 * t + 900.0 * t * t)
		var turn := -6.0 * t
		var moved := PackedVector2Array()
		for p in top:
			moved.append(where + (p + Vector2(0, 14)).rotated(turn))
		var fade := clampf(1.4 - t, 0.0, 1.0)
		ci.draw_colored_polygon(moved, Color(shell, fade))
		moved.append(moved[0])
		ci.draw_polyline(moved, Color(line, fade), 3.0, true)


## Joins the shell points on one side of the crack with the zigzag crack line.
static func _sorted_ring(side: PackedVector2Array, zig: Array, upper := false) -> PackedVector2Array:
	var arc := Array(side)
	if upper:
		# Top of the shell: left to right over the top.
		arc.sort_custom(func(a: Vector2, b: Vector2): return atan2(a.x, -a.y) < atan2(b.x, -b.y))
	else:
		# Bottom of the shell: right to left under the egg.
		arc.sort_custom(func(a: Vector2, b: Vector2): return atan2(a.x, a.y) > atan2(b.x, b.y))
	var out := PackedVector2Array(arc)
	var crack := zig.duplicate()
	if not upper:
		crack.reverse()
	for i in range(1, crack.size() - 1):
		var z: Vector2 = crack[i]
		out.append(z + (Vector2(0, -3) if upper else Vector2.ZERO))
	return out
