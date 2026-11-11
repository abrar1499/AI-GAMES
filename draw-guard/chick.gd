extends RigidBody2D
## The little chick the player has to keep safe. One touch from a hazard and
## it is out of the game (dizzy, not harmed: stars spin round its head).

signal got_hurt

const RX := 27.0
const RY := 35.0
const BEAK := Color("ff9f1c")
const INK := Color("2b2d42")

## Colours for each skin: body, shade, extra colour, extra type.
## Extras: 0 none, 1 speckles, 2 a bow, 3 sparkles.
const SKINS := [
	{"name": "Sunny", "price": 0, "shell": Color("ffd93b"), "shade": Color("f2b01e"), "mark": Color("f2b01e"), "pattern": 0},
	{"name": "Sky", "price": 150, "shell": Color("8fd3f4"), "shade": Color("56a9d6"), "mark": Color("ffffff"), "pattern": 1},
	{"name": "Rosie", "price": 300, "shell": Color("ffb3cb"), "shade": Color("ee7fa4"), "mark": Color("e5395f"), "pattern": 2},
	{"name": "Minty", "price": 500, "shell": Color("a8e6a1"), "shade": Color("6cc071"), "mark": Color("3f8f4a"), "pattern": 1},
	{"name": "Snowy", "price": 800, "shell": Color("fdfdfd"), "shade": Color("cfd8e6"), "mark": Color("42a5f5"), "pattern": 2},
	{"name": "Cosmo", "price": 1200, "shell": Color("9a7be8"), "shade": Color("6748b8"), "mark": Color("ffe98a"), "pattern": 3},
]

var skin := 0
var is_hurt := false
var happy := false
## Where the chick is looking, in world space (the nearest danger).
var look_at_point := Vector2.INF
## 0 = calm, 1 = terrified. Set by the level from how close danger is.
var fear := 0.0

var _blink := 0.0
var _next_blink := 2.0
var _time := 0.0
var _squash := 0.0
var _squash_v := 0.0
var _fear_now := 0.0
var _hurt_t := 0.0
var _happy_t := 0.0


## The body outline, a little wider at the bottom. Also the collision shape.
static func outline(n := 22, grow := 0.0) -> PackedVector2Array:
	var pts := PackedVector2Array()
	for i in n:
		var a := TAU * i / n
		var y := -cos(a) * (RY + grow)
		var widen := 1.0 + 0.16 * (y / RY)
		pts.append(Vector2(sin(a) * (RX + grow) * widen, y))
	return pts


static func ellipse(centre: Vector2, rx: float, ry: float, turn := 0.0, n := 14) -> PackedVector2Array:
	var pts := PackedVector2Array()
	for i in n:
		var a := TAU * i / n
		pts.append(centre + Vector2(cos(a) * rx, sin(a) * ry).rotated(turn))
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
	# A low centre of mass keeps the chick standing on flat ground.
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
	# A soft spring makes the chick wobble like jelly when something bumps it.
	_squash_v += (-_squash * 220.0 - _squash_v * 11.0) * delta
	_squash += _squash_v * delta
	_fear_now = lerpf(_fear_now, fear, minf(1.0, delta * 8.0))
	if is_hurt:
		_hurt_t += delta
	if happy:
		_happy_t += delta
	queue_redraw()


## Squash the chick; positive flattens it first.
func bump(amount: float) -> void:
	_squash_v += amount * 9.0


func _on_body_entered(body: Node) -> void:
	bump(clampf(linear_velocity.length() / 500.0, 0.25, 1.2))
	if body.is_in_group("hazard"):
		hurt()


func hurt() -> void:
	if is_hurt or freeze:
		return
	is_hurt = true
	bump(2.0)
	got_hurt.emit()
	queue_redraw()


func _draw() -> void:
	var breathe := 0.025 * sin(_time * 2.6)
	var sq := clampf(_squash, -0.45, 0.45) * 0.3 + breathe
	if is_hurt:
		sq += 0.1
	var sx := 1.0 + sq
	var sy := 1.0 - sq
	var offset := Vector2(0, RY * (1.0 - sy))
	if _fear_now > 0.05 and not is_hurt and not happy:
		offset.x += sin(_time * 58.0) * 1.8 * _fear_now
	if happy:
		# Jump for joy, straight up whatever way the chick is lying.
		offset += Vector2(0, -absf(sin(_happy_t * 7.5)) * 20.0).rotated(-global_rotation)
	draw_set_transform(offset, 0.0, Vector2(sx, sy))
	draw_chick(self, skin, is_hurt, happy, _look_dir(), _blink > 0.0, _time, _fear_now, _hurt_t)


func _look_dir() -> Vector2:
	if look_at_point == Vector2.INF:
		return Vector2.ZERO
	return (look_at_point - global_position).rotated(-global_rotation).normalized()


## Draws a chick centred on the origin of `ci`. Also used by the menus.
static func draw_chick(ci: CanvasItem, skin_id: int, hurt_now := false, is_happy := false,
		look := Vector2.ZERO, blinking := false, time := 0.0, scared := 0.0, hurt_t := 0.0) -> void:
	var s: Dictionary = SKINS[clampi(skin_id, 0, SKINS.size() - 1)]
	var body: Color = s.shell
	var shade: Color = s.shade
	var mark: Color = s.mark
	var line := shade.darkened(0.3)

	# Feet.
	for fx: float in [-10.0, 10.0]:
		var foot := Vector2(fx, RY - 2.0)
		for toe: float in [-7.0, 0.0, 7.0]:
			ci.draw_line(foot, foot + Vector2(toe, 9.0), BEAK.darkened(0.15), 3.5, true)

	# Wings: tucked in when scared, flapping when happy, drooping when hurt.
	var lift := 0.3
	if is_happy:
		lift = 0.9 + 0.8 * sin(time * 20.0)
	elif hurt_now:
		lift = -0.15
	elif scared > 0.3:
		lift = 0.05
	else:
		lift = 0.3 + 0.08 * sin(time * 2.6)
	for side: float in [-1.0, 1.0]:
		var root := Vector2(side * 24.0, 2.0)
		var wing := ellipse(root + Vector2(side * 5.0, 9.0).rotated(-side * lift), 9.0, 16.0, -side * (0.25 + lift))
		ci.draw_colored_polygon(ellipse(root + Vector2(side * 5.0, 9.0).rotated(-side * lift), 11.5, 18.5, -side * (0.25 + lift)), line)
		ci.draw_colored_polygon(wing, shade)

	# Tuft of feathers on the head.
	for tip: Vector2 in [Vector2(-8, -47), Vector2(1, -51), Vector2(9, -46)]:
		ci.draw_line(Vector2(0, -32), tip + Vector2(sin(time * 3.0 + tip.x) * 1.5, 0), line, 6.5, true)
		ci.draw_line(Vector2(0, -32), tip + Vector2(sin(time * 3.0 + tip.x) * 1.5, 0), body, 3.5, true)

	# Body and a lighter tummy.
	ci.draw_colored_polygon(outline(28, 3.0), line)
	ci.draw_colored_polygon(outline(28), body)
	ci.draw_colored_polygon(ellipse(Vector2(0, 14), 17.0, 16.0), body.lightened(0.45))
	ci.draw_circle(Vector2(-11, -21), 5.0, Color(1, 1, 1, 0.45))

	match int(s.pattern):
		1:  # speckles
			for p: Vector2 in [Vector2(-19, -8), Vector2(20, -12), Vector2(-22, 12), Vector2(23, 10), Vector2(-4, -27), Vector2(12, -25)]:
				ci.draw_circle(p, 2.8, mark)
		2:  # a bow
			var knot := Vector2(13, -29)
			ci.draw_colored_polygon(PackedVector2Array([knot, knot + Vector2(-13, -8), knot + Vector2(-12, 7)]), mark)
			ci.draw_colored_polygon(PackedVector2Array([knot, knot + Vector2(13, -8), knot + Vector2(12, 7)]), mark)
			ci.draw_circle(knot, 4.0, mark.darkened(0.2))
		3:  # sparkles
			for p: Vector2 in [Vector2(-19, -10), Vector2(19, 14), Vector2(-20, 16), Vector2(15, -24)]:
				var tw: float = 2.2 + 1.2 * sin(time * 3.0 + p.x)
				ci.draw_line(p - Vector2(tw * 2, 0), p + Vector2(tw * 2, 0), mark, 2.0, true)
				ci.draw_line(p - Vector2(0, tw * 2), p + Vector2(0, tw * 2), mark, 2.0, true)

	# Rosy cheeks.
	ci.draw_circle(Vector2(-17, 3), 4.5, Color(1, 0.4, 0.45, 0.4))
	ci.draw_circle(Vector2(17, 3), 4.5, Color(1, 0.4, 0.45, 0.4))

	var eye_l := Vector2(-10, -7)
	var eye_r := Vector2(10, -7)
	var beak_open := 0.0
	if hurt_now:
		for e: Vector2 in [eye_l, eye_r]:
			ci.draw_line(e + Vector2(-5, -5), e + Vector2(5, 5), INK, 3.0, true)
			ci.draw_line(e + Vector2(-5, 5), e + Vector2(5, -5), INK, 3.0, true)
		beak_open = 0.5
		# A bump on the head and stars going round it.
		ci.draw_circle(Vector2(-12, -30), 6.5, Color("ff8a80"))
		ci.draw_circle(Vector2(-13, -32), 2.0, Color(1, 1, 1, 0.7))
		for i in 3:
			var a := time * 5.0 + TAU * i / 3.0
			var at := Vector2(cos(a) * 26.0, -50.0 + sin(a) * 8.0)
			var star := PackedVector2Array()
			for k in 10:
				star.append(at + Vector2.from_angle(-PI * 0.5 + TAU * k / 10.0 + time * 3.0) * (7.5 if k % 2 == 0 else 3.3))
			ci.draw_colored_polygon(star, Color("ffe066"))
	elif is_happy:
		for e: Vector2 in [eye_l, eye_r]:
			ci.draw_arc(e + Vector2(0, 2), 5.5, PI * 1.1, PI * 1.9, 8, INK, 3.0, true)
		beak_open = 0.8
	else:
		for e: Vector2 in [eye_l, eye_r]:
			if blinking and scared < 0.3:
				ci.draw_line(e + Vector2(-5, 0), e + Vector2(5, 0), INK, 2.5, true)
			elif scared > 0.3:
				ci.draw_circle(e, 7.5 + scared, Color.WHITE)
				ci.draw_circle(e + look * 3.0, 3.0, INK)
			else:
				ci.draw_circle(e + look * 1.8, 5.0, INK)
				ci.draw_circle(e + look * 1.8 + Vector2(-1.6, -1.8), 1.7, Color.WHITE)
		if scared > 0.3:
			beak_open = scared
			ci.draw_line(eye_l + Vector2(-8, -10), eye_l + Vector2(5, -14), INK, 2.5, true)
			ci.draw_line(eye_r + Vector2(8, -10), eye_r + Vector2(-5, -14), INK, 2.5, true)
			var slide := fmod(time * 1.4, 1.0)
			var drop := Vector2(24, -24 + slide * 22.0)
			var drop_col := Color(0.55, 0.85, 1.0, 1.0 - slide)
			ci.draw_circle(drop, 4.0, drop_col)
			ci.draw_colored_polygon(PackedVector2Array([drop + Vector2(-3.5, -1.5), drop + Vector2(0, -9), drop + Vector2(3.5, -1.5)]), drop_col)

	# Beak: two halves that open to cheep.
	var gap := 6.0 * beak_open
	var beak_at := Vector2(0, 3)
	if gap > 0.5:
		ci.draw_colored_polygon(PackedVector2Array([beak_at + Vector2(-5, 0), beak_at + Vector2(5, 0), beak_at + Vector2(0, gap + 4.0)]), Color("7a2e1d"))
	ci.draw_colored_polygon(PackedVector2Array([beak_at + Vector2(-7, 1), beak_at + Vector2(0, -4), beak_at + Vector2(7, 1), beak_at + Vector2(0, 4.0 - gap * 0.3)]), BEAK)
	ci.draw_colored_polygon(PackedVector2Array([beak_at + Vector2(-5, 2.0 + gap), beak_at + Vector2(5, 2.0 + gap), beak_at + Vector2(0, 7.0 + gap)]), BEAK.darkened(0.12))
