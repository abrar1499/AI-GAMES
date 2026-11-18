extends Node3D
## A character built from simple shapes, with a walk cycle made in code.
## Used for the player's trooper and for every robot. It faces -Z.
##
## The owner sets `speed`, `aim_pitch` and `lean` every frame and calls
## `kick()` when firing, `flash()` when hit and `explode()` when destroyed.

enum Kind { TROOPER, GRUNT, RUSHER, HEAVY, BOSS }

const PLAYER_MODEL := preload("res://assets/models/mike.glb")
const RIFLE := preload("res://assets/scifi/Gun_Rifle.gltf")

## The paint jobs the player can choose.
const LOOKS := [
	{"name": "Sentinel", "tint": Color(1.0, 1.0, 1.0), "glow": Color("35e0ff")},
	{"name": "Ember", "tint": Color(1.35, 0.85, 0.6), "glow": Color("ff7a1a")},
	{"name": "Venom", "tint": Color(0.75, 1.3, 0.8), "glow": Color("7bff4a")},
	{"name": "Void", "tint": Color(0.85, 0.8, 1.35), "glow": Color("b06cff")},
]

## The animations the mech ships with, under short names.
const CLIPS := {
	"idle": "RobotArmature|Idle",
	"walk": "RobotArmature|Walk_Holding",
	"run": "RobotArmature|Run_Holding",
	"shoot": "RobotArmature|Shoot",
	"death": "RobotArmature|Death",
}

## Metres per second over the ground; drives the legs.
var speed := 0.0
## Looking up (+) or down (-), in radians; tilts the upper body.
var aim_pitch := 0.0
## Sideways movement from -1 to 1; the body leans into it.
var lean := 0.0
## 0..1, makes the eye glow before a robot fires.
var charge := 0.0
## Walking backwards: the soldier's steps play in reverse.
var backwards := false

var body: Node3D
var upper: Node3D
var hip_l: Node3D
var hip_r: Node3D
var gun: Node3D
var muzzle: Node3D
var muzzle_2: Node3D
var eye_mat: StandardMaterial3D
var meshes: Array[MeshInstance3D] = []
var height := 1.8

var _phase := 0.0
var _recoil := 0.0
var _flash := 0.0
var _time := 0.0
var _gun_rest := Vector3.ZERO
var _eye_energy := 2.0
var _model: Node3D
var _skeleton: Skeleton3D
var _anim: AnimationPlayer
var _arms: Array = []
var _chest := -1
var _shoot_left := 0.0

static var _mats := {}
static var _flash_mat: StandardMaterial3D


static func mat(color: Color, emission := 0.0, metallic := 0.25, roughness := 0.6) -> StandardMaterial3D:
	var key := "%s|%.2f|%.2f|%.2f" % [color.to_html(), emission, metallic, roughness]
	if _mats.has(key):
		return _mats[key]
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	m.metallic = metallic
	m.roughness = roughness
	if emission > 0.0:
		m.emission_enabled = true
		m.emission = color
		m.emission_energy_multiplier = emission
	_mats[key] = m
	return m


func _box(parent: Node3D, size: Vector3, pos: Vector3, material: Material) -> MeshInstance3D:
	var mesh := BoxMesh.new()
	mesh.size = size
	return _add(parent, mesh, pos, material)


func _ball(parent: Node3D, radius: float, pos: Vector3, material: Material, squash := 1.0) -> MeshInstance3D:
	var mesh := SphereMesh.new()
	mesh.radius = radius
	mesh.height = radius * 2.0 * squash
	mesh.radial_segments = 16
	mesh.rings = 8
	return _add(parent, mesh, pos, material)


func _tube(parent: Node3D, radius: float, length: float, pos: Vector3, material: Material) -> MeshInstance3D:
	var mesh := CylinderMesh.new()
	mesh.top_radius = radius
	mesh.bottom_radius = radius
	mesh.height = length
	mesh.radial_segments = 10
	return _add(parent, mesh, pos, material)


func _add(parent: Node3D, mesh: Mesh, pos: Vector3, material: Material) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.position = pos
	mi.material_override = material
	parent.add_child(mi)
	meshes.append(mi)
	return mi


func _pivot(parent: Node3D, pos: Vector3) -> Node3D:
	var n := Node3D.new()
	n.position = pos
	parent.add_child(n)
	return n


func build(kind: Kind, look := 0) -> void:
	body = _pivot(self, Vector3.ZERO)
	# Only the player is built here; the robots are models (robot.gd).
	_build_soldier(look)
	if gun:
		_gun_rest = gun.position


## The player: a walking mech that carries a rifle in its right hand.
func _build_soldier(look: int) -> void:
	var l: Dictionary = LOOKS[clampi(look, 0, LOOKS.size() - 1)]
	_model = PLAYER_MODEL.instantiate()
	body.add_child(_model)
	_skeleton = _model.find_child("Skeleton3D", true, false)
	_anim = _model.find_child("AnimationPlayer", true, false)

	# Stand the mech at a person's height; its own origin is at its feet.
	var head_bone := _skeleton.find_bone("Head")
	var head_up: float = _skeleton.get_bone_global_rest(head_bone).origin.y if head_bone >= 0 else 4.7
	var scale_to := 1.70 / maxf(head_up, 0.01)
	_model.scale = Vector3.ONE * scale_to
	height = 1.95

	for mi in _find_meshes(_model):
		meshes.append(mi)
		for surface in mi.mesh.get_surface_count():
			var base: StandardMaterial3D = mi.get_active_material(surface)
			if base == null:
				continue
			var m: StandardMaterial3D = base.duplicate()
			m.albedo_color = l.tint
			m.metallic = 0.6
			m.roughness = 0.42
			mi.set_surface_override_material(surface, m)

	for clip_name: String in CLIPS.values():
		if _anim.has_animation(clip_name):
			_anim.get_animation(clip_name).loop_mode = Animation.LOOP_LINEAR
	_anim.play(CLIPS.idle)
	_chest = _skeleton.find_bone("Chest")

	# The rifle sits in the right hand and moves with it.
	var hand := BoneAttachment3D.new()
	hand.bone_name = "PalmI.R"
	_skeleton.add_child(hand)
	gun = Node3D.new()
	hand.add_child(gun)
	var rifle: Node3D = RIFLE.instantiate()
	gun.add_child(rifle)
	var rifle_long := _bounds(rifle).size.x
	rifle.scale = Vector3.ONE * (0.95 / maxf(rifle_long, 0.01)) / scale_to
	# The model lies along +X; turn it to point where the mech aims.
	rifle.rotation = Vector3(0, -PI * 0.5, 0)
	rifle.position = Vector3(0, 0, 0)
	for mi in _find_meshes(rifle):
		meshes.append(mi)
	muzzle = Node3D.new()
	muzzle.position = Vector3(0, 0, -0.62 / scale_to)
	gun.add_child(muzzle)


## Each frame: pick the animation for the speed and lean the chest into the aim.
func _animate_player(dt: float) -> void:
	_recoil = move_toward(_recoil, 0.0, dt * 9.0)
	_shoot_left = maxf(0.0, _shoot_left - dt)

	var want: String = CLIPS.idle
	if _shoot_left > 0.0:
		want = CLIPS.shoot
	elif speed > 3.6:
		want = CLIPS.run
	elif speed > 0.4:
		want = CLIPS.walk
	if _anim.current_animation != want:
		_anim.play(want, 0.12)
	var pace := 1.0
	if want == CLIPS.run:
		pace = clampf(speed / 5.5, 0.7, 1.6)
	elif want == CLIPS.walk:
		pace = clampf(speed / 1.7, 0.6, 1.8)
	_anim.speed_scale = -pace if (backwards and want == CLIPS.walk) else pace

	body.rotation.z = lerpf(body.rotation.z, -lean * 0.08, minf(1.0, dt * 8.0))
	# Aim: the chest tips up and down with the sights.
	if _chest >= 0 and _skeleton:
		var rest := _skeleton.get_bone_rest(_chest).basis.get_rotation_quaternion()
		_skeleton.set_bone_pose_rotation(_chest, rest * Quaternion(Vector3.RIGHT, -aim_pitch * 0.55 - _recoil * 0.12))


static func _find_meshes(node: Node) -> Array[MeshInstance3D]:
	var out: Array[MeshInstance3D] = []
	if node is MeshInstance3D:
		out.append(node)
	for c in node.get_children():
		out.append_array(_find_meshes(c))
	return out


static func _bounds(node: Node) -> AABB:
	# Each mesh reports its box in its own space, so every box has to be
	# carried up into the model's space before they are merged.
	var out := AABB()
	var first := true
	for entry in _mesh_boxes(node, Transform3D.IDENTITY):
		var b: AABB = entry
		out = b if first else out.merge(b)
		first = false
	return out


static func _mesh_boxes(node: Node, up: Transform3D) -> Array:
	var here := up
	if node is Node3D and node.get_parent() != null:
		here = up * (node as Node3D).transform
	var out: Array = []
	if node is MeshInstance3D:
		out.append(here * (node as MeshInstance3D).get_aabb())
	for c in node.get_children():
		out.append_array(_mesh_boxes(c, here))
	return out


func _reach(arm: Dictionary, target: Vector3, pole: Vector3) -> void:
	var shoulder: Vector3 = (_skeleton.get_bone_global_pose(_skeleton.get_bone_parent(arm.arm)) * _skeleton.get_bone_rest(arm.arm)).origin
	var a: float = arm.upper_len
	var b: float = arm.fore_len
	var to := target - shoulder
	var d := clampf(to.length(), absf(a - b) + 0.01, a + b - 0.01)
	var dir := to.normalized()
	# Where the elbow has to be for both bones to fit.
	var along := (a * a - b * b + d * d) / (2.0 * d)
	var out := sqrt(maxf(a * a - along * along, 0.0))
	var side := (pole - dir * pole.dot(dir)).normalized()
	var elbow := shoulder + dir * along + side * out
	_point_bone(arm.arm, arm.fore, elbow - shoulder)
	_point_bone(arm.fore, arm.hand, shoulder + dir * d - elbow)
	# The hand carries on in line with the forearm.
	_skeleton.set_bone_pose_rotation(arm.hand, _skeleton.get_bone_rest(arm.hand).basis.get_rotation_quaternion())


## Turns `bone` so that it points along `dir`; `child` tells which way the
## bone runs.
func _point_bone(bone: int, child: int, dir: Vector3) -> void:
	var parent := _skeleton.get_bone_global_pose(_skeleton.get_bone_parent(bone)).basis.orthonormalized()
	var rest: Basis = parent * _skeleton.get_bone_rest(bone).basis.orthonormalized()
	var now := (rest * _skeleton.get_bone_rest(child).origin).normalized()
	var turned := Basis(Quaternion(now, dir.normalized())) * rest
	_skeleton.set_bone_pose_rotation(bone, (parent.inverse() * turned).get_rotation_quaternion())


func _process(dt: float) -> void:
	_time += dt
	if body == null:
		return
	if _model:
		_animate_player(dt)
		_tick_flash(dt)
		return
	var moving := clampf(speed / 4.0, 0.0, 1.0)
	_phase += dt * (3.0 + speed * 1.6) * moving
	var swing := sin(_phase) * 0.75 * moving
	hip_l.rotation.x = swing
	hip_r.rotation.x = -swing
	body.position.y = absf(cos(_phase)) * 0.06 * moving + sin(_time * 2.2) * 0.012
	body.rotation.z = lerpf(body.rotation.z, -lean * 0.12, minf(1.0, dt * 8.0))

	_recoil = move_toward(_recoil, 0.0, dt * 9.0)
	if upper:
		upper.rotation.x = aim_pitch * 0.7 + _recoil * 0.07
		upper.rotation.y = sin(_phase) * 0.06 * moving
	if gun:
		gun.position = _gun_rest + Vector3(0, 0, _recoil * 0.1)

	if eye_mat:
		eye_mat.emission_energy_multiplier = _eye_energy + charge * 7.0 + sin(_time * 5.0) * 0.4

	_tick_flash(dt)


func _tick_flash(dt: float) -> void:
	if _flash > 0.0:
		_flash -= dt
		if _flash <= 0.0:
			for m in meshes:
				m.material_overlay = null


## The gun jumps back; call when a shot is fired.
func kick(amount := 1.0) -> void:
	_recoil = amount
	_shoot_left = 0.18


## Everything turns white for a moment; call when hit.
func flash() -> void:
	if _flash_mat == null:
		_flash_mat = StandardMaterial3D.new()
		_flash_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		_flash_mat.albedo_color = Color(1, 1, 1, 0.75)
		_flash_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_flash = 0.07
	for m in meshes:
		m.material_overlay = _flash_mat


## Breaks the character into its parts, which fly off and then vanish.
## `push` is the direction of the blow.
func explode(world: Node3D, push: Vector3) -> void:
	for m in meshes:
		if not is_instance_valid(m):
			continue
		var at := m.global_transform
		var piece := RigidBody3D.new()
		piece.collision_layer = 0
		piece.collision_mask = 1
		var shape := BoxShape3D.new()
		shape.size = (m.get_aabb().size * at.basis.get_scale()).max(Vector3.ONE * 0.08)
		var cs := CollisionShape3D.new()
		cs.shape = shape
		piece.add_child(cs)
		world.add_child(piece)
		piece.global_transform = at.orthonormalized()
		m.get_parent().remove_child(m)
		piece.add_child(m)
		m.transform = Transform3D(Basis.from_scale(at.basis.get_scale()), Vector3.ZERO)
		m.material_overlay = null
		var away := (at.origin - global_position).normalized() + Vector3(0, 0.9, 0)
		piece.linear_velocity = away * randf_range(2.5, 6.0) + push * randf_range(2.0, 5.0)
		piece.angular_velocity = Vector3(randf_range(-9, 9), randf_range(-9, 9), randf_range(-9, 9))
		var tw := piece.create_tween()
		tw.tween_interval(randf_range(1.6, 2.6))
		tw.tween_property(m, "scale", m.scale * 0.01, 0.35)
		tw.tween_callback(piece.queue_free)
	meshes.clear()
