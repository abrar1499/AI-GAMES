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

const SKY := Color(0.86, 0.66, 0.5)    # fog / haze colour, matched to the sky image
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

# Photo-scanned CC0 assets from Poly Haven (see assets/CREDITS.md).
const SHUTTER_SCENE := preload("res://assets/rollershutter_door/rollershutter_door.gltf")
const BARRIER_SCENE := preload("res://assets/concrete_road_barrier/concrete_road_barrier.gltf")
const MANHOLE_SCENE := preload("res://assets/water_manhole_cover/water_manhole_cover.gltf")
const LAMP_SCENE := preload("res://assets/street_lamp_01/street_lamp_01.gltf")
const SKY_HDR := preload("res://assets/sky/highway_bridge_sunset_1k.hdr")
const RICKSHAW_SCENE := preload("res://assets/models/rickshaw.glb")
const CAR_SCENE := preload("res://assets/models/car.glb")
const BUS_SCENE := preload("res://assets/models/bus.glb")
const BUFFALO_SCENE := preload("res://assets/models/buffalo.glb")
const PERSON_SCENE := preload("res://assets/models/person.glb")

# Shalwar kameez colours: white, sky blue, beige, mint, slate grey, brown, cream.
const SUIT_COLORS := [
	Color(0.9, 0.9, 0.87), Color(0.55, 0.7, 0.85), Color(0.78, 0.7, 0.55), Color(0.6, 0.78, 0.65),
	Color(0.45, 0.48, 0.52), Color(0.42, 0.3, 0.2), Color(0.92, 0.86, 0.72),
]

# Wall colours seen on Pakistani city streets: whitewash, cream, ochre, pale pink, mint, sky blue.
const WALL_TINTS := [
	Color(0.95, 0.93, 0.88), Color(0.93, 0.84, 0.66), Color(0.86, 0.68, 0.45), Color(0.92, 0.75, 0.72),
	Color(0.72, 0.86, 0.78), Color(0.70, 0.80, 0.90), Color(0.80, 0.78, 0.74), Color(0.90, 0.60, 0.50),
]

var road_mat: StandardMaterial3D
var walk_mat: StandardMaterial3D
var curb_mat: StandardMaterial3D
var wall_mats: Array[StandardMaterial3D] = []
var glass_mat: StandardMaterial3D
var frame_mat: StandardMaterial3D
var paint_white: StandardMaterial3D
var wire_mat: StandardMaterial3D


func _ready() -> void:
	randomize()
	best = _load_best()
	_build_materials()
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
		w.rotation.x -= dz / 0.25


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
			half_len = 1.95
		"bus":
			n = _make_bus()
			half_len = 3.0
		"buffalo":
			n = _make_buffalo()
			half_len = 1.3
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
	# All static boxes of a tile are merged into one mesh (one surface per material):
	# a few draw calls per tile instead of ~140, which phones and WebGL need.
	var batch := {}

	_add_box(batch, Vector3(7.0, 0.1, TILE_LEN), Vector3(0, -0.05, 0), road_mat)
	for x: float in [-1.1, 1.1]:
		for k in 4:
			_add_box(batch, Vector3(0.12, 0.012, 2.4), Vector3(x, 0.006, -TILE_LEN * 0.5 + 2.5 + k * 5.0), paint_white)
	for side: float in [-1.0, 1.0]:
		_add_box(batch, Vector3(2.4, 0.22, TILE_LEN), Vector3(side * 4.7, 0.11, 0), walk_mat)
		_add_box(batch, Vector3(0.22, 0.26, TILE_LEN), Vector3(side * 3.6, 0.13, 0), curb_mat)

		# Street lamp, plus sagging electricity wires running along the street.
		var lamp := LAMP_SCENE.instantiate() as Node3D
		lamp.scale = Vector3.ONE * 1.35
		lamp.rotation.y = -side * PI * 0.5
		lamp.position = Vector3(side * 4.1, 0.22, -3.0)
		tile.add_child(lamp)
		for k in 3:
			_add_box(batch, Vector3(0.025, 0.025, TILE_LEN), Vector3(side * (4.6 + k * 0.25), 6.2 - k * 0.15, 0), wire_mat)

		# Row of buildings with shops on the ground floor.
		var zz := -TILE_LEN * 0.5
		while zz < TILE_LEN * 0.5 - 1.0:
			var w := minf(randf_range(3.4, 7.0), TILE_LEN * 0.5 - zz)
			_building(tile, batch, side, zz, w)
			zz += w
	_flush_batch(tile, batch)


func _building(tile: Node3D, batch: Dictionary, side: float, z0: float, w: float) -> void:
	var floors := randi_range(1, 4)
	var h := 3.4 + floors * 3.0
	var depth := 6.0
	var front := side * 5.9                       # x of the facade facing the road
	var cz := z0 + w * 0.5
	_add_box(batch, Vector3(depth, h, w - 0.08), Vector3(front + side * depth * 0.5, h * 0.5 + 0.22, cz), wall_mats.pick_random())

	# Ground floor: rolling shutters (some half open), sign board above.
	var shops := maxi(1, int(w / 3.2))
	var shop_w := w / shops
	for i in shops:
		var sz := z0 + shop_w * (i + 0.5)
		var shutter := SHUTTER_SCENE.instantiate() as Node3D
		shutter.rotation.y = side * -PI * 0.5 if side > 0 else PI * 0.5
		var s_scale := minf(1.0, (shop_w - 0.3) / 3.08)
		shutter.scale = Vector3(s_scale, 1.0, 1.0)
		# The model's origin is at its left edge; its centre is 1.0 m along local x.
		var centre_offset := shutter.transform.basis * Vector3(1.0, 0.0, 0.0)
		shutter.position = Vector3(front - side * 0.02, 0.22, sz) - Vector3(0.0, 0.0, centre_offset.z)
		tile.add_child(shutter)
	var sign_col := Color.from_hsv(randf(), randf_range(0.6, 0.9), randf_range(0.75, 0.95))
	_add_box(batch, Vector3(0.12, 0.75, w * 0.9), Vector3(front - side * 0.08, 3.0, cz), _pbr(sign_col, 0.0, 0.45))
	_add_box(batch, Vector3(0.6, 0.06, w * 0.95), Vector3(front - side * 0.3, 2.62, cz), frame_mat)  # awning ledge

	# Upper floors: windows with frames, some AC units and balconies.
	var cols := maxi(1, int(w / 2.6))
	for f in floors:
		var y := 3.9 + f * 3.0 + 1.1
		if randf() < 0.35:
			_add_box(batch, Vector3(1.0, 0.12, w * 0.8), Vector3(front - side * 0.5, y - 0.95, cz), frame_mat)
			_add_box(batch, Vector3(0.05, 0.8, w * 0.8), Vector3(front - side * 0.98, y - 0.5, cz), frame_mat)
		for c in cols:
			var wz := z0 + w / cols * (c + 0.5)
			_add_box(batch, Vector3(0.1, 1.5, 1.2), Vector3(front - side * 0.02, y, wz), frame_mat)
			_add_box(batch, Vector3(0.12, 1.3, 1.0), Vector3(front - side * 0.03, y, wz), glass_mat)
			if randf() < 0.3:
				_add_box(batch, Vector3(0.5, 0.45, 0.8), Vector3(front - side * 0.3, y - 0.95, wz + 0.2), paint_white)
	# Black water tank on the roof, like most houses here.
	if randf() < 0.6:
		var tank := CylinderMesh.new()
		tank.top_radius = 0.55
		tank.bottom_radius = 0.55
		tank.height = 1.1
		tank.radial_segments = 12
		tank.rings = 1
		_add_mesh(batch, tank, Vector3(front + side * randf_range(1.5, 4.0), h + 0.22 + 0.55, cz + randf_range(-0.6, 0.6)), _pbr(Color(0.07, 0.07, 0.08), 0.0, 0.5))


## Queues a box for the tile's merged mesh.
func _add_box(batch: Dictionary, size: Vector3, pos: Vector3, mat: Material) -> void:
	var box := BoxMesh.new()
	box.size = size
	_add_mesh(batch, box, pos, mat)


func _add_mesh(batch: Dictionary, mesh: PrimitiveMesh, pos: Vector3, mat: Material) -> void:
	if not batch.has(mat):
		var st := SurfaceTool.new()
		st.begin(Mesh.PRIMITIVE_TRIANGLES)
		batch[mat] = st
	(batch[mat] as SurfaceTool).append_from(mesh, 0, Transform3D(Basis.IDENTITY, pos))


## Builds one ArrayMesh (a surface per material) from the queued boxes and adds it to the tile.
func _flush_batch(tile: Node3D, batch: Dictionary) -> void:
	var mesh := ArrayMesh.new()
	for mat: Material in batch:
		var st: SurfaceTool = batch[mat]
		st.commit(mesh)
		mesh.surface_set_material(mesh.get_surface_count() - 1, mat)
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	tile.add_child(mi)


func _boxm(parent: Node3D, size: Vector3, pos: Vector3, mat: Material) -> MeshInstance3D:
	var mesh := BoxMesh.new()
	mesh.size = size
	mesh.material = mat
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.position = pos
	parent.add_child(mi)
	return mi


## Physically based material for painted metal, plastic, glass and so on.
func _pbr(color: Color, metallic: float, roughness: float) -> StandardMaterial3D:
	var key := "%s_%.2f_%.2f" % [color.to_html(), metallic, roughness]
	if mat_cache.has(key):
		return mat_cache[key]
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	m.metallic = metallic
	m.metallic_specular = 0.5
	m.roughness = roughness
	mat_cache[key] = m
	return m


## Material from a scanned texture set (diffuse, normal, roughness), mapped by position
## so the texture keeps its real-world size on any box.
func _scan_mat(tex_name: String, metres_per_tile: float, tint := Color.WHITE) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_texture = load("res://assets/textures/%s_diffuse.jpg" % tex_name)
	m.albedo_color = tint
	m.normal_enabled = true
	m.normal_texture = load("res://assets/textures/%s_nor_gl.jpg" % tex_name)
	m.roughness_texture = load("res://assets/textures/%s_rough.jpg" % tex_name)
	m.roughness = 1.0
	m.uv1_triplanar = true
	m.uv1_scale = Vector3.ONE / metres_per_tile
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS_ANISOTROPIC
	return m


func _build_materials() -> void:
	road_mat = _scan_mat("asphalt_02", 3.0, Color(0.85, 0.85, 0.85))
	walk_mat = _scan_mat("brick_pavement_02", 2.0, Color(0.9, 0.85, 0.8))
	curb_mat = _scan_mat("painted_plaster_wall", 1.5, Color(0.75, 0.75, 0.72))
	for tint: Color in WALL_TINTS:
		wall_mats.append(_scan_mat("painted_plaster_wall", 2.5, tint))
	glass_mat = _pbr(Color(0.12, 0.15, 0.18), 0.8, 0.06)
	frame_mat = _pbr(Color(0.55, 0.53, 0.5), 0.3, 0.6)
	paint_white = _pbr(Color(0.92, 0.92, 0.9), 0.0, 0.7)
	wire_mat = _pbr(Color(0.05, 0.05, 0.05), 0.0, 0.8)


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

## The rickshaw model is built in Blender by tools/make_rickshaw.py.
func _make_rickshaw() -> Node3D:
	var root := Node3D.new()
	body = Node3D.new()
	root.add_child(body)
	var model := RICKSHAW_SCENE.instantiate() as Node3D
	body.add_child(model)
	for wheel_name in ["Wheel_F", "Wheel_RL", "Wheel_RR"]:
		var w := model.find_child(wheel_name, true, false) as Node3D
		if w:
			wheels.append(w)
	return root


## Car and bus models are built in Blender by tools/make_vehicles.py.
func _make_car() -> Node3D:
	var n := Node3D.new()
	var model := CAR_SCENE.instantiate() as Node3D
	n.add_child(model)
	# Common car colours here: white, silver, black, grey, maroon, dark blue.
	var col: Color = [Color(0.9, 0.9, 0.88), Color(0.62, 0.63, 0.65), Color(0.03, 0.03, 0.04),
		Color(0.3, 0.31, 0.33), Color(0.38, 0.04, 0.06), Color(0.06, 0.12, 0.3)].pick_random()
	_recolor(model, "CarPaint", col)
	return n


func _make_bus() -> Node3D:
	var n := Node3D.new()
	var model := BUS_SCENE.instantiate() as Node3D
	n.add_child(model)
	_recolor(model, "BusPaint", Color.from_hsv(randf(), randf_range(0.0, 0.5), 0.9))
	_recolor(model, "BusStripe", Color.from_hsv(randf(), 0.85, 0.8))
	return n


## Gives one instance its own copy of the named material with a new albedo colour.
func _recolor(root: Node, material_name: String, color: Color) -> void:
	for mi: MeshInstance3D in root.find_children("*", "MeshInstance3D", true, false):
		for i in mi.mesh.get_surface_count():
			var m := mi.mesh.surface_get_material(i)
			if m and m.resource_name == material_name:
				var copy := m.duplicate() as StandardMaterial3D
				copy.albedo_color = color
				mi.set_surface_override_material(i, copy)


## Buffalo and passenger models are built in Blender by tools/make_creatures.py.
func _make_buffalo() -> Node3D:
	var n := Node3D.new()
	var model := BUFFALO_SCENE.instantiate() as Node3D
	model.rotation.y = PI + randf_range(-0.35, 0.35)   # facing the oncoming rickshaw
	n.add_child(model)
	return n


## An open sewer manhole: dark hole with a broken rim, the lifted cover lying beside it.
func _make_pothole() -> Node3D:
	var n := Node3D.new()
	var rim := _cyl(n, 0.85, 0.06, Vector3(0, 0.02, 0), Color(0.3, 0.29, 0.28))
	rim.material_override = curb_mat
	var hole := _cyl(n, 0.7, 0.07, Vector3(0, 0.03, 0), Color(0.01, 0.01, 0.01))
	hole.material_override = _pbr(Color(0.01, 0.01, 0.012), 0.0, 1.0)
	var cover := MANHOLE_SCENE.instantiate() as Node3D
	cover.scale = Vector3.ONE * 1.6
	cover.position = Vector3(0.75, 0.02, 0.6)
	cover.rotation = Vector3(0.0, randf() * TAU, 0.12)
	n.add_child(cover)
	return n


## Concrete road barrier (police naka).
func _make_barrier() -> Node3D:
	var n := Node3D.new()
	var b := BARRIER_SCENE.instantiate() as Node3D
	b.scale = Vector3(1.15, 0.85, 1.0)
	n.add_child(b)
	return n


func _make_passenger() -> Node3D:
	var n := Node3D.new()
	var person := PERSON_SCENE.instantiate() as Node3D
	person.rotation.y = PI + randf_range(-0.4, 0.4)    # facing the road, waving
	n.add_child(person)
	var suit: Color = SUIT_COLORS.pick_random()
	_recolor(person, "Kameez", suit)
	_recolor(person, "Shalwar", suit.darkened(0.05))
	# Floating marker so passengers are easy to spot.
	var marker := Node3D.new()
	marker.position = Vector3(0, 2.45, 0)
	n.add_child(marker)
	var gem := _box(marker, Vector3(0.38, 0.38, 0.38), Vector3.ZERO, Color(0.2, 1.0, 0.4))
	gem.rotation = Vector3(PI * 0.25, 0, PI * 0.25)
	gem.material_override = _mat(Color(0.2, 1.0, 0.45), true)
	return n


# ---------------------------------------------------------------- world / ui / audio

func _build_world() -> void:
	var sky_mat := PanoramaSkyMaterial.new()
	sky_mat.panorama = SKY_HDR
	sky_mat.energy_multiplier = 1.0
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.8
	env.reflected_light_source = Environment.REFLECTION_SOURCE_SKY
	env.tonemap_mode = Environment.TONE_MAPPER_ACES
	env.tonemap_exposure = 1.0
	env.tonemap_white = 6.0
	env.fog_enabled = true
	env.fog_light_color = SKY
	env.fog_density = 0.013
	env.fog_sky_affect = 0.85
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)

	# Haze wall at the far end of the street, so the road fades into dust instead of
	# showing the dark lower half of the sky image.
	var haze := MeshInstance3D.new()
	var quad := QuadMesh.new()
	quad.size = Vector2(260.0, 40.0)
	var haze_mat := StandardMaterial3D.new()
	haze_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	haze_mat.disable_fog = true
	haze_mat.albedo_color = SKY
	quad.material = haze_mat
	haze.mesh = quad
	haze.position = Vector3(0.0, -1.0, -TILE_LEN * (TILE_COUNT - 0.5) + 2.0)
	add_child(haze)

	# Low evening sun from behind-left, warm.
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-28.0, -40.0, 0.0)
	sun.light_color = Color(1.0, 0.8, 0.6)
	sun.light_energy = 1.6
	sun.shadow_enabled = true
	sun.shadow_blur = 1.5
	sun.directional_shadow_max_distance = 50.0
	add_child(sun)

	cam = Camera3D.new()
	cam.fov = 62.0
	cam.near = 0.1
	cam.far = 220.0
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
