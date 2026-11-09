extends Node3D
## Rickshaw Rush 3D
## Endless runner: the rickshaw drives forward on a 3-lane road.
## Swipe left/right to change lane, tap or swipe up to jump.
## Dodge cars, buses and buffaloes; jump over potholes and police barriers; pick up passengers.

enum State { READY, PLAYING, OVER }

const LANE_X := [-2.2, 0.0, 2.2]
const START_SPEED := 14.0
const MAX_SPEED := 34.0
const ACCEL := 0.35                # speed gained per second
const LANE_SWITCH_SPEED := 16.0
const JUMP_VELOCITY := 7.5
const GRAVITY := 24.0
const JUMP_CLEAR_HEIGHT := 0.35    # above this the rickshaw clears low obstacles
const PLAYER_HALF_LEN := 1.0
const PLAYER_HALF_WIDTH := 0.65

const TILE_LEN := 20.0
const TILE_COUNT := 9
const SPAWN_Z := -110.0
const DESPAWN_Z := 12.0
const PASSENGER_POINTS := 25
const SWIPE_MIN := 50.0
const SAVE_PATH := "user://rickshaw_best.save"

const SKY := Color(0.98, 0.66, 0.42)
const ROAD := Color(0.30, 0.30, 0.33)

var state: State = State.READY
var speed := START_SPEED
var distance := 0.0
var passengers := 0
var best := 0
var next_row_in := 30.0

var lane := 1
var player: Node3D
var body: Node3D               # tilts and bounces inside `player`
var wheels: Array[Node3D] = []
var player_y := 0.0
var player_vy := 0.0
var over_time := 0.0

var tiles: Array[Node3D] = []
## Each obstacle: { node, lane, half_len, jumpable, passenger }
var obstacles: Array[Dictionary] = []

var cam: Camera3D
var shake := 0.0

var touch_start := Vector2.ZERO
var touch_down := false
var swiped := false

var score_label: Label
var sawari_label: Label
var hint_label: Label
var over_label: Label
var dim_rect: ColorRect

var sfx: AudioStreamPlayer
var sfx2: AudioStreamPlayer
var tone_coin: AudioStreamWAV
var tone_jump: AudioStreamWAV
var tone_crash: AudioStreamWAV
var tone_horn: AudioStreamWAV

var mat_cache := {}


func _ready() -> void:
	randomize()
	best = _load_best()
	_build_world()
	_build_ui()
	_build_audio()
	for i in TILE_COUNT:
		var tile := Node3D.new()
		add_child(tile)
		tiles.append(tile)
		_dress_tile(tile, -i * TILE_LEN + TILE_LEN * 0.5)
	player = _make_rickshaw()
	add_child(player)
	player.position = Vector3(LANE_X[lane], 0.0, 0.0)
	hint_label.text = "RICKSHAW RUSH\n\n\nSwipe left / right to change lane\nTap to jump\n\nTAP TO START" \
		+ ("\n\nBEST  %d" % best if best > 0 else "")


# ---------------------------------------------------------------- game flow

func _start() -> void:
	state = State.PLAYING
	hint_label.visible = false
	score_label.visible = true
	sawari_label.visible = true
	_play(sfx2, tone_horn, 1.0)


func change_lane(dir: int) -> void:
	if state != State.PLAYING:
		return
	lane = clampi(lane + dir, 0, 2)


func jump() -> void:
	if state != State.PLAYING or player_y > 0.01:
		return
	player_vy = JUMP_VELOCITY
	_play(sfx, tone_jump, 1.0)


func _crash() -> void:
	state = State.OVER
	over_time = 0.0
	shake = 0.6
	var score := _score()
	var new_best := score > best
	if new_best:
		best = score
		_save_best()
	score_label.visible = false
	sawari_label.visible = false
	dim_rect.visible = true
	over_label.visible = true
	over_label.text = "TAKKAR!\n\n%d\n\n%d m   ·   %d sawari\n\n%s\n\n\nTap to play again" % [
		score, int(distance), passengers, "NEW BEST!" if new_best else "BEST  %d" % best]
	_play(sfx, tone_crash, 1.0)
	Input.vibrate_handheld(200)
	var tw := create_tween()
	tw.tween_property(body, "rotation", Vector3(-0.35, 0.0, 0.5), 0.25).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)


func _score() -> int:
	return int(distance) + passengers * PASSENGER_POINTS


# ---------------------------------------------------------------- per frame

func _process(delta: float) -> void:
	over_time += delta
	if state == State.PLAYING:
		speed = minf(speed + ACCEL * delta, MAX_SPEED)
		distance += speed * delta * 0.5
		_scroll_world(speed * delta)
		_update_player(delta)
		_check_hits()
		score_label.text = str(_score())
		sawari_label.text = "SAWARI  %d" % passengers
	elif state == State.READY:
		# Idle: the road crawls by behind the title.
		_scroll_world(4.0 * delta)
		_spin_wheels(4.0 * delta)
	_update_camera(delta)


func _scroll_world(dz: float) -> void:
	for tile in tiles:
		tile.position.z += dz
		if tile.position.z - TILE_LEN * 0.5 > DESPAWN_Z:
			_dress_tile(tile, tile.position.z - TILE_COUNT * TILE_LEN)

	for i in range(obstacles.size() - 1, -1, -1):
		var o := obstacles[i]
		var n: Node3D = o.node
		n.position.z += dz
		if o.passenger:
			n.get_child(0).position.y = 0.0 + sin(over_time * 6.0 + n.position.z) * 0.05
			n.get_child(1).rotation.y += dz * 0.3
		if n.position.z > DESPAWN_Z:
			n.queue_free()
			obstacles.remove_at(i)

	if state == State.PLAYING:
		next_row_in -= dz
		if next_row_in <= 0.0:
			_spawn_row()
			# Gap between rows grows with speed so there is always time to react.
			next_row_in = randf_range(15.0, 24.0) * (0.75 + speed / START_SPEED * 0.25)


func _update_player(delta: float) -> void:
	var tx: float = LANE_X[lane]
	var x := move_toward(player.position.x, tx, LANE_SWITCH_SPEED * delta)
	player.position.x = x

	player_vy -= GRAVITY * delta
	player_y = maxf(0.0, player_y + player_vy * delta)
	if player_y == 0.0:
		player_vy = 0.0
	player.position.y = player_y

	# Lean into lane changes, nose up while jumping, small engine vibration on the ground.
	body.rotation.z = lerpf(body.rotation.z, (x - tx) * 0.12, 1.0 - exp(-delta * 12.0))
	body.rotation.x = lerpf(body.rotation.x, player_vy * 0.03, 1.0 - exp(-delta * 10.0))
	body.position.y = 0.0 if player_y > 0.0 else sin(over_time * 40.0) * 0.015
	_spin_wheels(speed * delta)


func _spin_wheels(dz: float) -> void:
	for w in wheels:
		w.rotation.x -= dz / 0.3


func _check_hits() -> void:
	var px := player.position.x
	for i in range(obstacles.size() - 1, -1, -1):
		var o := obstacles[i]
		var n: Node3D = o.node
		if absf(n.position.x - px) > 1.1 + PLAYER_HALF_WIDTH * 0.5:
			continue
		if absf(n.position.z) > o.half_len + PLAYER_HALF_LEN:
			continue
		if o.passenger:
			passengers += 1
			_play(sfx, tone_coin, 1.0 + minf(passengers, 10) * 0.03)
			Input.vibrate_handheld(20)
			n.queue_free()
			obstacles.remove_at(i)
		elif o.jumpable and player_y > JUMP_CLEAR_HEIGHT:
			continue
		else:
			_crash()
			return


func _update_camera(delta: float) -> void:
	var px := player.position.x if player else 0.0
	var target := Vector3(px * 0.45, 4.6 + player_y * 0.3, 7.8)
	cam.position = cam.position.lerp(target, 1.0 - exp(-delta * 6.0))
	if shake > 0.0:
		shake = maxf(0.0, shake - delta)
		cam.position += Vector3(randf_range(-1, 1), randf_range(-1, 1), 0.0) * shake * 0.3
	cam.look_at(Vector3(px * 0.3, 1.0, -8.0))
	cam.fov = lerpf(cam.fov, 62.0 + (speed - START_SPEED) * 0.35, 1.0 - exp(-delta * 2.0))


func _unhandled_input(event: InputEvent) -> void:
	# Touch arrives as mouse events (emulate_mouse_from_touch is on by default).
	if event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT:
		if event.pressed:
			touch_down = true
			swiped = false
			touch_start = event.position
		else:
			touch_down = false
			if not swiped:
				_tap()
	elif event is InputEventMouseMotion and touch_down and not swiped:
		var d: Vector2 = event.position - touch_start
		if d.length() >= SWIPE_MIN:
			swiped = true
			if absf(d.x) > absf(d.y):
				change_lane(1 if d.x > 0 else -1)
			elif d.y < 0:
				jump()
	elif event is InputEventKey and event.pressed and not event.echo:
		match event.keycode:
			KEY_LEFT, KEY_A:
				change_lane(-1)
			KEY_RIGHT, KEY_D:
				change_lane(1)
			KEY_UP, KEY_W, KEY_SPACE:
				if state == State.PLAYING:
					jump()
				else:
					_tap()
			KEY_ENTER:
				_tap()


func _tap() -> void:
	match state:
		State.READY:
			_start()
		State.PLAYING:
			jump()
		State.OVER:
			if over_time > 0.8:
				get_tree().reload_current_scene()


# ---------------------------------------------------------------- spawning

func _spawn_row() -> void:
	var lanes := [0, 1, 2]
	lanes.shuffle()
	var two_blocked := randf() < clampf(0.25 + distance / 1500.0, 0.25, 0.7)
	var blocked := 2 if two_blocked else 1
	var solid_used := 0
	for i in blocked:
		var kind := _pick_kind()
		# Never wall off every lane: at most two solid obstacles in a row.
		if solid_used >= 1 and blocked == 2 and kind in ["car", "bus", "buffalo"] and randf() < 0.3:
			kind = "pothole"
		if kind in ["car", "bus", "buffalo"]:
			solid_used += 1
		_spawn_obstacle(kind, lanes[i])
	if randf() < 0.55:
		_spawn_passenger(lanes[blocked])


func _pick_kind() -> String:
	var r := randf() * 10.0
	if r < 3.0:
		return "car"
	if r < 5.0:
		return "buffalo"
	if r < 6.2:
		return "bus"
	if r < 8.6:
		return "pothole"
	return "barrier"


func _spawn_obstacle(kind: String, lane_i: int) -> void:
	var n: Node3D
	var half_len := 1.0
	var jumpable := false
	match kind:
		"car":
			n = _make_car()
			half_len = 1.6
		"bus":
			n = _make_bus()
			half_len = 3.0
		"buffalo":
			n = _make_buffalo()
			half_len = 1.1
		"pothole":
			n = _make_pothole()
			half_len = 0.7
			jumpable = true
		_:
			n = _make_barrier()
			half_len = 0.25
			jumpable = true
	n.position = Vector3(LANE_X[lane_i], 0.0, SPAWN_Z - half_len)
	add_child(n)
	obstacles.append({"node": n, "lane": lane_i, "half_len": half_len, "jumpable": jumpable, "passenger": false})


func _spawn_passenger(lane_i: int) -> void:
	var n := _make_passenger()
	n.position = Vector3(LANE_X[lane_i], 0.0, SPAWN_Z - randf_range(4.0, 8.0))
	add_child(n)
	obstacles.append({"node": n, "lane": lane_i, "half_len": 0.6, "jumpable": false, "passenger": true})


# ---------------------------------------------------------------- road and city

func _dress_tile(tile: Node3D, z: float) -> void:
	for c in tile.get_children():
		c.queue_free()
	tile.position = Vector3(0.0, 0.0, z)

	_box(tile, Vector3(7.0, 0.1, TILE_LEN), Vector3(0, -0.05, 0), ROAD)
	for x: float in [-1.1, 1.1]:
		for k in 4:
			_box(tile, Vector3(0.12, 0.02, 2.2), Vector3(x, 0.01, -TILE_LEN * 0.5 + 2.5 + k * 5.0), Color(0.95, 0.95, 0.9))
	for side: float in [-1.0, 1.0]:
		_box(tile, Vector3(2.4, 0.3, TILE_LEN), Vector3(side * 4.7, 0.15, 0), Color(0.78, 0.74, 0.68))
		_box(tile, Vector3(0.15, 0.32, TILE_LEN), Vector3(side * 3.55, 0.16, 0), Color(0.9, 0.85, 0.2))
		# Electricity pole with a light.
		_box(tile, Vector3(0.18, 5.5, 0.18), Vector3(side * 4.2, 2.75, -2.0), Color(0.4, 0.4, 0.42))
		_box(tile, Vector3(1.1, 0.1, 0.1), Vector3(side * 3.75, 5.3, -2.0), Color(0.4, 0.4, 0.42))
		# Row of buildings and shops.
		var zz := -TILE_LEN * 0.5
		while zz < TILE_LEN * 0.5 - 1.0:
			var w := randf_range(3.0, 6.0)
			w = minf(w, TILE_LEN * 0.5 - zz)
			var h := randf_range(3.5, 11.0)
			var col := Color.from_hsv(randf_range(0.0, 1.0), randf_range(0.45, 0.75), randf_range(0.6, 0.9))
			var bx := side * (6.0 + 1.5)
			_box(tile, Vector3(3.0, h, w - 0.2), Vector3(bx, h * 0.5 + 0.3, zz + w * 0.5), col)
			# Shop sign board facing the road.
			var sign_col := Color.from_hsv(randf(), 0.8, 0.95)
			_box(tile, Vector3(0.12, 0.7, w * 0.8), Vector3(side * 5.95, 2.6, zz + w * 0.5), sign_col)
			# Windows on taller buildings.
			if h > 6.0:
				for fy in range(4, int(h), 2):
					_box(tile, Vector3(0.05, 0.7, w * 0.6), Vector3(side * 5.98, float(fy) + 0.3, zz + w * 0.5), Color(0.25, 0.3, 0.4))
			zz += w


func _box(parent: Node3D, size: Vector3, pos: Vector3, color: Color) -> MeshInstance3D:
	var mesh := BoxMesh.new()
	mesh.size = size
	mesh.material = _mat(color)
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.position = pos
	parent.add_child(mi)
	return mi


func _cyl(parent: Node3D, radius: float, height: float, pos: Vector3, color: Color) -> MeshInstance3D:
	var mesh := CylinderMesh.new()
	mesh.top_radius = radius
	mesh.bottom_radius = radius
	mesh.height = height
	mesh.radial_segments = 16
	mesh.rings = 1
	mesh.material = _mat(color)
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.position = pos
	parent.add_child(mi)
	return mi


func _mat(color: Color, emissive := false) -> StandardMaterial3D:
	var key := color.to_html() + ("e" if emissive else "")
	if mat_cache.has(key):
		return mat_cache[key]
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	m.roughness = 0.75
	if emissive:
		m.emission_enabled = true
		m.emission = color
		m.emission_energy_multiplier = 1.5
	mat_cache[key] = m
	return m


# ---------------------------------------------------------------- models (built from simple shapes)

func _make_rickshaw() -> Node3D:
	var root := Node3D.new()
	body = Node3D.new()
	root.add_child(body)
	var green := Color(0.15, 0.62, 0.36)
	var dark := Color(0.1, 0.1, 0.12)
	_box(body, Vector3(1.3, 0.45, 2.2), Vector3(0, 0.6, 0), green)                    # chassis
	_box(body, Vector3(0.8, 0.75, 0.6), Vector3(0, 0.85, -1.1), Color(1.0, 0.82, 0.2)) # nose
	_box(body, Vector3(1.2, 0.5, 0.6), Vector3(0, 1.05, 0.55), Color(0.85, 0.15, 0.2))  # back seat
	_box(body, Vector3(1.42, 0.08, 1.9), Vector3(0, 1.85, 0.05), dark)                 # canopy roof
	for p: Vector3 in [Vector3(-0.66, 1.35, -0.8), Vector3(0.66, 1.35, -0.8), Vector3(-0.66, 1.35, 0.9), Vector3(0.66, 1.35, 0.9)]:
		_box(body, Vector3(0.07, 0.95, 0.07), p, dark)
	# Truck-art back panel.
	_box(body, Vector3(1.32, 0.3, 0.05), Vector3(0, 0.62, 1.12), Color(0.95, 0.3, 0.55))
	_box(body, Vector3(1.32, 0.08, 0.06), Vector3(0, 0.82, 1.12), Color(1.0, 0.85, 0.2))
	_box(body, Vector3(1.4, 0.1, 0.06), Vector3(0, 1.82, 1.0), Color(0.2, 0.75, 0.95))
	var tail_l := _box(body, Vector3(0.18, 0.12, 0.04), Vector3(-0.5, 0.5, 1.13), Color(1, 0.1, 0.1))
	var tail_r := _box(body, Vector3(0.18, 0.12, 0.04), Vector3(0.5, 0.5, 1.13), Color(1, 0.1, 0.1))
	tail_l.material_override = _mat(Color(1, 0.15, 0.1), true)
	tail_r.material_override = _mat(Color(1, 0.15, 0.1), true)
	var head := _box(body, Vector3(0.22, 0.22, 0.05), Vector3(0, 1.0, -1.42), Color(1, 1, 0.8))
	head.material_override = _mat(Color(1, 0.95, 0.7), true)
	# Wheels: one in front, two at the back.
	for wp: Vector3 in [Vector3(0, 0.3, -1.05), Vector3(-0.68, 0.3, 0.65), Vector3(0.68, 0.3, 0.65)]:
		var pivot := Node3D.new()
		pivot.position = wp
		body.add_child(pivot)
		var w := _cyl(pivot, 0.3, 0.18, Vector3.ZERO, dark)
		w.rotation.z = PI * 0.5
		_box(pivot, Vector3(0.2, 0.08, 0.5), Vector3.ZERO, Color(0.6, 0.6, 0.6))  # spoke, shows spin
		wheels.append(pivot)
	# Driver.
	_box(body, Vector3(0.45, 0.5, 0.35), Vector3(0, 1.15, -0.4), Color(0.9, 0.9, 0.85))
	_cyl(body, 0.15, 0.25, Vector3(0, 1.55, -0.4), Color(0.75, 0.55, 0.4))
	return root


func _make_car() -> Node3D:
	var n := Node3D.new()
	var col := Color.from_hsv(randf(), randf_range(0.4, 0.8), randf_range(0.6, 0.95))
	_box(n, Vector3(1.7, 0.65, 3.2), Vector3(0, 0.6, 0), col)
	_box(n, Vector3(1.45, 0.55, 1.6), Vector3(0, 1.2, 0.2), col.darkened(0.15))
	_box(n, Vector3(1.47, 0.4, 1.4), Vector3(0, 1.22, 0.2), Color(0.2, 0.25, 0.32))
	for x: float in [-0.8, 0.8]:
		for z: float in [-1.05, 1.05]:
			var w := _cyl(n, 0.32, 0.22, Vector3(x, 0.32, z), Color(0.1, 0.1, 0.1))
			w.rotation.z = PI * 0.5
	var l1 := _box(n, Vector3(0.3, 0.12, 0.05), Vector3(-0.55, 0.75, 1.61), Color.RED)
	var l2 := _box(n, Vector3(0.3, 0.12, 0.05), Vector3(0.55, 0.75, 1.61), Color.RED)
	l1.material_override = _mat(Color(1, 0.15, 0.1), true)
	l2.material_override = _mat(Color(1, 0.15, 0.1), true)
	return n


func _make_bus() -> Node3D:
	var n := Node3D.new()
	var base := Color.from_hsv(randf(), 0.7, 0.9)
	_box(n, Vector3(2.0, 2.6, 6.0), Vector3(0, 1.6, 0), base)
	_box(n, Vector3(2.04, 0.7, 5.0), Vector3(0, 2.2, -0.3), Color(0.2, 0.25, 0.32))  # windows
	# Truck-art stripes.
	for k in 3:
		_box(n, Vector3(2.04, 0.12, 6.04), Vector3(0, 0.8 + k * 0.22, 0), Color.from_hsv(randf(), 0.85, 1.0))
	_box(n, Vector3(1.6, 1.0, 0.05), Vector3(0, 1.5, 3.02), Color.from_hsv(randf(), 0.8, 1.0))
	for x: float in [-0.95, 0.95]:
		for z: float in [-2.0, 2.0]:
			var w := _cyl(n, 0.45, 0.3, Vector3(x, 0.45, z), Color(0.1, 0.1, 0.1))
			w.rotation.z = PI * 0.5
	return n


func _make_buffalo() -> Node3D:
	var n := Node3D.new()
	var black := Color(0.12, 0.11, 0.12)
	_box(n, Vector3(1.1, 1.0, 2.0), Vector3(0, 1.25, 0), black)          # body
	_box(n, Vector3(0.6, 0.6, 0.7), Vector3(0, 1.4, 1.3), black)         # head, looking at you
	_box(n, Vector3(0.45, 0.3, 0.2), Vector3(0, 1.2, 1.7), Color(0.3, 0.27, 0.27))  # snout
	var horn_l := _box(n, Vector3(0.5, 0.1, 0.12), Vector3(-0.45, 1.75, 1.25), Color(0.85, 0.8, 0.7))
	var horn_r := _box(n, Vector3(0.5, 0.1, 0.12), Vector3(0.45, 1.75, 1.25), Color(0.85, 0.8, 0.7))
	horn_l.rotation.z = 0.5
	horn_r.rotation.z = -0.5
	for x: float in [-0.38, 0.38]:
		for z: float in [-0.7, 0.7]:
			_box(n, Vector3(0.22, 0.8, 0.22), Vector3(x, 0.4, z), black)
	var e1 := _box(n, Vector3(0.08, 0.08, 0.04), Vector3(-0.18, 1.5, 1.66), Color.WHITE)
	var e2 := _box(n, Vector3(0.08, 0.08, 0.04), Vector3(0.18, 1.5, 1.66), Color.WHITE)
	e1.material_override = _mat(Color(1, 1, 1), true)
	e2.material_override = _mat(Color(1, 1, 1), true)
	return n


func _make_pothole() -> Node3D:
	var n := Node3D.new()
	_cyl(n, 0.95, 0.03, Vector3(0, 0.02, 0), Color(0.45, 0.4, 0.35))
	_cyl(n, 0.8, 0.04, Vector3(0, 0.03, 0), Color(0.08, 0.08, 0.09))
	# Small water puddle shine.
	_cyl(n, 0.35, 0.05, Vector3(0.2, 0.035, 0.15), Color(0.35, 0.45, 0.55))
	return n


func _make_barrier() -> Node3D:
	var n := Node3D.new()
	for k in 5:
		var c := Color(0.9, 0.1, 0.1) if k % 2 == 0 else Color(0.95, 0.95, 0.95)
		_box(n, Vector3(0.36, 0.22, 0.12), Vector3(-0.72 + k * 0.36, 0.55, 0), c)
	for x: float in [-0.8, 0.8]:
		_box(n, Vector3(0.1, 0.65, 0.4), Vector3(x, 0.32, 0), Color(0.25, 0.25, 0.28))
	return n


func _make_passenger() -> Node3D:
	var n := Node3D.new()
	var person := Node3D.new()
	n.add_child(person)
	var kurta := Color.from_hsv(randf(), 0.5, 0.9)
	_box(person, Vector3(0.18, 0.6, 0.18), Vector3(-0.12, 0.3, 0), Color(0.9, 0.9, 0.88))
	_box(person, Vector3(0.18, 0.6, 0.18), Vector3(0.12, 0.3, 0), Color(0.9, 0.9, 0.88))
	_box(person, Vector3(0.55, 0.75, 0.32), Vector3(0, 0.95, 0), kurta)
	_cyl(person, 0.17, 0.32, Vector3(0, 1.5, 0), Color(0.78, 0.58, 0.42))
	# Waving arm.
	var arm := _box(person, Vector3(0.14, 0.55, 0.14), Vector3(0.38, 1.45, 0), kurta)
	arm.rotation.z = -0.4
	# Floating marker so passengers are easy to spot.
	var marker := Node3D.new()
	marker.position = Vector3(0, 2.3, 0)
	n.add_child(marker)
	var gem := _box(marker, Vector3(0.38, 0.38, 0.38), Vector3.ZERO, Color(0.2, 1.0, 0.4))
	gem.rotation = Vector3(PI * 0.25, 0, PI * 0.25)
	gem.material_override = _mat(Color(0.2, 1.0, 0.45), true)
	return n


# ---------------------------------------------------------------- world / ui / audio

func _build_world() -> void:
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = SKY
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(1.0, 0.92, 0.85)
	env.ambient_light_energy = 0.35
	env.fog_enabled = true
	env.fog_light_color = SKY
	env.fog_density = 0.009
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.tonemap_exposure = 1.1
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)

	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50.0, -35.0, 0.0)
	sun.light_color = Color(1.0, 0.93, 0.82)
	sun.light_energy = 1.1
	sun.shadow_enabled = true
	sun.directional_shadow_max_distance = 45.0
	add_child(sun)

	cam = Camera3D.new()
	cam.fov = 62.0
	cam.near = 0.1
	cam.far = 160.0
	cam.position = Vector3(0, 4.6, 7.8)
	add_child(cam)
	cam.current = true


func _build_ui() -> void:
	var ui := CanvasLayer.new()
	add_child(ui)

	score_label = _make_label(ui, 84)
	score_label.vertical_alignment = VERTICAL_ALIGNMENT_TOP
	score_label.offset_top = 70.0
	score_label.text = "0"
	score_label.visible = false

	sawari_label = _make_label(ui, 34)
	sawari_label.vertical_alignment = VERTICAL_ALIGNMENT_TOP
	sawari_label.offset_top = 180.0
	sawari_label.add_theme_color_override("font_color", Color(0.75, 1.0, 0.8))
	sawari_label.visible = false

	dim_rect = ColorRect.new()
	dim_rect.color = Color(0, 0, 0, 0.5)
	dim_rect.set_anchors_preset(Control.PRESET_FULL_RECT)
	dim_rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	dim_rect.visible = false
	ui.add_child(dim_rect)

	hint_label = _make_label(ui, 40)
	hint_label.vertical_alignment = VERTICAL_ALIGNMENT_TOP
	hint_label.offset_top = 150.0

	over_label = _make_label(ui, 46)
	over_label.visible = false


func _make_label(parent: Node, font_size: int) -> Label:
	var label := Label.new()
	label.set_anchors_preset(Control.PRESET_FULL_RECT)
	label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	label.mouse_filter = Control.MOUSE_FILTER_IGNORE
	label.add_theme_font_size_override("font_size", font_size)
	label.add_theme_color_override("font_color", Color.WHITE)
	label.add_theme_color_override("font_outline_color", Color(0.15, 0.05, 0.0, 0.6))
	label.add_theme_constant_override("outline_size", maxi(6, font_size / 6))
	parent.add_child(label)
	return label


func _build_audio() -> void:
	sfx = AudioStreamPlayer.new()
	sfx2 = AudioStreamPlayer.new()
	add_child(sfx)
	add_child(sfx2)
	tone_coin = _make_tone([1320.0, 1760.0], 0.16, 14.0)
	tone_jump = _make_tone([420.0], 0.14, 18.0)
	tone_crash = _make_tone([90.0, 130.0], 0.5, 5.0, false, true)
	tone_horn = _make_tone([440.0, 554.0], 0.45, 1.5, true)


## Short synthesized sounds so the game needs no audio files.
func _make_tone(freqs: Array, duration: float, decay: float, square := false, noisy := false) -> AudioStreamWAV:
	var rate := 22050
	var count := int(rate * duration)
	var data := PackedByteArray()
	data.resize(count * 2)
	for i in count:
		var t := float(i) / rate
		var v := 0.0
		for f in freqs:
			var w := sin(TAU * f * t)
			v += signf(w) * 0.5 if square else w
		v /= freqs.size()
		if noisy:
			v = v * 0.6 + randf_range(-0.4, 0.4)
		v *= exp(-t * decay) * 0.45
		data.encode_s16(i * 2, int(clampf(v, -1.0, 1.0) * 32767.0))
	var wav := AudioStreamWAV.new()
	wav.format = AudioStreamWAV.FORMAT_16_BITS
	wav.mix_rate = rate
	wav.stereo = false
	wav.data = data
	return wav


func _play(player_node: AudioStreamPlayer, stream: AudioStreamWAV, pitch: float) -> void:
	player_node.stream = stream
	player_node.pitch_scale = pitch
	player_node.play()


# ---------------------------------------------------------------- save

func _load_best() -> int:
	if not FileAccess.file_exists(SAVE_PATH):
		return 0
	var f := FileAccess.open(SAVE_PATH, FileAccess.READ)
	return f.get_32() if f else 0


func _save_best() -> void:
	var f := FileAccess.open(SAVE_PATH, FileAccess.WRITE)
	if f:
		f.store_32(best)
