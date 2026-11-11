extends Node3D
## Everything that flies or flashes: bullet tracers, sparks, explosions,
## damage numbers, the robots' energy bolts and the player's grenades.

const Rig := preload("res://rig.gd")

const WORLD := 1
const PLAYER := 2
const ENEMIES := 4

signal sound(sound_name: String, pitch: float, volume_db: float)

## Added to by explosions and hits; the camera reads it and shakes.
var shake := 0.0
## Who the bolts can hurt.
var player: Node3D

var _bolts: Array = []
var _grenades: Array = []
var _tracers: Array = []
var _glow := {}


func _process(dt: float) -> void:
	shake = maxf(0.0, shake - dt * 3.2)
	for t in _tracers:
		t.age += dt
		var k: float = t.age / t.life
		var node: MeshInstance3D = t.node
		node.scale.x = maxf(0.02, 1.0 - k)
		node.scale.y = node.scale.x
		if k >= 1.0:
			node.queue_free()
	_tracers = _tracers.filter(func(t): return t.age < t.life)


func _physics_process(dt: float) -> void:
	_step_bolts(dt)
	_step_grenades(dt)


func clear() -> void:
	for b in _bolts:
		b.node.queue_free()
	for g in _grenades:
		g.node.queue_free()
	_bolts.clear()
	_grenades.clear()
	shake = 0.0


## A bright, unlit material that glows the same in light and shadow.
func glow(color: Color, alpha := 1.0) -> StandardMaterial3D:
	var key := color.to_html() + str(alpha)
	if _glow.has(key):
		return _glow[key]
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.albedo_color = Color(color, alpha)
	if alpha < 1.0:
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_glow[key] = m
	return m


# ---------------------------------------------------------------- one-off effects

## The streak a bullet leaves from the gun to where it hit.
func tracer(from: Vector3, to: Vector3, color := Color("ffe9a8")) -> void:
	var length := from.distance_to(to)
	if length < 0.3:
		return
	var mesh := BoxMesh.new()
	mesh.size = Vector3(0.05, 0.05, length)
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = glow(color)
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(mi)
	mi.look_at_from_position((from + to) * 0.5, to, Vector3.UP if absf((to - from).normalized().y) < 0.98 else Vector3.RIGHT)
	_tracers.append({"node": mi, "age": 0.0, "life": 0.07})


## A spray of little bright bits, for bullet hits and breaking robots.
func sparks(at: Vector3, color: Color, count := 8, speed := 5.0, size := 0.06, life := 0.35) -> void:
	var p := CPUParticles3D.new()
	p.one_shot = true
	p.explosiveness = 1.0
	p.amount = count
	p.lifetime = life
	p.direction = Vector3.UP
	p.spread = 180.0
	p.initial_velocity_min = speed * 0.4
	p.initial_velocity_max = speed
	p.gravity = Vector3(0, -12, 0)
	p.scale_amount_min = 0.5
	p.scale_amount_max = 1.2
	var mesh := BoxMesh.new()
	mesh.size = Vector3.ONE * size
	mesh.material = glow(color)
	p.mesh = mesh
	p.position = at
	add_child(p)
	p.emitting = true
	get_tree().create_timer(life + 0.2, false).timeout.connect(p.queue_free)


## Rising grey puffs.
func smoke(at: Vector3, count := 10, size := 0.5) -> void:
	var p := CPUParticles3D.new()
	p.one_shot = true
	p.explosiveness = 0.9
	p.amount = count
	p.lifetime = 1.1
	p.direction = Vector3.UP
	p.spread = 70.0
	p.initial_velocity_min = 0.6
	p.initial_velocity_max = 2.4
	p.gravity = Vector3(0, 1.2, 0)
	p.scale_amount_min = 0.5
	p.scale_amount_max = 1.3
	var curve := Curve.new()
	curve.add_point(Vector2(0, 0.4))
	curve.add_point(Vector2(0.3, 1.0))
	curve.add_point(Vector2(1, 0.0))
	p.scale_amount_curve = curve
	var mesh := SphereMesh.new()
	mesh.radius = size * 0.5
	mesh.height = size
	mesh.radial_segments = 8
	mesh.rings = 4
	mesh.material = glow(Color(0.25, 0.25, 0.28), 0.6)
	p.mesh = mesh
	p.position = at
	add_child(p)
	p.emitting = true
	get_tree().create_timer(1.4, false).timeout.connect(p.queue_free)


func explosion(at: Vector3, radius: float, color := Color("ff8a1f")) -> void:
	for layer in 2:
		var mesh := SphereMesh.new()
		mesh.radius = 1.0
		mesh.height = 2.0
		mesh.radial_segments = 16
		mesh.rings = 8
		var m := StandardMaterial3D.new()
		m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		m.albedo_color = Color(1, 0.96, 0.8, 0.95) if layer == 0 else Color(color, 0.7)
		var mi := MeshInstance3D.new()
		mi.mesh = mesh
		mi.material_override = m
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		mi.position = at
		mi.scale = Vector3.ONE * 0.2
		add_child(mi)
		var big := radius * (0.45 if layer == 0 else 0.9)
		var tw := mi.create_tween().set_parallel(true)
		tw.tween_property(mi, "scale", Vector3.ONE * big, 0.28).set_trans(Tween.TRANS_QUART).set_ease(Tween.EASE_OUT)
		tw.tween_property(m, "albedo_color:a", 0.0, 0.32 if layer == 0 else 0.42)
		tw.chain().tween_callback(mi.queue_free)
	var light := OmniLight3D.new()
	light.light_color = color
	light.light_energy = 6.0
	light.omni_range = radius * 2.5
	light.position = at + Vector3(0, 0.5, 0)
	add_child(light)
	var lt := light.create_tween()
	lt.tween_property(light, "light_energy", 0.0, 0.4)
	lt.tween_callback(light.queue_free)
	sparks(at, Color("ffd27a"), 26, radius * 2.6, 0.09, 0.6)
	smoke(at + Vector3(0, 0.3, 0), 12, radius * 0.35)
	if player:
		var d := at.distance_to(player.global_position)
		shake = maxf(shake, clampf(1.2 - d / 22.0, 0.0, 1.0))
	sound.emit("boom", randf_range(0.85, 1.1), 0.0)


## A quick flash of light at the end of a gun.
func muzzle_flash(at: Vector3, color := Color("ffd27a"), size := 0.22) -> void:
	var mesh := SphereMesh.new()
	mesh.radius = size
	mesh.height = size * 2.0
	mesh.radial_segments = 8
	mesh.rings = 4
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = glow(color)
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	mi.position = at
	add_child(mi)
	var tw := mi.create_tween()
	tw.tween_property(mi, "scale", Vector3.ONE * 0.1, 0.06)
	tw.tween_callback(mi.queue_free)


## A number that pops up where damage was done and floats away.
func number(at: Vector3, text: String, color: Color, big := false) -> void:
	var label := Label3D.new()
	label.text = text
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.no_depth_test = true
	label.fixed_size = true
	label.pixel_size = 0.0016 if big else 0.0011
	label.font_size = 64
	label.outline_size = 16
	label.modulate = color
	label.position = at + Vector3(randf_range(-0.25, 0.25), 0.2, randf_range(-0.25, 0.25))
	add_child(label)
	var tw := label.create_tween().set_parallel(true)
	tw.tween_property(label, "position:y", label.position.y + 1.3, 0.6).set_ease(Tween.EASE_OUT)
	tw.tween_property(label, "modulate:a", 0.0, 0.6).set_ease(Tween.EASE_IN)
	tw.chain().tween_callback(label.queue_free)


## The pillar of light a robot arrives in.
func beam(at: Vector3, color := Color("ff3b30")) -> void:
	var mesh := CylinderMesh.new()
	mesh.top_radius = 0.9
	mesh.bottom_radius = 0.9
	mesh.height = 14.0
	mesh.radial_segments = 12
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.albedo_color = Color(color, 0.55)
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = m
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	mi.position = at + Vector3(0, 7, 0)
	add_child(mi)
	var tw := mi.create_tween().set_parallel(true)
	tw.tween_property(mi, "scale", Vector3(0.05, 1, 0.05), 0.5).set_ease(Tween.EASE_IN)
	tw.tween_property(m, "albedo_color:a", 0.0, 0.5)
	tw.chain().tween_callback(mi.queue_free)
	sparks(at + Vector3(0, 0.3, 0), color, 10, 4.0)


# ---------------------------------------------------------------- robot bolts

## A slow, visible shot from a robot. The player can step out of its way.
func fire_bolt(from: Vector3, velocity: Vector3, damage: float, color := Color("ff5a3c")) -> void:
	var mesh := SphereMesh.new()
	mesh.radius = 0.13
	mesh.height = 0.26
	mesh.radial_segments = 8
	mesh.rings = 4
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = glow(color)
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	mi.scale = Vector3(1, 1, 3.2)
	add_child(mi)
	mi.look_at_from_position(from, from + velocity, Vector3.UP if absf(velocity.normalized().y) < 0.98 else Vector3.RIGHT)
	_bolts.append({"node": mi, "pos": from, "vel": velocity, "damage": damage, "life": 4.0, "origin": from, "color": color})


func _step_bolts(dt: float) -> void:
	if _bolts.is_empty():
		return
	var space := get_world_3d().direct_space_state
	var keep: Array = []
	for b in _bolts:
		var from: Vector3 = b.pos
		var to: Vector3 = from + b.vel * dt
		var q := PhysicsRayQueryParameters3D.create(from, to, WORLD | PLAYER)
		var hit := space.intersect_ray(q)
		b.life -= dt
		if not hit.is_empty():
			if hit.collider.has_method("take_damage"):
				hit.collider.take_damage(b.damage, b.origin)
			sparks(hit.position, b.color, 6, 3.5)
			b.node.queue_free()
			continue
		if b.life <= 0.0:
			b.node.queue_free()
			continue
		b.pos = to
		(b.node as Node3D).position = to
		keep.append(b)
	_bolts = keep


# ---------------------------------------------------------------- grenades

## `on_explode` is called with the position where it went off.
func throw_grenade(from: Vector3, velocity: Vector3, fuse: float, on_explode: Callable) -> void:
	var mesh := SphereMesh.new()
	mesh.radius = 0.14
	mesh.height = 0.28
	mesh.radial_segments = 10
	mesh.rings = 5
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = Rig.mat(Color("2f3a2a"), 0.0, 0.5, 0.5)
	add_child(mi)
	var light := MeshInstance3D.new()
	var dot := SphereMesh.new()
	dot.radius = 0.06
	dot.height = 0.12
	light.mesh = dot
	light.material_override = glow(Color("ff3b30"))
	light.position = Vector3(0, 0.13, 0)
	mi.add_child(light)
	mi.position = from
	_grenades.append({"node": mi, "pos": from, "vel": velocity, "fuse": fuse, "done": on_explode, "light": light})


func _step_grenades(dt: float) -> void:
	if _grenades.is_empty():
		return
	var space := get_world_3d().direct_space_state
	var keep: Array = []
	for g in _grenades:
		g.fuse -= dt
		var vel: Vector3 = g.vel
		vel.y -= 18.0 * dt
		var from: Vector3 = g.pos
		var to: Vector3 = from + vel * dt
		var hit := space.intersect_ray(PhysicsRayQueryParameters3D.create(from, to, WORLD | ENEMIES))
		var explode: bool = g.fuse <= 0.0
		if not hit.is_empty():
			if (hit.collider as CollisionObject3D).collision_layer & ENEMIES:
				explode = true
				to = hit.position
			else:
				# Bounce, losing most of the speed.
				vel = vel.bounce(hit.normal) * 0.42
				to = hit.position + hit.normal * 0.15
		if explode:
			g.node.queue_free()
			(g.done as Callable).call(to)
			continue
		g.vel = vel
		g.pos = to
		var node: Node3D = g.node
		node.position = to
		node.rotate_x(dt * 9.0)
		(g.light as Node3D).visible = fmod(g.fuse, 0.2) > 0.1
		keep.append(g)
	_grenades = keep
