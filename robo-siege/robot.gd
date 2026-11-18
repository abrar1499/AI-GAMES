extends Node3D
## A robot built from a rigged model, with its own walk, attack and death
## animations. It offers the same handful of calls the old box-built rig did,
## so enemy.gd only has to say how fast the robot is moving and when it fires.

const Rig := preload("res://rig.gd")

## Which mech each robot is, how tall it stands, its colour, and which of
## its animations to use. They are the same family as the player's mech, so
## the whole battle reads as one world.
const KINDS := {
	Rig.Kind.GRUNT: {
		"model": "george", "height": 1.9, "hover": 0.0,
		"tint": Color(1.25, 0.75, 0.6), "glow": Color("ff6a3c"),
		"holding": true, "muzzle": Vector3(0.42, 1.25, -0.62), "muzzle_2": Vector3.ZERO,
	},
	Rig.Kind.RUSHER: {
		"model": "leela", "height": 1.7, "hover": 0.0,
		"tint": Color(1.45, 1.2, 0.35), "glow": Color("ffd000"),
		"holding": false, "muzzle": Vector3(0.0, 1.1, -0.6), "muzzle_2": Vector3.ZERO,
	},
	Rig.Kind.HEAVY: {
		"model": "stan", "height": 2.6, "hover": 0.0,
		"tint": Color(0.6, 1.0, 1.45), "glow": Color("3ca0ff"),
		"holding": true, "muzzle": Vector3(0.55, 1.75, -0.8), "muzzle_2": Vector3(-0.5, 1.6, -0.7),
	},
	Rig.Kind.BOSS: {
		"model": "stan", "height": 4.2, "hover": 0.0,
		"tint": Color(1.5, 0.45, 0.9), "glow": Color("ff2d6f"),
		"holding": true, "muzzle": Vector3(0.9, 2.8, -1.3), "muzzle_2": Vector3(-0.8, 2.6, -1.2),
	},
}

## The animations every mech in the pack carries.
const CLIPS := {
	"idle": "RobotArmature|Idle",
	"walk_holding": "RobotArmature|Walk_Holding",
	"walk": "RobotArmature|Walk",
	"run_holding": "RobotArmature|Run_Holding",
	"run": "RobotArmature|Run",
	"attack": "RobotArmature|Shoot",
	"hit": "RobotArmature|HitRecieve_1",
	"die": "RobotArmature|Death",
}

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

	# Stand the mech at its game height; its own origin is at its feet.
	var head_bone := _skeleton.find_bone("Head")
	var head_up: float = _skeleton.get_bone_global_rest(head_bone).origin.y if head_bone >= 0 else 4.7
	_scale = (float(info.height) * 0.88) / maxf(head_up, 0.01)
	height = float(info.height)
	_model.scale = Vector3.ONE * _scale

	for mi in _find_meshes(_model):
		_meshes.append(mi)
		for surface in mi.mesh.get_surface_count():
			var base: StandardMaterial3D = mi.get_active_material(surface)
			if base == null:
				continue
			# A private copy, so this kind of robot carries its own colour.
			var m: StandardMaterial3D = base.duplicate()
			m.albedo_color = info.tint
			m.metallic = 0.6
			m.roughness = 0.45
			m.emission_enabled = true
			m.emission = info.glow
			m.emission_energy_multiplier = 0.0
			mi.set_surface_override_material(surface, m)
			_panel = m

	for clip_name: String in CLIPS.values():
		if _anim.has_animation(clip_name):
			_anim.get_animation(clip_name).loop_mode = Animation.LOOP_LINEAR

	# Where the shots come from.
	muzzle = Node3D.new()
	muzzle.position = info.muzzle
	add_child(muzzle)
	if (info.muzzle_2 as Vector3) != Vector3.ZERO:
		muzzle_2 = Node3D.new()
		muzzle_2.position = info.muzzle_2
		add_child(muzzle_2)

	_play(_clip("idle"))


## The animation this mech should use for one of our short names.
func _clip(which: String) -> String:
	var holding: bool = _info.get("holding", false)
	if which == "move":
		var fast := speed > 3.2
		if holding:
			which = "run_holding" if fast else "walk_holding"
		else:
			which = "run" if fast else "walk"
	var name: String = CLIPS.get(which, "")
	if _anim and not _anim.has_animation(name):
		name = CLIPS.get("walk" if which.begins_with("walk") else "run", CLIPS.idle)
	return name


func _process(dt: float) -> void:
	_time += dt
	if _dying:
		_follow_guns()
		return
	if _busy > 0.0:
		_busy -= dt
	elif _anim:
		_play(_clip("move") if speed > 0.35 else _clip("idle"))
		if speed > 0.35:
			_anim.speed_scale = clampf(speed / 2.4, 0.6, 2.0)
		else:
			_anim.speed_scale = 1.0
	if _panel:
		# The lights flare as the robot winds up a shot.
		# The robot's lights flare as it winds up a shot.
		_panel.emission_energy_multiplier = charge * 1.4 + 0.05 + 0.03 * sin(_time * 3.0)
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
	_play(_clip("attack"), true)
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
	var fall: String = _clip("die")
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
