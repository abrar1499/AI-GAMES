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

## The same soldier in enemy colours, used by robot.gd.
const ENEMY_LOOKS := {
	"scout": Color(1.5, 0.8, 0.35),
	"rusher": Color(1.5, 1.25, 0.3),
	"gunner": Color(0.45, 0.95, 1.6),
	"boss": Color(1.15, 0.45, 1.8),
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
## Which weapon model this character carries, and how long it should be.
var gun_model := "AssaultRifle_1"
var gun_length := 0.95
## Painted on the gun's "Main" parts; the metal and wood keep their own
## colours. Transparent leaves the model as it is.
var gun_tint := Color.TRANSPARENT

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


func build(kind: Kind, look := 0, weapon := "", weapon_length := 0.0, weapon_tint := Color.TRANSPARENT) -> void:
	if weapon != "":
		gun_model = weapon
		gun_length = weapon_length
		gun_tint = weapon_tint
	body = _pivot(self, Vector3.ZERO)
	# Only the player is built here; the robots are models (robot.gd).
	_build_soldier(look)
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

	# The weapon, a model from the sci-fi kit, held in front of the chest.
	gun = _pivot(body, Vector3(0.14, 1.32, -0.3))
	var weapon: Node3D = make_weapon(gun_model, gun_length, gun_tint)
	gun.add_child(weapon)
	var box := _bounds(weapon)
	for mi in _find_meshes(weapon):
		meshes.append(mi)
	# The barrel tip, taken from the model rather than guessed.
	muzzle = _pivot(gun, Vector3(0, 0, box.position.z))
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


## Puts a different weapon in the trooper's hands, keeping the same grip.
func set_weapon(model: String, length: float, tint := Color.TRANSPARENT) -> void:
	gun_model = model
	gun_length = length
	gun_tint = tint
	if gun == null:
		return
	for child in gun.get_children():
		gun.remove_child(child)
		child.queue_free()
	meshes = meshes.filter(func(m): return is_instance_valid(m) and m.is_inside_tree())
	var weapon := make_weapon(model, length, tint)
	gun.add_child(weapon)
	for mi in _find_meshes(weapon):
		meshes.append(mi)
	muzzle = _pivot(gun, Vector3(0, 0, _bounds(weapon).position.z))


## Builds one weapon, scaled to `length` and painted, pointing down -Z with
## its grip at the origin. Used both in the trooper's hands and in the menu.
static func make_weapon(model: String, length: float, tint := Color.TRANSPARENT) -> Node3D:
	var holder := Node3D.new()
	var weapon: Node3D = load("res://assets/guns/%s.glb" % model).instantiate()
	holder.add_child(weapon)
	var raw := _bounds(weapon)
	var fit: float = length / maxf(raw.size.x, 0.01)
	weapon.scale = Vector3.ONE * fit
	# The gun models lie along X; turn them to point the way the trooper aims.
	weapon.rotation = Vector3(0, PI * 0.5, 0)
	# Sit the grip at the origin, with the barrel reaching forward.
	weapon.position = Vector3(0, -(raw.position.y + raw.size.y * 0.72) * fit, (raw.position.x + raw.size.x) * fit)
	if tint.a > 0.0:
		for mi in _find_meshes(weapon):
			for surface in mi.mesh.get_surface_count():
				var base: StandardMaterial3D = mi.get_active_material(surface)
				if base == null or not base.resource_name.begins_with("Main"):
					continue
				var m: StandardMaterial3D = base.duplicate()
				m.albedo_color = tint
				mi.set_surface_override_material(surface, m)
	return holder


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
