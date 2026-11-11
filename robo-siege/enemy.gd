extends CharacterBody3D
## One robot. Four kinds share this script:
##
## - GRUNT  walks into range, sidesteps, and fires single bolts.
## - RUSHER sprints at the trooper and blows itself up.
## - HEAVY  slow and tough, fires bursts from two guns.
## - BOSS   a huge heavy that arrives every fifth wave.

const Rig := preload("res://rig.gd")

const WORLD := 1
const ENEMIES := 4
const GRAVITY := 24.0

## hp, speed, damage, seconds between attacks, preferred range, bolts per
## burst, score, body radius.
const STATS := {
	Rig.Kind.GRUNT: {"hp": 70.0, "speed": 3.4, "damage": 9.0, "wait": 1.9, "range": 13.0, "burst": 1, "score": 100, "radius": 0.45},
	Rig.Kind.RUSHER: {"hp": 40.0, "speed": 7.2, "damage": 26.0, "wait": 0.0, "range": 0.0, "burst": 0, "score": 150, "radius": 0.42},
	Rig.Kind.HEAVY: {"hp": 320.0, "speed": 2.1, "damage": 11.0, "wait": 2.6, "range": 16.0, "burst": 4, "score": 400, "radius": 0.7},
	Rig.Kind.BOSS: {"hp": 1500.0, "speed": 2.4, "damage": 13.0, "wait": 2.2, "range": 18.0, "burst": 7, "score": 2500, "radius": 1.0},
}

signal destroyed(enemy: Node3D)
signal sound(sound_name: String, pitch: float, volume_db: float)

var kind: Rig.Kind = Rig.Kind.GRUNT
## 1 on wave 1, rising slowly; scales health and damage.
var power := 1.0
var hp := 70.0
var max_hp := 70.0
var dead := false
var score := 100
var rig: Node3D

var player: Node3D
var arena: Node3D
var fx: Node3D

var _stats: Dictionary
var _path := PackedVector3Array()
var _path_wait := 0.0
var _attack_wait := 1.0
var _charge := 0.0
var _burst_left := 0
var _burst_wait := 0.0
var _strafe := 1.0
var _strafe_wait := 0.0
var _fuse := -1.0
var _beep := 0.0
var _stagger := 0.0
var _push := Vector3.ZERO
var _arrive := 0.6


func setup(enemy_kind: Rig.Kind, wave_power: float, the_player: Node3D, the_arena: Node3D, the_fx: Node3D) -> void:
	kind = enemy_kind
	power = wave_power
	player = the_player
	arena = the_arena
	fx = the_fx
	_stats = STATS[kind]
	max_hp = float(_stats.hp) * power
	hp = max_hp
	score = int(_stats.score)


func _ready() -> void:
	add_to_group("enemies")
	collision_layer = ENEMIES
	collision_mask = WORLD
	rig = Rig.new()
	add_child(rig)
	rig.build(kind)
	var shape := CapsuleShape3D.new()
	shape.radius = float(_stats.radius)
	shape.height = maxf(rig.height, shape.radius * 2.0)
	var cs := CollisionShape3D.new()
	cs.shape = shape
	cs.position = Vector3(0, shape.height * 0.5, 0)
	add_child(cs)
	_strafe = 1.0 if randf() < 0.5 else -1.0
	_path_wait = randf() * 0.4
	_attack_wait = 0.8 + randf() * 1.2
	# Grow out of the arrival beam.
	rig.scale = Vector3.ONE * 0.05
	create_tween().tween_property(rig, "scale", Vector3.ONE, _arrive).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)


func aim_point() -> Vector3:
	return global_position + Vector3(0, rig.height * 0.6, 0)


func _physics_process(dt: float) -> void:
	if dead:
		return
	if _arrive > 0.0:
		_arrive -= dt
		return
	var to_player := player.global_position - global_position
	to_player.y = 0.0
	var dist := to_player.length()
	var sees: bool = dist < 45.0 and not player.dead and _sees_player()

	var wish := Vector3.ZERO
	if kind == Rig.Kind.RUSHER:
		wish = _rusher(dt, dist, to_player)
	else:
		wish = _shooter(dt, dist, to_player, sees)

	# Do not stand inside each other.
	for other: Node3D in get_tree().get_nodes_in_group("enemies"):
		if other == self or other.dead:
			continue
		var apart := global_position - other.global_position
		apart.y = 0.0
		var gap: float = float(_stats.radius) + float(other._stats.radius) + 0.25
		var d := apart.length()
		if d < gap and d > 0.001:
			wish += apart / d * (gap - d) * 2.5

	_stagger = maxf(0.0, _stagger - dt)
	var speed: float = float(_stats.speed) * (0.35 if _stagger > 0.0 else 1.0)
	_push = _push.move_toward(Vector3.ZERO, dt * 20.0)
	velocity.x = move_toward(velocity.x, wish.x * speed + _push.x, dt * 30.0)
	velocity.z = move_toward(velocity.z, wish.z * speed + _push.z, dt * 30.0)
	velocity.y = -1.0 if is_on_floor() else velocity.y - GRAVITY * dt
	move_and_slide()

	# Shooters face the trooper; a rusher faces where it runs.
	var face := to_player if (kind != Rig.Kind.RUSHER or dist < 3.0) else Vector3(velocity.x, 0, velocity.z)
	if face.length() > 0.2:
		rig.rotation.y = lerp_angle(rig.rotation.y, atan2(-face.x, -face.z), minf(1.0, dt * 8.0))
	rig.speed = Vector2(velocity.x, velocity.z).length()
	rig.charge = _charge
	if kind != Rig.Kind.RUSHER:
		var up: float = player.aim_point().y - aim_point().y
		rig.aim_pitch = clampf(atan2(up, maxf(dist, 1.0)), -0.5, 0.5)


func _sees_player() -> bool:
	var q := PhysicsRayQueryParameters3D.create(aim_point(), player.aim_point(), WORLD)
	return get_world_3d().direct_space_state.intersect_ray(q).is_empty()


## The direction to walk to reach the trooper, going round cover.
func _towards_player(dt: float) -> Vector3:
	_path_wait -= dt
	if _path_wait <= 0.0:
		_path_wait = 0.45 + randf() * 0.3
		_path = arena.path(global_position, player.global_position)
	# Skip points already reached.
	while _path.size() > 1 and Vector2(_path[0].x - global_position.x, _path[0].z - global_position.z).length() < 0.7:
		_path.remove_at(0)
	var target: Vector3 = _path[0] if _path.size() > 1 else player.global_position
	var dir := target - global_position
	dir.y = 0.0
	return dir.normalized() if dir.length() > 0.05 else Vector3.ZERO


func _shooter(dt: float, dist: float, to_player: Vector3, sees: bool) -> Vector3:
	var want: float = float(_stats.range)
	var wish := Vector3.ZERO
	if not sees or dist > want:
		wish = _towards_player(dt)
	else:
		# In range with a clear shot: sidestep, and back off if too close.
		_strafe_wait -= dt
		if _strafe_wait <= 0.0:
			_strafe_wait = randf_range(1.2, 2.8)
			_strafe = -_strafe if randf() < 0.7 else _strafe
		var side := to_player.normalized().cross(Vector3.UP) * _strafe
		wish = side * 0.55
		if dist < want * 0.5:
			wish -= to_player.normalized() * 0.8
		if not arena.is_open(global_position + wish.normalized() * 1.2):
			_strafe = -_strafe
			wish = Vector3.ZERO

	# A burst that has started carries on.
	if _burst_left > 0:
		_burst_wait -= dt
		if _burst_wait <= 0.0:
			_fire()
			_burst_left -= 1
			_burst_wait = 0.16
		return wish * 0.3

	_attack_wait -= dt
	if sees and dist < want + 8.0 and _attack_wait <= 0.0:
		# The eye glows for a moment before the shot: a warning to move.
		_charge += dt / 0.45
		if _charge >= 1.0:
			_charge = 0.0
			_attack_wait = float(_stats.wait) * randf_range(0.8, 1.25)
			_burst_left = int(_stats.burst)
			_burst_wait = 0.0
		return wish * 0.3
	_charge = maxf(0.0, _charge - dt * 3.0)
	return wish


func _fire() -> void:
	if player.dead:
		return
	var use_second: bool = rig.muzzle_2 != null and _burst_left % 2 == 0
	var from: Vector3 = (rig.muzzle_2 if use_second else rig.muzzle).global_position
	# Aim a little ahead of a moving trooper, and never perfectly.
	var bolt_speed := 24.0
	var lead: Vector3 = player.velocity * (from.distance_to(player.aim_point()) / bolt_speed) * 0.5
	var target: Vector3 = player.aim_point() + lead + Vector3(randf_range(-0.5, 0.5), randf_range(-0.3, 0.3), randf_range(-0.5, 0.5))
	var color := Color("ff5a3c") if kind == Rig.Kind.GRUNT else (Color("ffc400") if kind == Rig.Kind.HEAVY else Color("ff2d6f"))
	fx.fire_bolt(from, (target - from).normalized() * bolt_speed, float(_stats.damage) * (0.75 + 0.25 * power), color)
	fx.muzzle_flash(from, color, 0.2)
	rig.kick(0.8)
	sound.emit("enemy_shot", randf_range(0.85, 1.15), -9.0)


func _rusher(dt: float, dist: float, to_player: Vector3) -> Vector3:
	if _fuse >= 0.0:
		# Close enough: stop, beep faster and faster, then blow up.
		_fuse -= dt
		_beep -= dt
		_charge = 1.0 - _fuse / 0.55
		if _beep <= 0.0:
			_beep = 0.11
			sound.emit("beep", 1.0 + _charge, -8.0)
		if _fuse <= 0.0:
			_blow_up()
		return to_player.normalized() * 0.25
	if dist < 2.4 and not player.dead:
		_fuse = 0.55
		return Vector3.ZERO
	return _towards_player(dt)


func _blow_up() -> void:
	var at := aim_point()
	var d := at.distance_to(player.aim_point())
	if d < 4.2:
		player.take_damage(float(_stats.damage) * (0.75 + 0.25 * power) * (1.0 - 0.5 * d / 4.2), at)
	_die(Vector3.ZERO, true)


## Returns true if this hit destroyed the robot.
func take_damage(amount: float, _at: Vector3, push: Vector3) -> bool:
	if dead:
		return false
	hp -= amount
	rig.flash()
	_stagger = 0.18
	if kind != Rig.Kind.BOSS:
		_push += Vector3(push.x, 0, push.z) * minf(amount / max_hp, 1.0) * 9.0
	if hp <= 0.0:
		_die(push, kind == Rig.Kind.RUSHER)
		return true
	return false


func _die(push: Vector3, blast: bool) -> void:
	if dead:
		return
	dead = true
	remove_from_group("enemies")
	collision_layer = 0
	var at := aim_point()
	if blast or kind == Rig.Kind.BOSS:
		fx.explosion(at, 6.5 if kind == Rig.Kind.BOSS else 3.6)
	else:
		fx.sparks(at, Color("ffd27a"), 18, 7.0, 0.08, 0.5)
		fx.smoke(at, 6, 0.5)
		sound.emit("kill", randf_range(0.85, 1.2), -2.0)
	rig.explode(get_parent(), push)
	destroyed.emit(self)
	queue_free()
