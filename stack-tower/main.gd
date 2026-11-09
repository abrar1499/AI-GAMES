extends Node3D
## Stack Tower 3D
## A block slides back and forth above the tower. Tap to drop it.
## Whatever hangs over the edge is cut off and falls. Miss completely and it's game over.
## Land within PERFECT_TOL of the block below for a "perfect"; a streak of perfects grows the block back.

enum State { READY, PLAYING, OVER }

const BLOCK_H := 0.6
const START_SIZE := 3.0
const MOVE_RANGE := 5.0
const PERFECT_TOL := 0.15
const GROW_AFTER := 3          # perfect streak needed before blocks start growing back
const GROW_STEP := 0.25
const BASE_SPEED := 3.2
const SPEED_PER_LEVEL := 0.08
const MAX_SPEED := 8.0
const HUE_STEP := 0.04
const CAM_OFFSET := Vector3(9.0, 10.0, 9.0)
const CAM_SIZE := 12.0
const SAVE_PATH := "user://best_score.save"

var state: State = State.READY
var score := 0
var best := 0
var combo := 0
var hue_start := 0.0

# Top of the tower: centre (x, z) and size of the last placed block, plus the height of its top face.
var top_center := Vector3.ZERO
var top_size := Vector3(START_SIZE, BLOCK_H, START_SIZE)
var top_y := 0.0

# The sliding block.
var mover: MeshInstance3D
var mover_size := Vector3.ZERO
var mover_color := Color.WHITE
var mover_axis := 0            # 0 = slides along x, 2 = slides along z
var mover_t := 0.0

var cam: Camera3D
var cam_y := 0.0
var over_time := 0.0

var bg_gradient: Gradient
var bg_top_target := Color.BLACK
var bg_bottom_target := Color.BLACK

var ui: CanvasLayer
var score_label: Label
var hint_label: Label
var perfect_label: Label
var over_label: Label
var dim_rect: ColorRect

var sfx: AudioStreamPlayer
var place_tone: AudioStreamWAV
var fail_tone: AudioStreamWAV


func _ready() -> void:
	randomize()
	hue_start = randf()
	best = _load_best()
	_build_world()
	_build_ui()
	_build_audio()

	# Pedestal the tower stands on; its top face is at y = 0.
	var base_size := Vector3(START_SIZE, 10.0, START_SIZE)
	_make_static(Vector3(0.0, -base_size.y * 0.5, 0.0), base_size, _block_color(0))

	_update_background(true)
	hint_label.text = "TAP TO START" + ("\n\nBEST  %d" % best if best > 0 else "")


# ---------------------------------------------------------------- game flow

func _start() -> void:
	state = State.PLAYING
	hint_label.visible = false
	score_label.visible = true
	_spawn_mover()


func _spawn_mover() -> void:
	mover_axis = 0 if score % 2 == 0 else 2
	mover_size = top_size
	mover_color = _block_color(score + 1)
	mover_t = 0.0
	mover = _mesh_instance(mover_size, mover_color)
	add_child(mover)
	_move_mover(0.0)


func _move_mover(delta: float) -> void:
	var speed := minf(BASE_SPEED + score * SPEED_PER_LEVEL, MAX_SPEED)
	mover_t += delta * speed
	var offset := pingpong(mover_t, MOVE_RANGE * 2.0) - MOVE_RANGE
	var p := Vector3(top_center.x, top_y + BLOCK_H * 0.5, top_center.z)
	p[mover_axis] += offset
	mover.position = p


## Drops the sliding block onto the tower.
func place_block() -> void:
	if state != State.PLAYING or mover == null:
		return

	var axis := mover_axis
	var pos: Vector3 = mover.position
	var shift: float = pos[axis] - top_center[axis]
	var length: float = mover_size[axis]
	var perfect := absf(shift) <= PERFECT_TOL
	if perfect:
		shift = 0.0

	var overlap := length - absf(shift)
	if overlap <= 0.0:
		_game_over()
		return

	var new_size := mover_size
	var new_pos := pos
	new_size[axis] = overlap
	new_pos[axis] = top_center[axis] + shift * 0.5

	if perfect:
		combo += 1
		if combo >= GROW_AFTER:
			new_size[axis] = minf(overlap + GROW_STEP, START_SIZE)
	else:
		combo = 0
		var cut_size := mover_size
		var cut_pos := new_pos
		cut_size[axis] = absf(shift)
		cut_pos[axis] = new_pos[axis] + signf(shift) * (overlap + absf(shift)) * 0.5
		_spawn_debris(cut_pos, cut_size, mover_color)

	mover.queue_free()
	mover = null
	_make_static(new_pos, new_size, mover_color)

	top_center = new_pos
	top_size = new_size
	top_y += BLOCK_H
	score += 1
	score_label.text = str(score)
	_update_background(false)

	if perfect:
		_perfect_effect(new_pos, new_size)
		_play(place_tone, 1.0 + minf(combo, 8) * 0.12)
		Input.vibrate_handheld(25)
	else:
		_play(place_tone, 0.85)

	_spawn_mover()


func _game_over() -> void:
	state = State.OVER
	over_time = 0.0
	_spawn_debris(mover.position, mover_size, mover_color)
	mover.queue_free()
	mover = null

	var new_best := score > best
	if new_best:
		best = score
		_save_best()

	score_label.visible = false
	perfect_label.modulate.a = 0.0
	over_label.text = "GAME OVER\n\n%d\n\n%s\n\n\nTap to play again" % [
		score, "NEW BEST!" if new_best else "BEST  %d" % best]
	over_label.visible = true
	dim_rect.visible = true
	_play(fail_tone, 1.0)
	Input.vibrate_handheld(120)


func _restart() -> void:
	get_tree().reload_current_scene()


# ---------------------------------------------------------------- per frame

func _process(delta: float) -> void:
	if state == State.PLAYING and mover:
		_move_mover(delta)
	over_time += delta

	# Follow the top of the tower; on game over pull back to show the whole thing.
	var target_y := top_y
	var target_size := CAM_SIZE
	if state == State.OVER:
		target_y = top_y * 0.5 - 1.0
		target_size = maxf(CAM_SIZE, top_y * 1.3 + 7.0)
	var k := 1.0 - exp(-delta * 4.0)
	cam_y = lerpf(cam_y, target_y, k)
	cam.size = lerpf(cam.size, target_size, k * 0.8)
	var focus := Vector3(0.0, cam_y, 0.0)
	cam.position = focus + CAM_OFFSET
	cam.look_at(focus)

	# Ease the background toward the current level's colours.
	var kb := 1.0 - exp(-delta * 2.0)
	bg_gradient.set_color(0, bg_gradient.get_color(0).lerp(bg_top_target, kb))
	bg_gradient.set_color(1, bg_gradient.get_color(1).lerp(bg_bottom_target, kb))

	if perfect_label.modulate.a > 0.0:
		perfect_label.modulate.a = maxf(0.0, perfect_label.modulate.a - delta * 1.5)


func _unhandled_input(event: InputEvent) -> void:
	# Touch is delivered as a left click (emulate_mouse_from_touch is on by default).
	var tap := false
	if event is InputEventMouseButton:
		tap = event.pressed and event.button_index == MOUSE_BUTTON_LEFT
	elif event is InputEventKey:
		tap = event.pressed and not event.echo and event.keycode in [KEY_SPACE, KEY_ENTER]
	if not tap:
		return

	match state:
		State.READY:
			_start()
		State.PLAYING:
			place_block()
		State.OVER:
			if over_time > 0.8:
				_restart()


# ---------------------------------------------------------------- building blocks

func _block_color(level: int) -> Color:
	return Color.from_hsv(fposmod(hue_start + level * HUE_STEP, 1.0), 0.62, 0.92)


func _mesh_instance(size: Vector3, color: Color) -> MeshInstance3D:
	var box := BoxMesh.new()
	box.size = size
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color
	mat.roughness = 0.6
	box.material = mat
	var mi := MeshInstance3D.new()
	mi.mesh = box
	return mi


func _box_collider(size: Vector3) -> CollisionShape3D:
	var shape := BoxShape3D.new()
	shape.size = size
	var col := CollisionShape3D.new()
	col.shape = shape
	return col


func _make_static(pos: Vector3, size: Vector3, color: Color) -> void:
	var body := StaticBody3D.new()
	body.position = pos
	body.add_child(_mesh_instance(size, color))
	body.add_child(_box_collider(size))
	add_child(body)


func _spawn_debris(pos: Vector3, size: Vector3, color: Color) -> void:
	var body := RigidBody3D.new()
	body.position = pos
	body.add_child(_mesh_instance(size, color))
	body.add_child(_box_collider(size))
	add_child(body)
	body.angular_velocity = Vector3(randf_range(-1.5, 1.5), 0.0, randf_range(-1.5, 1.5))
	get_tree().create_timer(6.0).timeout.connect(body.queue_free)


func _perfect_effect(pos: Vector3, size: Vector3) -> void:
	# A white flash plate that grows out from under the block and fades.
	var plate := MeshInstance3D.new()
	var box := BoxMesh.new()
	box.size = Vector3(size.x + 0.3, 0.04, size.z + 0.3)
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	mat.albedo_color = Color(1, 1, 1, 0.9)
	box.material = mat
	plate.mesh = box
	plate.position = Vector3(pos.x, pos.y - BLOCK_H * 0.5, pos.z)
	add_child(plate)
	var tw := create_tween().set_parallel(true)
	tw.tween_property(plate, "scale", Vector3(1.35, 1.0, 1.35), 0.45).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_OUT)
	tw.tween_property(mat, "albedo_color:a", 0.0, 0.45)
	tw.chain().tween_callback(plate.queue_free)

	perfect_label.text = "PERFECT!" if combo < 2 else "PERFECT  x%d" % combo
	perfect_label.modulate.a = 1.0


# ---------------------------------------------------------------- world / ui / audio

func _build_world() -> void:
	var env := Environment.new()
	env.background_mode = Environment.BG_CANVAS
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color.WHITE
	env.ambient_light_energy = 0.32
	var world_env := WorldEnvironment.new()
	world_env.environment = env
	add_child(world_env)

	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-60.0, 25.0, 0.0)
	sun.light_energy = 1.25
	sun.shadow_enabled = true
	sun.directional_shadow_max_distance = 60.0
	add_child(sun)

	var fill := DirectionalLight3D.new()
	fill.rotation_degrees = Vector3(-20.0, 135.0, 0.0)
	fill.light_energy = 0.35
	add_child(fill)

	cam = Camera3D.new()
	cam.projection = Camera3D.PROJECTION_ORTHOGONAL
	cam.size = CAM_SIZE
	cam.near = 0.1
	cam.far = 200.0
	add_child(cam)
	cam.current = true

	# Gradient sky drawn on a canvas layer behind the 3D scene.
	var bg_layer := CanvasLayer.new()
	bg_layer.layer = -1
	add_child(bg_layer)
	bg_gradient = Gradient.new()
	var tex := GradientTexture2D.new()
	tex.gradient = bg_gradient
	tex.fill_from = Vector2(0.5, 0.0)
	tex.fill_to = Vector2(0.5, 1.0)
	tex.width = 4
	tex.height = 256
	var rect := TextureRect.new()
	rect.texture = tex
	rect.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	rect.stretch_mode = TextureRect.STRETCH_SCALE
	rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	rect.set_anchors_preset(Control.PRESET_FULL_RECT)
	bg_layer.add_child(rect)


func _update_background(instant: bool) -> void:
	var h := fposmod(hue_start + score * HUE_STEP + 0.08, 1.0)
	bg_top_target = Color.from_hsv(h, 0.35, 0.95)
	bg_bottom_target = Color.from_hsv(fposmod(h + 0.12, 1.0), 0.55, 0.55)
	if instant:
		bg_gradient.set_color(0, bg_top_target)
		bg_gradient.set_color(1, bg_bottom_target)


func _build_ui() -> void:
	ui = CanvasLayer.new()
	add_child(ui)

	score_label = _make_label(110)
	score_label.vertical_alignment = VERTICAL_ALIGNMENT_TOP
	score_label.offset_top = 110.0
	score_label.text = "0"
	score_label.visible = false

	perfect_label = _make_label(44)
	perfect_label.vertical_alignment = VERTICAL_ALIGNMENT_TOP
	perfect_label.offset_top = 260.0
	perfect_label.modulate.a = 0.0

	hint_label = _make_label(52)
	hint_label.vertical_alignment = VERTICAL_ALIGNMENT_BOTTOM
	hint_label.offset_bottom = -170.0

	dim_rect = ColorRect.new()
	dim_rect.color = Color(0.0, 0.0, 0.0, 0.45)
	dim_rect.set_anchors_preset(Control.PRESET_FULL_RECT)
	dim_rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	dim_rect.visible = false
	ui.add_child(dim_rect)

	over_label = _make_label(56)
	over_label.visible = false


func _make_label(font_size: int) -> Label:
	var label := Label.new()
	label.set_anchors_preset(Control.PRESET_FULL_RECT)
	label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	label.mouse_filter = Control.MOUSE_FILTER_IGNORE
	label.add_theme_font_size_override("font_size", font_size)
	label.add_theme_color_override("font_color", Color.WHITE)
	label.add_theme_color_override("font_outline_color", Color(0.0, 0.0, 0.0, 0.3))
	label.add_theme_constant_override("outline_size", maxi(4, font_size / 8))
	ui.add_child(label)
	return label


func _build_audio() -> void:
	sfx = AudioStreamPlayer.new()
	add_child(sfx)
	place_tone = _make_tone(660.0, 0.18, 22.0)
	fail_tone = _make_tone(140.0, 0.45, 6.0)


## Builds a short decaying sine "pluck" so the game needs no sound files.
func _make_tone(freq: float, duration: float, decay: float) -> AudioStreamWAV:
	var rate := 22050
	var count := int(rate * duration)
	var data := PackedByteArray()
	data.resize(count * 2)
	for i in count:
		var t := float(i) / rate
		var v := sin(TAU * freq * t) * exp(-t * decay) * 0.45
		data.encode_s16(i * 2, int(v * 32767.0))
	var wav := AudioStreamWAV.new()
	wav.format = AudioStreamWAV.FORMAT_16_BITS
	wav.mix_rate = rate
	wav.stereo = false
	wav.data = data
	return wav


func _play(stream: AudioStreamWAV, pitch: float) -> void:
	sfx.stream = stream
	sfx.pitch_scale = pitch
	sfx.play()


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
