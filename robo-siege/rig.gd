extends Node3D
## A character built from simple shapes, with a walk cycle made in code.
## Used for the player's trooper and for every robot. It faces -Z.
##
## The owner sets `speed`, `aim_pitch` and `lean` every frame and calls
## `kick()` when firing, `flash()` when hit and `explode()` when destroyed.

enum Kind { TROOPER, GRUNT, RUSHER, HEAVY, BOSS }

## Metres per second over the ground; drives the legs.
var speed := 0.0
## Looking up (+) or down (-), in radians; tilts the upper body.
var aim_pitch := 0.0
## Sideways movement from -1 to 1; the body leans into it.
var lean := 0.0
## 0..1, makes the eye glow before a robot fires.
var charge := 0.0

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


func build(kind: Kind) -> void:
	body = _pivot(self, Vector3.ZERO)
	match kind:
		Kind.TROOPER:
			_build_trooper()
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


func _build_trooper() -> void:
	var armour := mat(Color("e8eef5"), 0.0, 0.3, 0.45)
	var suit := mat(Color("26324a"), 0.0, 0.1, 0.8)
	var accent := mat(Color("ff7a1a"), 0.0, 0.2, 0.5)
	var visor := mat(Color("35e0ff"), 1.6, 0.6, 0.2)
	var metal := mat(Color("2a2d33"), 0.0, 0.7, 0.35)

	hip_l = _pivot(body, Vector3(-0.15, 0.9, 0))
	hip_r = _pivot(body, Vector3(0.15, 0.9, 0))
	for hip: Node3D in [hip_l, hip_r]:
		_box(hip, Vector3(0.19, 0.5, 0.22), Vector3(0, -0.25, 0), suit)
		_box(hip, Vector3(0.21, 0.32, 0.25), Vector3(0, -0.62, 0.0), armour)
		_box(hip, Vector3(0.21, 0.12, 0.34), Vector3(0, -0.84, -0.05), metal)

	upper = _pivot(body, Vector3(0, 0.92, 0))
	_box(upper, Vector3(0.46, 0.2, 0.26), Vector3(0, 0.08, 0), suit)
	_box(upper, Vector3(0.56, 0.42, 0.32), Vector3(0, 0.4, 0), armour)
	_box(upper, Vector3(0.3, 0.2, 0.06), Vector3(0, 0.44, -0.17), accent)
	_box(upper, Vector3(0.34, 0.4, 0.16), Vector3(0, 0.4, 0.22), metal)      # backpack
	_box(upper, Vector3(0.2, 0.16, 0.2), Vector3(-0.38, 0.56, 0), accent)   # shoulder pads
	_box(upper, Vector3(0.2, 0.16, 0.2), Vector3(0.38, 0.56, 0), accent)
	_ball(upper, 0.19, Vector3(0, 0.82, 0), armour)                          # helmet
	_box(upper, Vector3(0.26, 0.09, 0.1), Vector3(0, 0.83, -0.15), visor)

	# Both arms reach forward to hold the rifle.
	_box(upper, Vector3(0.13, 0.13, 0.5), Vector3(0.3, 0.42, -0.26), suit)
	_box(upper, Vector3(0.13, 0.13, 0.56), Vector3(-0.2, 0.4, -0.34), suit).rotation.y = -0.5

	gun = _pivot(upper, Vector3(0.2, 0.4, -0.5))
	_box(gun, Vector3(0.09, 0.15, 0.62), Vector3(0, 0, 0), metal)
	_box(gun, Vector3(0.07, 0.2, 0.1), Vector3(0, -0.15, 0.12), metal)       # magazine
	_box(gun, Vector3(0.07, 0.07, 0.3), Vector3(0, 0.02, -0.44), metal)      # barrel
	_box(gun, Vector3(0.05, 0.06, 0.16), Vector3(0, 0.11, -0.05), accent)    # sight
	_box(gun, Vector3(0.08, 0.12, 0.22), Vector3(0, -0.03, 0.4), suit)       # stock
	muzzle = _pivot(gun, Vector3(0, 0.02, -0.62))
	height = 1.85


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
