extends Node2D
## One level: terrain, chicks, the line the player draws and the hazards.
##
## The level is laid out in a 720 x 1280 area. The player draws one line
## (phase DRAW); when the finger lifts the line becomes a solid body, the chicks
## are released and the hazards start (phase RUN). The level is won when every
## chick is still whole after `time` seconds.

signal ink_changed(used: float, limit: float)
signal run_started
signal finished(won: bool)
signal sound(sound_name: String)

enum Phase { DRAW, RUN, DONE }

const Chick := preload("res://chick.gd")

const W := 720.0
const H := 1280.0
const LINE_W := 14.0
const MIN_STEP := 9.0
const MIN_LINE := 24.0
const INK := Color("2b2d42")
const HAIL_SPEED := 950.0
const BLAST_RADIUS := 215.0

## Sky top, sky bottom, far hills, near hills, dirt, grass - one set per chapter.
const PALETTES := [
	[Color("6ec6f1"), Color("d6f3ff"), Color("a9dba0"), Color("86c97b"), Color("9a6a44"), Color("69bf4f")],
	[Color("ffb37a"), Color("ffe9c4"), Color("e7b98a"), Color("d49a66"), Color("8a5a3c"), Color("b7b34a")],
	[Color("7d93b8"), Color("d3dfee"), Color("a8b8cc"), Color("8ea2bb"), Color("6f6f7e"), Color("e8f1f7")],
	[Color("5b4a8a"), Color("f2a6a0"), Color("7a5c93"), Color("614a7c"), Color("4a3a55"), Color("8e6fb0")],
	[Color("52c4b0"), Color("e3fbe8"), Color("9adfc0"), Color("6fcaa2"), Color("8b6b47"), Color("3fae7a")],
	[Color("28335e"), Color("7d6fb0"), Color("3a467a"), Color("2c3766"), Color("3b3350"), Color("5f8f7a")],
]

var data: Dictionary = {}
var skin := 0
var palette := 0
## A demo level only shows the scene; it takes no input and never starts.
var demo := false

var phase := Phase.DRAW
var won := false
var chicks: Array = []
var pts := PackedVector2Array()
var ink_used := 0.0
var ink_max := 800.0
var star3 := 300.0
var star2 := 500.0
var run_t := 0.0
var run_total := 6.0
var wind := 0.0
var line_body: RigidBody2D

var _line: Line2D
var _fx: Node2D
var _touch := -1
var _events: Array = []
var _hail: Dictionary = {}
var _hail_acc := 0.0
var _drops: Array = []
var _bombs: Array = []
var _movers: Array = []
var _bursts: Array = []
var _hint := PackedVector2Array()
var _streaks: Array = []
var _rng := RandomNumberGenerator.new()
var _time := 0.0
var _decor: Node2D
var _tufts: Array = []
var _parts: Array = []
var _shake := 0.0
var _flash := 0.0
var _vel := {}
var _pen_count := 0


func setup(level: Dictionary, skin_id := 0, palette_id := 0) -> void:
	data = level
	skin = skin_id
	palette = palette_id % PALETTES.size()
	demo = level.get("demo", false)


func _ready() -> void:
	_rng.seed = hash(str(data.get("chicks", [])) + str(data.get("rocks", [])))
	run_total = data.get("time", 6.0)
	wind = data.get("wind", 0.0)
	_hail = data.get("hail", {})

	var sol_len := path_length(data.get("sol", PackedVector2Array()))
	if sol_len <= 0.0:
		sol_len = 320.0
	star3 = data.get("s3", sol_len * 1.3 + 30.0)
	star2 = data.get("s2", sol_len * 1.9 + 60.0)
	ink_max = data.get("ink", maxf(sol_len * 2.7 + 100.0, 620.0))

	_build_terrain()
	_build_spikes()
	_build_chicks()

	_decor = Node2D.new()
	_decor.z_index = 1
	_decor.draw.connect(_draw_decor)
	add_child(_decor)

	_line = _new_line()
	add_child(_line)

	_fx = Node2D.new()
	_fx.z_index = 20
	_fx.draw.connect(_draw_fx)
	add_child(_fx)

	for r in data.get("rocks", []):
		var e: Dictionary = r.duplicate()
		e["kind"] = "rock"
		_events.append(e)
	for b in data.get("bombs", []):
		var e: Dictionary = b.duplicate()
		e["kind"] = "bomb"
		_events.append(e)
	_events.sort_custom(func(a, b): return a.t < b.t)

	if wind != 0.0:
		for i in 14:
			_streaks.append(Vector3(_rng.randf() * (W + 600.0) - 300.0, 150.0 + _rng.randf() * 800.0, 40.0 + _rng.randf() * 70.0))


# ---------------------------------------------------------------- building

func _new_line() -> Line2D:
	var l := Line2D.new()
	l.width = LINE_W
	l.default_color = INK
	l.joint_mode = Line2D.LINE_JOINT_ROUND
	l.begin_cap_mode = Line2D.LINE_CAP_ROUND
	l.end_cap_mode = Line2D.LINE_CAP_ROUND
	l.antialiased = true
	l.z_index = 5
	return l


func _build_terrain() -> void:
	var pal: Array = PALETTES[palette]
	var mat := PhysicsMaterial.new()
	mat.friction = 0.9
	for poly: PackedVector2Array in data.get("ground", []):
		var body := StaticBody2D.new()
		body.physics_material_override = mat
		var cp := CollisionPolygon2D.new()
		cp.polygon = poly
		body.add_child(cp)
		var vis := Polygon2D.new()
		vis.polygon = poly
		vis.color = pal[4]
		body.add_child(vis)
		# Darker outline all round, grass on every edge that faces up.
		var edge := Line2D.new()
		edge.points = poly
		edge.closed = true
		edge.width = 4.0
		edge.default_color = (pal[4] as Color).darkened(0.3)
		body.add_child(edge)
		for i in poly.size():
			var a := poly[i]
			var b := poly[(i + 1) % poly.size()]
			var n := (b - a).orthogonal().normalized()
			if Geometry2D.is_point_in_polygon((a + b) * 0.5 + n * 2.0, poly):
				n = -n
			if n.y < -0.3:
				var g := Line2D.new()
				g.points = PackedVector2Array([a - n * 5.0, b - n * 5.0])
				g.width = 16.0
				g.default_color = pal[5]
				g.begin_cap_mode = Line2D.LINE_CAP_ROUND
				g.end_cap_mode = Line2D.LINE_CAP_ROUND
				body.add_child(g)
				# Blades of grass that sway in the breeze.
				var run := a.distance_to(b)
				var along := 14.0 + fmod(a.x * 0.37, 20.0)
				while along < run and _tufts.size() < 90:
					var at := a.lerp(b, along / run)
					if at.x > -160.0 and at.x < W + 160.0:
						_tufts.append([at + n * 10.0, n, at.x * 0.05])
					along += 44.0
		add_child(body)


func _build_spikes() -> void:
	# Each entry is [x, y, width]: a strip of spikes standing on y.
	for s: Array in data.get("spikes", []):
		var x: float = s[0]
		var y: float = s[1]
		var w: float = s[2]
		var body := StaticBody2D.new()
		body.add_to_group("hazard")
		var cs := CollisionShape2D.new()
		var rect := RectangleShape2D.new()
		rect.size = Vector2(w, 26.0)
		cs.shape = rect
		cs.position = Vector2(x + w * 0.5, y - 13.0)
		body.add_child(cs)
		var count := maxi(1, int(round(w / 30.0)))
		var step := w / count
		for i in count:
			var tooth := Polygon2D.new()
			tooth.polygon = PackedVector2Array([
				Vector2(x + i * step, y), Vector2(x + (i + 0.5) * step, y - 36.0), Vector2(x + (i + 1) * step, y)])
			tooth.color = Color("d9dde6") if i % 2 == 0 else Color("b9bfcc")
			body.add_child(tooth)
			var shine := Line2D.new()
			shine.points = PackedVector2Array([Vector2(x + i * step, y), Vector2(x + (i + 0.5) * step, y - 36.0), Vector2(x + (i + 1) * step, y)])
			shine.width = 2.5
			shine.default_color = Color("6d7382")
			body.add_child(shine)
		add_child(body)


func _build_chicks() -> void:
	for p: Vector2 in data.get("chicks", []):
		var chick: RigidBody2D = Chick.new()
		chick.skin = skin
		chick.position = p
		chick.freeze_mode = RigidBody2D.FREEZE_MODE_STATIC
		chick.freeze = true
		chick.z_index = 3
		chick.got_hurt.connect(_on_chick_hurt.bind(chick))
		add_child(chick)
		chicks.append(chick)
		chick.bump(1.4)


# ---------------------------------------------------------------- drawing

func _unhandled_input(event: InputEvent) -> void:
	if demo or phase != Phase.DRAW:
		return
	if event is InputEventScreenTouch:
		if event.pressed and _touch == -1:
			_touch = event.index
			_clear_line()
			_add_point(make_input_local(event).position)
		elif not event.pressed and event.index == _touch:
			_touch = -1
			_release()
	elif event is InputEventScreenDrag and event.index == _touch:
		_add_point(make_input_local(event).position)


func _clear_line() -> void:
	pts = PackedVector2Array()
	ink_used = 0.0
	_line.points = pts
	ink_changed.emit(ink_used, ink_max)


## Adds a point to the line if there is ink left and nothing solid is in the way.
func _add_point(p: Vector2) -> bool:
	if pts.is_empty():
		if not _is_free(p):
			return false
		pts.append(p)
	else:
		var last := pts[pts.size() - 1]
		var d := last.distance_to(p)
		if d < MIN_STEP:
			return true
		var left := ink_max - ink_used
		if left <= 0.5:
			return false
		if d > left:
			p = last + (p - last) * (left / d)
			d = left
		if not _is_clear(last, p) or not _is_free(p):
			return false
		pts.append(p)
		ink_used += d
		_pen_count += 1
		if _pen_count % 2 == 0:
			_add_part(p, Vector2.from_angle(randf() * TAU) * randf_range(20.0, 70.0), 0.35, randf_range(2.5, 5.0), INK, 160.0)
	_line.points = pts
	ink_changed.emit(ink_used, ink_max)
	return true


func _is_free(p: Vector2) -> bool:
	var q := PhysicsShapeQueryParameters2D.new()
	var c := CircleShape2D.new()
	c.radius = LINE_W * 0.5 - 1.0
	q.shape = c
	q.transform = Transform2D(0.0, to_global(p))
	return get_world_2d().direct_space_state.intersect_shape(q, 1).is_empty()


func _is_clear(a: Vector2, b: Vector2) -> bool:
	var q := PhysicsRayQueryParameters2D.create(to_global(a), to_global(b))
	q.hit_from_inside = true
	return get_world_2d().direct_space_state.intersect_ray(q).is_empty()


func _release() -> void:
	if ink_used < MIN_LINE:
		_clear_line()
		return
	_start_run()


func _start_run() -> void:
	if phase != Phase.DRAW:
		return
	phase = Phase.RUN
	_hint = PackedVector2Array()
	if pts.size() >= 2:
		_build_line_body()
		# The line flashes white as it turns solid.
		_line.default_color = Color.WHITE
		_line.width = LINE_W + 10.0
		var tw := create_tween().set_parallel(true)
		tw.tween_property(_line, "default_color", INK, 0.3)
		tw.tween_property(_line, "width", LINE_W, 0.3).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	for chick: RigidBody2D in chicks:
		chick.freeze = false
		chick.bump(-1.0)
	sound.emit("pop")
	run_started.emit()


func _build_line_body() -> void:
	var simple := simplify(pts, 2.0)
	var centre := Vector2.ZERO
	for p in simple:
		centre += p
	centre /= simple.size()

	line_body = RigidBody2D.new()
	line_body.position = centre
	var mat := PhysicsMaterial.new()
	mat.friction = 0.9
	mat.bounce = 0.0
	line_body.physics_material_override = mat
	line_body.mass = clampf(ink_used * 0.008, 0.8, 8.0)
	line_body.can_sleep = false
	for i in simple.size() - 1:
		var a := simple[i]
		var b := simple[i + 1]
		var seg := b - a
		var cap := CapsuleShape2D.new()
		cap.radius = LINE_W * 0.5
		cap.height = seg.length() + LINE_W
		var cs := CollisionShape2D.new()
		cs.shape = cap
		cs.position = (a + b) * 0.5 - centre
		cs.rotation = seg.angle() - PI * 0.5
		line_body.add_child(cs)
	add_child(line_body)
	remove_child(_line)
	_line.position = -centre
	line_body.add_child(_line)


# ---------------------------------------------------------------- running

func _physics_process(dt: float) -> void:
	if phase == Phase.DRAW:
		return
	for m: RigidBody2D in _movers:
		if is_instance_valid(m):
			_vel[m] = m.linear_velocity
	if phase == Phase.RUN:
		run_t += dt
		while not _events.is_empty() and _events[0].t <= run_t:
			_spawn(_events.pop_front())
		_spawn_hail(dt)
	_step_hail(dt)
	_step_bombs()
	_apply_wind()
	if phase == Phase.RUN:
		for chick: RigidBody2D in chicks:
			var p: Vector2 = chick.position
			if p.y > H + 160.0 or p.x < -90.0 or p.x > W + 90.0:
				chick.hurt()
		if phase == Phase.RUN and run_t >= run_total:
			_finish(true)


func _process(dt: float) -> void:
	_time += dt
	# Chicks watch whatever dangerous thing is closest, and get scared of it.
	for chick: RigidBody2D in chicks:
		var best := Vector2.INF
		var best_d := 560.0
		for m: RigidBody2D in _movers:
			if is_instance_valid(m):
				var d: float = m.global_position.distance_to(chick.global_position)
				if d < best_d:
					best_d = d
					best = m.global_position
		var scare := clampf(1.0 - best_d / 380.0, 0.0, 1.0) if best != Vector2.INF else 0.0
		if phase == Phase.RUN and not _drops.is_empty():
			scare = maxf(scare, 0.55)
			if best == Vector2.INF:
				best = chick.global_position + Vector2(0, -200)
		chick.look_at_point = best
		chick.fear = scare if phase == Phase.RUN else 0.0

	for b in _bursts:
		b.age += dt
	_bursts = _bursts.filter(func(b): return b.age < b.life)

	for part in _parts:
		part.age += dt
		var v: Vector2 = part.v
		v.y += part.g * dt
		v *= maxf(0.0, 1.0 - part.drag * dt)
		part.v = v
		part.p += v * dt
		part.rot += part.spin * dt
	_parts = _parts.filter(func(part): return part.age < part.life)

	for i in _streaks.size():
		var s: Vector3 = _streaks[i]
		s.x += signf(wind) * (500.0 + s.z * 4.0) * dt
		if s.x > W + 300.0:
			s.x = -300.0
		elif s.x < -300.0:
			s.x = W + 300.0
		_streaks[i] = s

	# Screen shake: a quick random jolt that dies away.
	_shake = maxf(0.0, _shake - dt * 45.0)
	_flash = maxf(0.0, _flash - dt * 2.5)
	position = Vector2(randf_range(-1.0, 1.0), randf_range(-1.0, 1.0)) * _shake

	queue_redraw()
	_decor.queue_redraw()
	_fx.queue_redraw()


func _spawn(e: Dictionary) -> void:
	if e.kind == "rock":
		_spawn_rock(e)
	else:
		_spawn_bomb(e)


func _spawn_rock(e: Dictionary) -> void:
	var r: float = e.get("r", 30.0)
	var body := RigidBody2D.new()
	body.add_to_group("hazard")
	var shape_pts := PackedVector2Array()
	var n := 8
	for i in n:
		var a := TAU * i / n + _rng.randf_range(-0.15, 0.15)
		shape_pts.append(Vector2.from_angle(a) * r * _rng.randf_range(0.82, 1.0))
	var shape := ConvexPolygonShape2D.new()
	shape.set_point_cloud(shape_pts)
	var cs := CollisionShape2D.new()
	cs.shape = shape
	body.add_child(cs)
	var vis := Polygon2D.new()
	vis.polygon = shape_pts
	vis.color = Color("8d8d99")
	body.add_child(vis)
	var light := Polygon2D.new()
	light.polygon = PackedVector2Array([shape_pts[4] * 0.85, shape_pts[5] * 0.9, shape_pts[6] * 0.85, shape_pts[5] * 0.25])
	light.color = Color("b3b3bd")
	body.add_child(light)
	var edge := Line2D.new()
	edge.points = shape_pts
	edge.closed = true
	edge.width = 4.0
	edge.default_color = Color("55555f")
	edge.joint_mode = Line2D.LINE_JOINT_ROUND
	body.add_child(edge)
	var mat := PhysicsMaterial.new()
	mat.friction = 0.7
	mat.bounce = 0.15
	body.physics_material_override = mat
	body.mass = 2.2 * pow(r / 30.0, 2.0)
	body.continuous_cd = RigidBody2D.CCD_MODE_CAST_RAY
	body.can_sleep = false
	body.position = Vector2(e.x, e.get("y", -90.0))
	body.linear_velocity = Vector2(e.get("vx", 0.0), e.get("vy", 0.0))
	body.angular_velocity = e.get("spin", _rng.randf_range(-2.0, 2.0))
	body.z_index = 2
	body.set_meta("r", r)
	body.contact_monitor = true
	body.max_contacts_reported = 4
	body.body_entered.connect(_on_hazard_hit.bind(body))
	add_child(body)
	_movers.append(body)


func _spawn_bomb(e: Dictionary) -> void:
	var body := RigidBody2D.new()
	body.add_to_group("hazard")
	var shape := CircleShape2D.new()
	shape.radius = 24.0
	var cs := CollisionShape2D.new()
	cs.shape = shape
	body.add_child(cs)
	var ball := Polygon2D.new()
	var circle := PackedVector2Array()
	for i in 20:
		circle.append(Vector2.from_angle(TAU * i / 20.0) * 24.0)
	ball.polygon = circle
	ball.color = Color("23232e")
	body.add_child(ball)
	var shine := Polygon2D.new()
	var dot := PackedVector2Array()
	for i in 10:
		dot.append(Vector2(-8, -9) + Vector2.from_angle(TAU * i / 10.0) * 5.0)
	shine.polygon = dot
	shine.color = Color(1, 1, 1, 0.5)
	body.add_child(shine)
	var fuse := Line2D.new()
	fuse.points = PackedVector2Array([Vector2(0, -22), Vector2(4, -32), Vector2(11, -36)])
	fuse.width = 4.0
	fuse.default_color = Color("c9a15a")
	body.add_child(fuse)
	var spark := Polygon2D.new()
	var sp := PackedVector2Array()
	for i in 8:
		sp.append(Vector2(11, -36) + Vector2.from_angle(TAU * i / 8.0) * (7.0 if i % 2 == 0 else 3.0))
	spark.polygon = sp
	spark.color = Color("ffb300")
	body.add_child(spark)
	var mat := PhysicsMaterial.new()
	mat.friction = 0.8
	mat.bounce = 0.2
	body.physics_material_override = mat
	body.mass = 2.0
	body.continuous_cd = RigidBody2D.CCD_MODE_CAST_RAY
	body.can_sleep = false
	body.position = Vector2(e.x, e.get("y", -90.0))
	body.linear_velocity = Vector2(e.get("vx", 0.0), e.get("vy", 0.0))
	body.z_index = 2
	body.set_meta("r", 24.0)
	body.contact_monitor = true
	body.max_contacts_reported = 4
	body.body_entered.connect(_on_hazard_hit.bind(body))
	add_child(body)
	_movers.append(body)
	_bombs.append({"body": body, "at": run_t + float(e.get("fuse", 2.0)), "ball": ball})


func _step_bombs() -> void:
	for b in _bombs.duplicate():
		var body: RigidBody2D = b.body
		var left: float = b.at - run_t
		if phase == Phase.RUN and left <= 0.0:
			_bombs.erase(b)
			_explode(body)
			continue
		# Blink red, faster as the fuse runs out.
		var rate := 4.0 if left > 1.0 else 12.0
		(b.ball as Polygon2D).color = Color("23232e").lerp(Color("e53935"), 0.5 + 0.5 * sin(run_t * rate * TAU * 0.5))


func _explode(bomb: RigidBody2D) -> void:
	var c := bomb.position
	var space := get_world_2d().direct_space_state
	for chick: RigidBody2D in chicks:
		if chick.position.distance_to(c) > BLAST_RADIUS:
			continue
		# The blast only reaches an chick it can "see".
		var q := PhysicsRayQueryParameters2D.create(to_global(c), chick.global_position)
		q.exclude = [bomb.get_rid()]
		var hit := space.intersect_ray(q)
		if hit.is_empty() or hit.collider == chick:
			chick.hurt()
	var pushed: Array = _movers.duplicate()
	if line_body:
		pushed.append(line_body)
	for body: RigidBody2D in pushed:
		if body == bomb or not is_instance_valid(body):
			continue
		var v := body.global_position - to_global(c)
		var d := v.length()
		if d < BLAST_RADIUS * 1.4:
			var power := 420.0 * (1.0 - d / (BLAST_RADIUS * 1.4))
			body.apply_central_impulse((v.normalized() + Vector2(0, -0.35)) * power * body.mass)
	_bursts.append({"pos": c, "age": 0.0, "life": 0.4, "size": BLAST_RADIUS, "color": Color("ff9800")})
	_bursts.append({"pos": c, "age": 0.0, "life": 0.28, "size": BLAST_RADIUS * 0.6, "color": Color("fff3c4")})
	_bursts.append({"pos": c, "age": 0.0, "life": 0.55, "size": BLAST_RADIUS * 1.25, "color": Color.WHITE, "ring": true})
	for i in 22:
		var dir := Vector2.from_angle(randf() * TAU)
		_add_part(c + dir * 20.0, dir * randf_range(120.0, 420.0) + Vector2(0, -120), randf_range(0.6, 1.1),
			randf_range(14.0, 30.0), Color(0.25, 0.25, 0.3).lerp(Color(0.6, 0.6, 0.65), randf()), -60.0, 2.5)
	for i in 18:
		var dir := Vector2.from_angle(randf() * TAU)
		_add_part(c, dir * randf_range(300.0, 800.0), randf_range(0.3, 0.6), randf_range(4.0, 8.0),
			Color("ffd54f").lerp(Color("ff5722"), randf()), 900.0, 1.0)
	_shake = 20.0
	_flash = 0.6
	_movers.erase(bomb)
	bomb.queue_free()
	sound.emit("boom")


func _spawn_hail(dt: float) -> void:
	if _hail.is_empty() or run_t < float(_hail.get("from", 0.4)) or run_t > float(_hail.get("to", run_total - 1.0)):
		return
	_hail_acc += float(_hail.get("rate", 30.0)) * dt
	while _hail_acc >= 1.0:
		_hail_acc -= 1.0
		var slant: float = _hail.get("slant", 0.0)
		var x := _rng.randf_range(_hail.get("x0", -100.0), _hail.get("x1", W + 100.0))
		_drops.append({"p": Vector2(x, -30.0 - _rng.randf() * 40.0), "v": Vector2(slant, 1.0).normalized() * HAIL_SPEED})


func _step_hail(dt: float) -> void:
	if _drops.is_empty():
		return
	var space := get_world_2d().direct_space_state
	var keep: Array = []
	for d in _drops:
		var from: Vector2 = d.p
		var to: Vector2 = from + d.v * dt
		var q := PhysicsRayQueryParameters2D.create(to_global(from), to_global(to))
		var hit := space.intersect_ray(q)
		if not hit.is_empty():
			if hit.collider is Chick and phase == Phase.RUN:
				(hit.collider as RigidBody2D).hurt()
			if _parts.size() < 260:
				var at := to_local(hit.position)
				for i in 2:
					_add_part(at, Vector2(randf_range(-150.0, 150.0), randf_range(-320.0, -140.0)), 0.3,
						randf_range(2.5, 4.5), Color("f4fbff"), 1400.0)
			continue
		d.p = to
		if to.y < H + 300.0:
			keep.append(d)
	_drops = keep


func _apply_wind() -> void:
	if wind == 0.0 or phase == Phase.DRAW:
		return
	var bodies: Array = _movers.duplicate()
	bodies.append_array(chicks)
	if line_body:
		bodies.append(line_body)
	for body: RigidBody2D in bodies:
		if is_instance_valid(body):
			body.apply_central_force(Vector2(wind * body.mass, 0.0))


func _on_chick_hurt(chick: RigidBody2D) -> void:
	if phase != Phase.RUN:
		return
	sound.emit("hurt")
	var at: Vector2 = chick.position
	# Feathers fly.
	var feather: Color = Chick.SKINS[clampi(skin, 0, Chick.SKINS.size() - 1)].shell
	for i in 16:
		var dir := Vector2.from_angle(randf() * TAU)
		_add_part(at + dir * 12.0, dir * randf_range(120.0, 460.0) + Vector2(0, -160), randf_range(0.5, 0.9),
			randf_range(4.0, 9.0), feather.lerp(Color.WHITE, 0.5 if i % 3 == 0 else 0.0), 700.0, 1.5, 1)
	_shake = maxf(_shake, 12.0)
	_flash = maxf(_flash, 0.35)
	_finish(false)


## A hazard hit something hard: kick up dust and shake the screen.
func _on_hazard_hit(_other: Node, body: RigidBody2D) -> void:
	var before: Vector2 = _vel.get(body, Vector2.ZERO)
	var speed := before.length()
	var last: float = body.get_meta("hit_at", -1.0)
	if speed < 280.0 or _time - last < 0.18:
		return
	body.set_meta("hit_at", _time)
	var r: float = body.get_meta("r", 30.0)
	var at := body.position + before.normalized() * r
	var side := before.normalized().orthogonal()
	for i in int(clampf(speed / 130.0, 4.0, 12.0)):
		var way := side * (1.0 if i % 2 == 0 else -1.0)
		_add_part(at, way * randf_range(60.0, 260.0) + Vector2(0, randf_range(-140.0, -20.0)), randf_range(0.35, 0.7),
			randf_range(7.0, 16.0), Color(0.93, 0.9, 0.82, 0.9), -40.0, 3.0)
	_shake = maxf(_shake, clampf(speed / 170.0 * r / 30.0, 2.0, 12.0))
	sound.emit("thud")


func _finish(is_win: bool) -> void:
	if phase == Phase.DONE:
		return
	phase = Phase.DONE
	won = is_win
	if is_win:
		for chick: RigidBody2D in chicks:
			chick.happy = true
			chick.fear = 0.0
			for i in 12:
				var dir := Vector2.from_angle(TAU * i / 12.0)
				_add_part(chick.position + dir * 30.0, dir * randf_range(160.0, 300.0), 0.6, 7.0, Color("ffe066"), 0.0, 2.0)
		_confetti()
	finished.emit(is_win)


func _confetti() -> void:
	var colours := [Color("ff5252"), Color("ffca28"), Color("66bb6a"), Color("42a5f5"), Color("ab47bc"), Color("ff7043"), Color.WHITE]
	for i in 110:
		_add_part(Vector2(randf_range(-40.0, W + 40.0), randf_range(-420.0, -20.0)),
			Vector2(randf_range(-80.0, 80.0), randf_range(120.0, 380.0)), randf_range(2.6, 4.0),
			randf_range(10.0, 18.0), colours[i % colours.size()], 260.0, 0.5, 1)


## shape 0 is a soft dot, 1 a tumbling square (confetti, feathers).
func _add_part(p: Vector2, v: Vector2, life: float, size: float, col: Color, g := 0.0, drag := 0.0, shape := 0) -> void:
	if _parts.size() > 420:
		return
	_parts.append({"p": p, "v": v, "age": 0.0, "life": life, "size": size, "col": col, "g": g, "drag": drag,
		"shape": shape, "rot": randf() * TAU, "spin": randf_range(-9.0, 9.0)})


# ---------------------------------------------------------------- helpers

func stars() -> int:
	if ink_used <= star3:
		return 3
	if ink_used <= star2:
		return 2
	return 1


func time_left() -> float:
	return maxf(0.0, run_total - run_t)


## Shows the stored solution as a dotted guide.
func show_hint() -> void:
	_hint = data.get("sol", PackedVector2Array())


## Draws `points` as if a finger had traced them, then lifts the finger.
## Returns how many points were refused. Used by the level checker.
func auto_draw(points: PackedVector2Array) -> int:
	var refused := 0
	_clear_line()
	for p in points:
		if not _add_point(p):
			refused += 1
	_start_run()
	return refused


static func path_length(p: PackedVector2Array) -> float:
	var total := 0.0
	for i in range(1, p.size()):
		total += p[i - 1].distance_to(p[i])
	return total


## Ramer-Douglas-Peucker: drops points that are within `eps` of a straight run.
static func simplify(p: PackedVector2Array, eps: float) -> PackedVector2Array:
	if p.size() < 3:
		return p
	var keep := PackedByteArray()
	keep.resize(p.size())
	keep[0] = 1
	keep[p.size() - 1] = 1
	var stack: Array = [[0, p.size() - 1]]
	while not stack.is_empty():
		var span: Array = stack.pop_back()
		var a: int = span[0]
		var b: int = span[1]
		var worst := -1
		var worst_d := eps
		for i in range(a + 1, b):
			var closest := Geometry2D.get_closest_point_to_segment(p[i], p[a], p[b])
			var d := closest.distance_to(p[i])
			if d > worst_d:
				worst_d = d
				worst = i
		if worst != -1:
			keep[worst] = 1
			stack.append([a, worst])
			stack.append([worst, b])
	var out := PackedVector2Array()
	for i in p.size():
		if keep[i] == 1:
			out.append(p[i])
	return out


# ---------------------------------------------------------------- drawing

func _draw() -> void:
	var pal: Array = PALETTES[palette]
	var top: Color = pal[0]
	var bottom: Color = pal[1]
	draw_rect(Rect2(-2000, -1500, 4720, 1500), top)
	draw_polygon(
		PackedVector2Array([Vector2(-2000, 0), Vector2(2720, 0), Vector2(2720, H), Vector2(-2000, H)]),
		PackedColorArray([top, top, bottom, bottom]))
	draw_rect(Rect2(-2000, H, 4720, 2000), bottom)

	# Sun with slowly turning rays and a soft glow.
	var sun := Vector2(565, 300)
	for i in 12:
		var a := _time * 0.12 + TAU * i / 12.0
		var far := 150.0 + 16.0 * sin(_time * 1.3 + i)
		draw_colored_polygon(PackedVector2Array([
			sun + Vector2.from_angle(a - 0.09) * 60.0,
			sun + Vector2.from_angle(a) * far,
			sun + Vector2.from_angle(a + 0.09) * 60.0]), Color(1, 1, 0.85, 0.16))
	var glow := Color(1, 1, 0.85, 0.12)
	for r: float in [120.0, 95.0, 72.0]:
		draw_circle(sun, r + 4.0 * sin(_time * 1.6), glow)
	draw_circle(sun, 54.0, Color(1, 0.97, 0.8))

	# Clouds drift across and wrap round.
	for c: Vector3 in [Vector3(130, 330, 1.0), Vector3(470, 470, 0.75), Vector3(-160, 520, 0.9), Vector3(840, 250, 1.1)]:
		var x := fposmod(c.x + _time * 14.0 * c.z + 300.0, W + 600.0) - 300.0
		_draw_cloud(Vector2(x, c.y + 5.0 * sin(_time * 0.6 + c.x)), c.z)

	_draw_hills(790.0, 70.0, 0.0061, 1.3, pal[2])
	_draw_hills(880.0, 50.0, 0.0093, 4.1, pal[3])


func _draw_cloud(at: Vector2, s: float) -> void:
	var col := (PALETTES[palette][0] as Color).lerp(Color.WHITE, 0.82)
	draw_circle(at, 34.0 * s, col)
	draw_circle(at + Vector2(38, 8) * s, 27.0 * s, col)
	draw_circle(at + Vector2(-38, 10) * s, 24.0 * s, col)
	draw_rect(Rect2(at + Vector2(-40, 8) * s, Vector2(80, 26) * s), col)


func _draw_hills(base: float, height: float, freq: float, shift: float, col: Color) -> void:
	var p := PackedVector2Array()
	var x := -2000.0
	while x <= 2720.0:
		p.append(Vector2(x, base - height * (0.5 + 0.5 * sin(x * freq + shift)) - height * 0.3 * sin(x * freq * 2.7 + shift)))
		x += 40.0
	p.append(Vector2(2720, 3300))
	p.append(Vector2(-2000, 3300))
	draw_colored_polygon(p, col)


func _draw_decor() -> void:
	var col: Color = (PALETTES[palette][5] as Color).lightened(0.12)
	for t: Array in _tufts:
		var at: Vector2 = t[0]
		var n: Vector2 = t[1]
		var sway := 0.22 * sin(_time * 2.0 + float(t[2])) + clampf(wind / 1500.0, -0.5, 0.5)
		for k: float in [-0.45, 0.0, 0.45]:
			_decor.draw_line(at - n * 4.0, at + (n * (13.0 - absf(k) * 6.0)).rotated(k + sway), col, 3.0, true)


func _draw_fx() -> void:
	if _hint.size() > 1 and phase == Phase.DRAW:
		var total := path_length(_hint)
		var dist := fmod(_time * 40.0, 24.0)
		while dist < total:
			_fx.draw_circle(_point_along(_hint, dist), 5.0, Color(1, 1, 1, 0.95))
			_fx.draw_circle(_point_along(_hint, dist), 3.0, Color("ff7043"))
			dist += 24.0
		# A pen tip tracing the line shows which way to draw.
		var tip := _point_along(_hint, fmod(_time * 260.0, total + 200.0))
		_fx.draw_circle(tip, 13.0, Color(1, 1, 1, 0.9))
		_fx.draw_circle(tip, 8.0, Color("ff7043"))

	# Storm clouds wherever hail is going to fall.
	if not _hail.is_empty() and not demo:
		var x0: float = maxf(_hail.get("x0", -100.0), -200.0)
		var x1: float = minf(_hail.get("x1", W + 100.0), W + 200.0)
		var x := x0
		var i := 0
		while x <= x1:
			var at := Vector2(x + 14.0 * sin(_time * 0.7 + i), 196.0 + 7.0 * sin(_time * 1.1 + i * 1.7))
			var dark := Color(0.36, 0.4, 0.5, 0.92)
			_fx.draw_circle(at, 52.0, dark)
			_fx.draw_circle(at + Vector2(48, 14), 40.0, dark)
			_fx.draw_circle(at + Vector2(-48, 14), 40.0, dark)
			x += 120.0
			i += 1

	for s: Vector3 in _streaks:
		_fx.draw_line(Vector2(s.x, s.y), Vector2(s.x - signf(wind) * s.z, s.y), Color(1, 1, 1, 0.45), 3.0, true)

	# Speed lines behind anything falling fast.
	for m: RigidBody2D in _movers:
		if not is_instance_valid(m):
			continue
		var v := m.linear_velocity
		if v.length() > 650.0:
			var back := -v.normalized()
			var side := back.orthogonal()
			var r: float = m.get_meta("r", 30.0)
			for k: float in [-0.6, 0.0, 0.6]:
				var from := m.position + side * r * k + back * r
				_fx.draw_line(from, from + back * (r * 1.6 + v.length() * 0.03), Color(1, 1, 1, 0.3), 4.0, true)

	for d in _drops:
		var p: Vector2 = d.p
		var v: Vector2 = d.v
		_fx.draw_line(p - v.normalized() * 22.0, p, Color(1, 1, 1, 0.35), 4.0)
		_fx.draw_circle(p, 5.5, Color("f4fbff"))
		_fx.draw_circle(p, 5.5, Color("9cc7e6"), false, 1.5)

	for b in _bursts:
		var k: float = b.age / b.life
		var col: Color = b.color
		col.a = 1.0 - k
		if b.get("ring", false):
			_fx.draw_arc(b.pos, b.size * (0.2 + 0.8 * sqrt(k)), 0.0, TAU, 40, col, 10.0 * (1.0 - k) + 2.0, true)
		else:
			_fx.draw_circle(b.pos, b.size * (0.25 + 0.75 * k), col)

	for part in _parts:
		var k: float = part.age / part.life
		var col: Color = part.col
		col.a *= 1.0 - k * k
		if part.shape == 1:
			_fx.draw_set_transform(part.p, part.rot, Vector2(1.0, cos(part.age * 9.0 + part.rot)))
			_fx.draw_rect(Rect2(-part.size * 0.5, -part.size * 0.3, part.size, part.size * 0.6), col)
			_fx.draw_set_transform(Vector2.ZERO)
		else:
			_fx.draw_circle(part.p, part.size * (1.0 - 0.4 * k), col)

	# Where danger is about to come from.
	if not demo and phase != Phase.DONE:
		for e: Dictionary in _events:
			var wait: float = float(e.t) - run_t
			if phase == Phase.RUN and wait > 1.0:
				continue
			var y: float = e.get("y", -90.0)
			var at := Vector2(clampf(e.x, 44.0, W - 44.0), 300.0 if y < 0.0 else clampf(y, 300.0, H - 300.0))
			var beat := 0.5 + 0.5 * sin(_time * (16.0 if phase == Phase.RUN else 5.0) + float(e.t))
			_draw_warning(at, 0.45 + 0.5 * beat, 1.0 + 0.12 * beat)

	if _flash > 0.0:
		_fx.draw_rect(Rect2(-2000, -1500, 4720, 5000), Color(1, 1, 1, _flash))


func _draw_warning(at: Vector2, alpha: float, grow: float) -> void:
	var s := 26.0 * grow
	_fx.draw_colored_polygon(PackedVector2Array([at + Vector2(-s, -s * 0.8), at + Vector2(s, -s * 0.8), at + Vector2(0, s)]),
		Color(0.9, 0.2, 0.2, alpha))
	_fx.draw_line(at + Vector2(0, -s * 0.5), at + Vector2(0, s * 0.05), Color(1, 1, 1, alpha), 5.0)
	_fx.draw_circle(at + Vector2(0, s * 0.35), 3.0, Color(1, 1, 1, alpha))


func _point_along(p: PackedVector2Array, dist: float) -> Vector2:
	for i in range(1, p.size()):
		var seg := p[i - 1].distance_to(p[i])
		if dist <= seg:
			return p[i - 1].lerp(p[i], dist / maxf(seg, 0.001))
		dist -= seg
	return p[p.size() - 1]
