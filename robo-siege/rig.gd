extends Node3D
## A character built from simple shapes, with a walk cycle made in code.
## Used for the player's trooper and for every robot. It faces -Z.
##
## The owner sets `speed`, `aim_pitch` and `lean` every frame and calls
## `kick()` when firing, `flash()` when hit and `explode()` when destroyed.

enum Kind { TROOPER, GRUNT, RUSHER, HEAVY, BOSS }

const SOLDIER := preload("res://assets/soldier.glb")

## The uniforms the player can choose: a colour wash over the soldier model.
const LOOKS := [
	{"name": "Standard", "tint": Color(1.0, 1.0, 1.0)},
	{"name": "Desert", "tint": Color(1.25, 1.05, 0.72)},
	{"name": "Jungle", "tint": Color(0.62, 0.95, 0.55)},
	{"name": "Night", "tint": Color(0.42, 0.5, 0.72)},
]

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
	match kind:
		Kind.TROOPER:
			_build_soldier(look)
		Kind.GRUNT:
			_build_robot(Color("7f93ad"), Color("3a4458"), Color("ff3b30"), 1.0, false)
		Kind.RUSHER:
			_build_rusher()
		Kind.HEAVY:
			_build_robot(Color("5d7a4a"), Color("2f3a2a"), Color("ffc400"), 1.35, true)
		Kind.BOSS:
			_build_robot(Color("7a2230"), Color("1c1c22"), Color("ff2d6f"), 1.95, true)
	if gun:
		_gun_rest = gun.position


## The player: a rigged soldier model with its own walk and run animations.
## `look` picks one of LOOKS (a colour wash over the uniform).
func _build_soldier(look: int) -> void:
	var l: Dictionary = LOOKS[clampi(look, 0, LOOKS.size() - 1)]
	_model = SOLDIER.instantiate()
	body.add_child(_model)
	_skeleton = _model.find_child("Skeleton3D", true, false)
	_anim = _model.find_child("AnimationPlayer", true, false)
	for child in _skeleton.get_children():
		if child is MeshInstance3D:
			var mi: MeshInstance3D = child
			meshes.append(mi)
			# Tint a private copy of the material so each look is its own colour.
			for surface in mi.mesh.get_surface_count():
				var m: StandardMaterial3D = mi.get_active_material(surface).duplicate()
				m.albedo_color = l.tint
				mi.set_surface_override_material(surface, m)

	# The arms hold the rifle, so the walk and run must not swing them.
	for anim_name in _anim.get_animation_list():
		var anim := _anim.get_animation(anim_name)
		anim.loop_mode = Animation.LOOP_LINEAR
		for t in range(anim.get_track_count() - 1, -1, -1):
			var path := str(anim.track_get_path(t))
			if path.contains("Arm") or path.contains("Hand") or path.contains("Shoulder"):
				anim.remove_track(t)
	_anim.play("Idle")

	for side: String in ["Right", "Left"]:
		var arm := _skeleton.find_bone("mixamorig_%sArm" % side)
		var fore := _skeleton.find_bone("mixamorig_%sForeArm" % side)
		var hand := _skeleton.find_bone("mixamorig_%sHand" % side)
		_arms.append({
			"arm": arm, "fore": fore, "hand": hand,
			"upper_len": _skeleton.get_bone_rest(fore).origin.length(),
			"fore_len": _skeleton.get_bone_rest(hand).origin.length(),
		})
	_chest = _skeleton.find_bone("mixamorig_Spine2")

	# Curl the fingers round the rifle. In the model's rest pose the arms
	# are straight out sideways, palms down, so a finger closes by turning
	# about the model's front-to-back axis.
	for side: String in ["Right", "Left"]:
		var way := 1.0 if side == "Right" else -1.0
		for finger: String in ["Index", "Middle", "Ring", "Pinky"]:
			for joint in range(1, 4):
				var bone := _skeleton.find_bone("mixamorig_%sHand%s%d" % [side, finger, joint])
				if bone < 0:
					continue
				var rest_global := _skeleton.get_bone_global_rest(bone).basis.orthonormalized()
				var axis := (rest_global.inverse() * Vector3(0, 1, 0)).normalized()
				var rest := _skeleton.get_bone_rest(bone).basis.get_rotation_quaternion()
				_skeleton.set_bone_pose_rotation(bone, rest * Quaternion(axis, way * 0.95))

	var metal := mat(Color("2a2d33"), 0.0, 0.7, 0.35)
	var dark := mat(Color("16181d"), 0.0, 0.2, 0.5)
	var wood := mat(Color("5a4630"), 0.0, 0.0, 0.8)
	gun = _pivot(body, Vector3(0.14, 1.32, -0.3))
	gun.scale = Vector3.ONE * 1.2
	_box(gun, Vector3(0.055, 0.1, 0.46), Vector3(0, 0, 0), metal)
	_box(gun, Vector3(0.045, 0.17, 0.075), Vector3(0, -0.12, 0.04), metal)         # magazine
	_box(gun, Vector3(0.04, 0.09, 0.06), Vector3(0, -0.085, 0.18), dark)          # grip
	_tube(gun, 0.02, 0.32, Vector3(0, 0.02, -0.38), metal).rotation.x = PI * 0.5  # barrel
	_box(gun, Vector3(0.05, 0.065, 0.2), Vector3(0, -0.02, -0.25), wood)          # handguard
	_box(gun, Vector3(0.03, 0.04, 0.12), Vector3(0, 0.075, -0.02), dark)          # sight
	_box(gun, Vector3(0.045, 0.09, 0.22), Vector3(0, -0.02, 0.33), wood)          # stock
	muzzle = _pivot(gun, Vector3(0, 0.02, -0.56))
	# The gun's own parts must not flash or tint with the body.
	height = 1.85


## Each frame: pick the animation for the speed, keep the rifle at the
## chest, and bend both arms so the hands are on it.
func _animate_soldier(dt: float) -> void:
	var want := "Idle"
	if speed > 3.6:
		want = "Run"
	elif speed > 0.4:
		want = "Walk"
	if _anim.current_animation != want:
		_anim.play(want, 0.18)
	var pace := 1.0
	if want == "Run":
		pace = clampf(speed / 5.5, 0.7, 1.6)
	elif want == "Walk":
		pace = clampf(speed / 1.7, 0.6, 1.8)
	_anim.speed_scale = -pace if backwards and want != "Idle" else pace

	if not is_inside_tree():
		return
	_recoil = move_toward(_recoil, 0.0, dt * 9.0)
	body.rotation.z = lerpf(body.rotation.z, -lean * 0.08, minf(1.0, dt * 8.0))

	# Rifle: in front of the chest, tilted with the aim, kicking back on a shot.
	var to_rig := global_transform.affine_inverse() * _skeleton.global_transform
	var chest: Vector3 = to_rig * _skeleton.get_bone_global_pose(_chest).origin
	chest = body.transform.affine_inverse() * chest
	gun.rotation.x = aim_pitch * 0.85 + _recoil * 0.05
	gun.position = chest + Vector3(0.13, -0.07, -0.3) + Vector3(0, 0, _recoil * 0.05)

	# Hands: right on the grip, left under the handguard.
	var to_skeleton := _skeleton.global_transform.affine_inverse()
	var grips := [gun.global_transform * Vector3(0.0, -0.07, 0.17), gun.global_transform * Vector3(0.0, -0.06, -0.24)]
	var poles := [global_transform.basis * Vector3(0.5, -1.0, 0.3), global_transform.basis * Vector3(-0.7, -1.0, 0.0)]
	for i in 2:
		_reach(_arms[i], to_skeleton * (grips[i] as Vector3), (to_skeleton.basis * (poles[i] as Vector3)).normalized())


## Bends an arm at the elbow so the hand reaches `target`. `pole` is the
## way the elbow should point. All in the skeleton's own space.
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


## Grunts, heavies and the boss share a shape; `wide` adds shoulder guns.
func _build_robot(shell_col: Color, dark_col: Color, eye_col: Color, size: float, wide: bool) -> void:
	var shell := mat(shell_col, 0.0, 0.55, 0.4)
	var dark := mat(dark_col, 0.0, 0.6, 0.5)
	eye_mat = mat(eye_col, 2.0, 0.0, 0.3).duplicate()
	var glow := mat(eye_col, 1.2, 0.0, 0.4)
	body.scale = Vector3.ONE * size

	hip_l = _pivot(body, Vector3(-0.2, 0.85, 0))
	hip_r = _pivot(body, Vector3(0.2, 0.85, 0))
	for hip: Node3D in [hip_l, hip_r]:
		_tube(hip, 0.06, 0.5, Vector3(0, -0.25, 0), dark)
		_box(hip, Vector3(0.16, 0.36, 0.2), Vector3(0, -0.6, 0), shell)
		_box(hip, Vector3(0.2, 0.1, 0.36), Vector3(0, -0.8, -0.06), dark)

	upper = _pivot(body, Vector3(0, 0.88, 0))
	_tube(upper, 0.12, 0.2, Vector3(0, 0.06, 0), dark)
	_box(upper, Vector3(0.66 if wide else 0.56, 0.5, 0.4), Vector3(0, 0.42, 0), shell)
	_box(upper, Vector3(0.3, 0.12, 0.05), Vector3(0, 0.5, -0.21), glow)       # chest light
	_box(upper, Vector3(0.34, 0.26, 0.3), Vector3(0, 0.82, 0), dark)          # head
	_box(upper, Vector3(0.26, 0.08, 0.06), Vector3(0, 0.84, -0.16), eye_mat)  # eye
	_tube(upper, 0.015, 0.26, Vector3(0.12, 1.08, 0), dark)                   # aerial

	var arm_x := 0.47 if wide else 0.4
	_box(upper, Vector3(0.18, 0.2, 0.22), Vector3(-arm_x, 0.56, 0), dark)
	_box(upper, Vector3(0.18, 0.2, 0.22), Vector3(arm_x, 0.56, 0), dark)
	gun = _pivot(upper, Vector3(arm_x, 0.42, -0.2))
	_box(gun, Vector3(0.14, 0.14, 0.5), Vector3(0, 0, 0), shell)
	_tube(gun, 0.05, 0.3, Vector3(0, 0, -0.36), dark).rotation.x = PI * 0.5
	muzzle = _pivot(gun, Vector3(0, 0, -0.55))
	if wide:
		var left := _pivot(upper, Vector3(-arm_x, 0.42, -0.2))
		_box(left, Vector3(0.14, 0.14, 0.5), Vector3(0, 0, 0), shell)
		_tube(left, 0.05, 0.3, Vector3(0, 0, -0.36), dark).rotation.x = PI * 0.5
		muzzle_2 = _pivot(left, Vector3(0, 0, -0.55))
		_box(upper, Vector3(0.9, 0.1, 0.44), Vector3(0, 0.7, 0), mat(Color("ffc400"), 0.0, 0.3, 0.5))
	else:
		_box(upper, Vector3(0.12, 0.12, 0.4), Vector3(-arm_x, 0.42, -0.14), dark)
	height = 1.95 * size


## A small ball on legs that runs at you and blows up.
func _build_rusher() -> void:
	var shell := mat(Color("ff8a1f"), 0.0, 0.4, 0.4)
	var dark := mat(Color("2b2b30"), 0.0, 0.6, 0.5)
	eye_mat = mat(Color("ffe14a"), 2.5, 0.0, 0.3).duplicate()
	hip_l = _pivot(body, Vector3(-0.18, 0.6, 0))
	hip_r = _pivot(body, Vector3(0.18, 0.6, 0))
	for hip: Node3D in [hip_l, hip_r]:
		_tube(hip, 0.05, 0.5, Vector3(0, -0.25, 0), dark)
		_box(hip, Vector3(0.16, 0.1, 0.3), Vector3(0, -0.55, -0.05), dark)
	upper = _pivot(body, Vector3(0, 0.6, 0))
	_ball(upper, 0.4, Vector3(0, 0.35, 0), shell)
	_ball(upper, 0.17, Vector3(0, 0.38, -0.3), eye_mat)
	_box(upper, Vector3(0.86, 0.08, 0.2), Vector3(0, 0.35, 0), dark)
	_box(upper, Vector3(0.2, 0.86, 0.08), Vector3(0, 0.35, 0.05), dark)
	_tube(upper, 0.02, 0.3, Vector3(0, 0.85, 0), dark)
	_ball(upper, 0.06, Vector3(0, 1.02, 0), eye_mat)
	height = 1.15


func _process(dt: float) -> void:
	_time += dt
	if body == null:
		return
	if _model:
		_animate_soldier(dt)
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
