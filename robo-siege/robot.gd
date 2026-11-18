extends Node3D
## A robot built from a rigged model, with its own walk, attack and death
## animations. It offers the same handful of calls the old box-built rig did,
## so enemy.gd only has to say how fast the robot is moving and when it fires.

const Rig := preload("res://rig.gd")

## Which model each robot is, how tall it stands in the game, the colour of
## its plastic panels, and the bones its guns sit on.
const KINDS := {
	Rig.Kind.GRUNT: {
		"model": "blaster", "height": 2.0, "tint": Color(1, 1, 1), "plain": false,
		"guns": ["Arm_R.002", "Arm_L.002"], "gun_reach": 0.5,
	},
	Rig.Kind.RUSHER: {
		"model": "crawler", "height": 1.25, "tint": Color(2.0, 1.25, 0.15), "plain": true,
		"guns": [], "gun_reach": 0.0,
	},
	Rig.Kind.HEAVY: {
		"model": "lobber", "height": 2.7, "tint": Color(0.35, 1.3, 2.1), "plain": true,
		"guns": ["gun.001", "gun.002"], "gun_reach": 0.55,
	},
	Rig.Kind.BOSS: {
		"model": "lobber", "height": 4.4, "tint": Color(1.7, 0.25, 1.5), "plain": true,
		"guns": ["gun.001", "gun.002"], "gun_reach": 0.9,
	},
}

## The panels that take the robot's colour; the rest is metal and rubber.
const PANEL_MATERIAL := "Orange plastic"
## The bodywork, which takes a lighter wash of the same colour.
const METAL_MATERIAL := "Metal grid"

# Set by the owner every frame.
var speed := 0.0
var aim_pitch := 0.0
var lean := 0.0
## 0..1 while the robot winds up a shot; its eye and panels glow.
var charge := 0.0

var height := 2.0
var muzzle: Node3D
var muzzle_2: Node3D

var _model: Node3D
var _anim: AnimationPlayer
var _skeleton: Skeleton3D
var _meshes: Array[MeshInstance3D] = []
var _panel: StandardMaterial3D
var _scale := 1.0
var _gun_bones: Array[int] = []
var _busy := 0.0        # seconds left of a one-off animation (attack)
var _dying := false
var _time := 0.0
var _info: Dictionary = {}

static var _flash_mat: StandardMaterial3D


func build(kind: Rig.Kind) -> void:
	var info: Dictionary = KINDS[kind]
	_info = info
	_model = load("res://assets/models/%s.glb" % info.model).instantiate()
	add_child(_model)
	_anim = _model.find_child("AnimationPlayer", true, false)
	_skeleton = _model.find_child("Skeleton3D", true, false)

	# The models are built at their own size; scale each to its game height.
	var bounds := _bounds(_model)
	_scale = float(info.height) / maxf(bounds.size.y, 0.01)
	height = float(info.height)
	_model.scale = Vector3.ONE * _scale
	_model.position.y = -bounds.position.y * _scale

	for mi in _find_meshes(_model):
		_meshes.append(mi)
		for surface in mi.mesh.get_surface_count():
			var m: StandardMaterial3D = mi.get_active_material(surface)
			if m == null:
				continue
			# A private copy of each material, so this kind of robot can carry
			# its own colour without touching the others.
			var own: StandardMaterial3D = m.duplicate()
			if m.resource_name == PANEL_MATERIAL:
				if info.plain:
					# A grey version of the panels, so any colour reads true.
					own.albedo_texture = load("res://assets/models/plastic_plain.png")
				own.albedo_color = info.tint
				own.emission_enabled = true
				own.emission = Color(info.tint).clamp()
				own.emission_energy_multiplier = 0.0
				_panel = own
			elif m.resource_name == METAL_MATERIAL:
				# The bodywork takes a hint of the same colour.
				own.albedo_color = Color.WHITE.lerp(Color(info.tint).clamp(), 0.45)
			mi.set_surface_override_material(surface, own)

	# Points the shots come from, kept on the gun bones as the robot moves.
	for bone_name: String in info.guns:
		var index := _skeleton.find_bone(bone_name) if _skeleton else -1
		if index < 0:
			continue
		_gun_bones.append(index)
		var point := Node3D.new()
		add_child(point)
		if muzzle == null:
			muzzle = point
		else:
			muzzle_2 = point
	if muzzle == null:
		# No gun bones: fire from the front of the body.
		muzzle = Node3D.new()
		muzzle.position = Vector3(0, height * 0.6, -height * 0.3)
		add_child(muzzle)

	_play("idle")


func _process(dt: float) -> void:
	_time += dt
	if _dying:
		_follow_guns()
		return
	if _busy > 0.0:
		_busy -= dt
	elif _anim:
		_play("move" if speed > 0.35 else "idle")
		if _anim.current_animation == "move":
			_anim.speed_scale = clampf(speed / 2.4, 0.6, 2.0)
		else:
			_anim.speed_scale = 1.0
	if _panel:
		# The panels glow as the robot winds up a shot.
		_panel.emission_energy_multiplier = charge * 2.5 + 0.05 + 0.04 * sin(_time * 3.0)
	_follow_guns()


## Keeps each muzzle on the tip of its gun as the arms swing.
func _follow_guns() -> void:
	if _skeleton == null or _gun_bones.is_empty():
		return
	var reach: float = _info.get("gun_reach", 0.5)
	var points: Array[Node3D] = [muzzle, muzzle_2]
	for i in mini(_gun_bones.size(), points.size()):
		var point := points[i]
		if point == null:
			continue
		var at: Vector3 = _skeleton.global_transform * _skeleton.get_bone_global_pose(_gun_bones[i]).origin
		point.global_position = at - global_transform.basis.z * reach


func _play(anim_name: String, once := false) -> void:
	if _anim == null or not _anim.has_animation(anim_name):
		return
	if _anim.current_animation == anim_name and not once:
		return
	_anim.get_animation(anim_name).loop_mode = Animation.LOOP_NONE if once else Animation.LOOP_LINEAR
	_anim.speed_scale = 1.0
	_anim.play(anim_name, 0.15)
	if once:
		_busy = _anim.get_animation(anim_name).length / maxf(_anim.speed_scale, 0.01)


## The robot swings or recoils as it fires.
func kick(_amount := 1.0) -> void:
	if _dying:
		return
	_play("attack", true)
	# Attack animations run long; cut them short so the robot keeps moving.
	_busy = minf(_busy, 0.55)


## Everything flashes white for a moment; call when hit.
func flash() -> void:
	if _flash_mat == null:
		_flash_mat = StandardMaterial3D.new()
		_flash_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		_flash_mat.albedo_color = Color(1, 1, 1, 0.8)
		_flash_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	for m in _meshes:
		m.material_overlay = _flash_mat
	var timer := get_tree().create_timer(0.07, false)
	timer.timeout.connect(func():
		for m in _meshes:
			if is_instance_valid(m):
				m.material_overlay = null)


## The robot drops where it stands: it is handed to the world, plays one of
## its death animations, then sinks into the floor and is removed.
func explode(world: Node3D, push: Vector3) -> void:
	if _dying:
		return
	_dying = true
	var at := global_transform
	get_parent().remove_child(self)
	world.add_child(self)
	global_transform = at
	# Knocked back a little by the shot that finished it.
	var shove := Vector3(push.x, 0, push.z).limit_length(1.0) * 0.6
	var fall: String = ["die1", "die2", "die3"][randi() % 3]
	_play(fall, true)
	var seconds: float = _anim.get_animation(fall).length if _anim and _anim.has_animation(fall) else 0.8
	var tw := create_tween()
	tw.tween_property(self, "position", position + shove, 0.25)
	tw.tween_interval(maxf(0.1, seconds - 0.25) + 1.2)
	tw.tween_property(self, "position:y", position.y - height, 0.8).set_ease(Tween.EASE_IN)
	tw.tween_callback(queue_free)


static func _find_meshes(node: Node) -> Array[MeshInstance3D]:
	var out: Array[MeshInstance3D] = []
	if node is MeshInstance3D:
		out.append(node)
	for c in node.get_children():
		out.append_array(_find_meshes(c))
	return out


static func _bounds(node: Node) -> AABB:
	var out := AABB()
	var first := true
	for m in _find_meshes(node):
		var b := m.get_aabb()
		out = b if first else out.merge(b)
		first = false
	return out
