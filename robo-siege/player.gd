extends CharacterBody3D
## The trooper the player controls, and the camera over its shoulder.
##
## The HUD (touch or keyboard and mouse) only sets `move`, `want_fire` and
## calls `add_look`, `reload`, `throw_grenade` and `dash`; everything else
## happens here.

const Rig := preload("res://rig.gd")
const Weapons := preload("res://weapons.gd")

const WORLD := 1
const PLAYER := 2
const ENEMIES := 4

const GRAVITY := 24.0
const PITCH_MIN := -0.75
const PITCH_MAX := 0.9
const CAM_OFFSET := Vector3(0.8, 0.3, 3.4)
const EYE_HEIGHT := 1.55

signal shot_hit(killed: bool, crit: bool)
signal weapon_changed
signal hurt(angle: float)
signal died
signal sound(sound_name: String, pitch: float, volume_db: float)
## The weapon's own sound: sent with its recording's name and `on` true for
## every bullet, and once with `on` false when the burst is over.
signal fire_sound(recording: String, on: bool)
## Footsteps: 0 when still (or in the air, or dashing), 1 at a walk, 2 at a run.
signal steps(pace: int)

# The two weapons the trooper carries, by id, and which one is in hand.
var slots: Array[String] = ["rifle", "ak"]
var slot := 0
var weapon: Dictionary = Weapons.tuned(Weapons.LIST[0])

# Upgrades multiply the weapon's own numbers rather than replacing them, so
# they carry across when the trooper swaps weapons.
var up_damage := 1.0
## Less than 1 steadies the weapon: less spread, less kick.
var up_steady := 1.0
var up_mag := 0

# Stats; the weapon and the upgrades together set the first five.
var max_hp := 100.0
var damage := 22.0
var fire_interval := 0.11
var mag_size := 30
var spread := 0.005
var move_speed := 6.0
var grenade_cooldown := 7.0
var grenade_damage := 170.0
var dash_cooldown := 2.5
var regen := 7.0

var hp := 100.0
var ammo := 30
var grenade_wait := 0.0
var dash_wait := 0.0
var dead := false
var kills := 0

# Set by the HUD.
var move := Vector2.ZERO       # x: right, y: forward
var want_fire := false
var auto_fire := false         # fire by itself when the sights are on a robot
var assist := 0.5              # 0..1, how much the aim is pulled to a robot
## Plays the game by itself; used by the smoke test.
var autopilot := false
## Which outfit to wear: an index into Rig.LOOKS.
var look := 0

var cam_yaw := 0.0
var cam_pitch := -0.15
var camera: Camera3D
var rig: Node3D
var fx: Node3D
## 0..1, how wide the crosshair is right now.
var bloom := 0.0
## True while the sights rest on a robot.
var on_target := false

var _cooldown := 0.0
var _kick := 0.0
var _dash_time := 0.0
var _dash_dir := Vector3.ZERO
var _safe := 0.0
var _regen_wait := 0.0
var _empty_click := 0.0
var _pilot_time := 0.0
## What the footsteps were last told: 0 still, 1 walking, 2 running.
var _pace := -1
## A burst is being fired, and its sound is playing.
var _burst := false
var _time_since_cry := 9.0


func _ready() -> void:
	collision_layer = PLAYER
	collision_mask = WORLD
	var shape := CapsuleShape3D.new()
	shape.radius = 0.38
	shape.height = 1.8
	var cs := CollisionShape3D.new()
	cs.shape = shape
	cs.position = Vector3(0, 0.9, 0)
	add_child(cs)

	rig = Rig.new()
	add_child(rig)
	rig.build(Rig.Kind.TROOPER, look, Weapons.by_id(slots[slot]))

	camera = Camera3D.new()
	camera.fov = 68.0
	camera.near = 0.15
	camera.far = 220.0
	camera.top_level = true
	add_child(camera)
	camera.make_current()
	hp = max_hp
	equip(slot, true)
	_place_camera(1.0)


## Puts the weapon in slot `index` into the trooper's hands.
func equip(index: int, instant := false) -> void:
	slot = posmod(index, slots.size())
	_end_burst()
	weapon = Weapons.tuned(Weapons.by_id(slots[slot]))
	damage = float(weapon.damage) * up_damage
	# Never changed by an upgrade: the weapon fires in time with its recording.
	fire_interval = float(weapon.interval)
	mag_size = int(weapon.mag) + up_mag
	spread = float(weapon.spread) * up_steady
	ammo = mag_size
	bloom = 0.0
	if rig:
		rig.set_weapon(weapon)
	if not instant:
		_cooldown = maxf(_cooldown, 0.35)
		sound.emit("swap", 1.0, -6.0)
	weapon_changed.emit()


## The other weapon.
func swap_weapon() -> void:
	if dead or slots.size() < 2:
		return
	equip(slot + 1)


## Turn the view; `delta` is in radians.
func add_look(delta: Vector2) -> void:
	if dead:
		return
	cam_yaw = wrapf(cam_yaw - delta.x, -PI, PI)
	cam_pitch = clampf(cam_pitch - delta.y, PITCH_MIN, PITCH_MAX)


func view_basis() -> Basis:
	return Basis(Vector3.UP, cam_yaw) * Basis(Vector3.RIGHT, cam_pitch + _kick)


func aim_point() -> Vector3:
	return global_position + Vector3(0, 1.1, 0)


# ---------------------------------------------------------------- every frame

func _physics_process(dt: float) -> void:
	if dead:
		velocity.x = move_toward(velocity.x, 0.0, dt * 20.0)
		velocity.z = move_toward(velocity.z, 0.0, dt * 20.0)
		velocity.y -= GRAVITY * dt
		move_and_slide()
		return
	if autopilot:
		_pilot(dt)

	_cooldown -= dt
	_time_since_cry += dt
	_safe -= dt
	_empty_click -= dt
	grenade_wait = maxf(0.0, grenade_wait - dt)
	dash_wait = maxf(0.0, dash_wait - dt)
	bloom = maxf(0.0, bloom - dt * 1.8)
	_kick = move_toward(_kick, 0.0, dt * 0.5)

	# Moving.
	var yaw := Basis(Vector3.UP, cam_yaw)
	var wish := yaw * Vector3(move.x, 0, -move.y)
	if wish.length() > 1.0:
		wish = wish.normalized()
	if _dash_time > 0.0:
		_dash_time -= dt
		velocity.x = _dash_dir.x * 17.0
		velocity.z = _dash_dir.z * 17.0
	else:
		velocity.x = move_toward(velocity.x, wish.x * move_speed, dt * 45.0)
		velocity.z = move_toward(velocity.z, wish.z * move_speed, dt * 45.0)
	velocity.y = -1.0 if is_on_floor() else velocity.y - GRAVITY * dt
	move_and_slide()

	# Healing after a few quiet seconds.
	_regen_wait -= dt
	if _regen_wait <= 0.0 and hp < max_hp:
		hp = minf(max_hp, hp + regen * dt)

	# Shooting.
	var aim := _aim()
	on_target = aim.enemy != null
	var trigger := want_fire or (auto_fire and on_target)
	if trigger and _cooldown <= 0.0:
		if ammo > 0:
			# Carry what is left of this tick over, so the shots keep exact
			# time with the recording however the ticks fall.
			_cooldown = maxf(_cooldown, -dt)
			_shoot(aim)
		else:
			reload()
	# The burst is over when the next shot is nearly due and is not coming.
	if _burst and _cooldown <= Weapons.RELEASE_AHEAD and not (trigger and ammo > 0):
		_end_burst()

	# The body follows the view and the legs follow the ground speed.
	rig.rotation.y = cam_yaw
	rig.aim_pitch = cam_pitch
	rig.speed = Vector2(velocity.x, velocity.z).length()
	rig.lean = move.x
	rig.backwards = move.y < -0.35

	# Footsteps while the feet are on the ground and moving.
	var pace := 0
	if is_on_floor() and _dash_time <= 0.0 and rig.speed > 0.6:
		pace = 2 if rig.speed > 3.6 else 1
	_tell_steps(pace)


func _process(dt: float) -> void:
	_place_camera(minf(1.0, dt * 18.0))


func _place_camera(blend: float) -> void:
	var basis := view_basis()
	var pivot := global_position + Vector3(0, EYE_HEIGHT, 0)
	var want := pivot + basis * CAM_OFFSET
	# Pull the camera in when a wall is in the way.
	if is_inside_tree():
		var q := PhysicsRayQueryParameters3D.create(pivot, want, WORLD)
		var hit := get_world_3d().direct_space_state.intersect_ray(q)
		if not hit.is_empty():
			want = pivot + (hit.position - pivot) * 0.86
	var pos := camera.global_position.lerp(want, blend) if blend < 1.0 else want
	var shake: float = fx.shake if fx else 0.0
	if shake > 0.0:
		pos += Vector3(randf_range(-1, 1), randf_range(-1, 1), randf_range(-1, 1)) * shake * 0.14
	camera.global_transform = Transform3D(basis, pos)
	# Backed against a wall the camera ends up inside the trooper: hide them.
	rig.visible = pos.distance_to(pivot) > 1.1


# ---------------------------------------------------------------- shooting

## What the sights are on: a point in the world and the robot there, if any.
func _aim() -> Dictionary:
	var basis := view_basis()
	var origin := camera.global_position
	var dir := -basis.z
	# Start in front of the trooper so nothing behind them can be hit.
	var start := origin + dir * (CAM_OFFSET.z + 0.2)
	var space := get_world_3d().direct_space_state

	# Aim assist: pull towards the robot nearest the centre of the screen.
	if assist > 0.0:
		var best: Node3D = null
		var best_angle := deg_to_rad(1.5 + 4.5 * assist)
		for e: Node3D in get_tree().get_nodes_in_group("enemies"):
			if e.dead:
				continue
			var to: Vector3 = e.aim_point() - origin
			if to.length() > 70.0:
				continue
			var angle := dir.angle_to(to)
			if angle < best_angle:
				var blocked := space.intersect_ray(PhysicsRayQueryParameters3D.create(start, e.aim_point(), WORLD))
				if blocked.is_empty():
					best_angle = angle
					best = e
		if best:
			dir = dir.slerp((best.aim_point() - origin).normalized(), 0.35 + 0.5 * assist)

	var far := origin + dir * 140.0
	var hit := space.intersect_ray(PhysicsRayQueryParameters3D.create(start, far, WORLD | ENEMIES))
	var enemy: Node3D = null
	var point := far
	if not hit.is_empty():
		point = hit.position
		if hit.collider.is_in_group("enemies"):
			enemy = hit.collider
	return {"point": point, "enemy": enemy, "dir": dir}


func _tell_steps(pace: int) -> void:
	if pace != _pace:
		_pace = pace
		steps.emit(pace)


## Makes the trooper say again what it is doing, after the sound has been
## silenced from outside (a pause, say).
func resend_sounds() -> void:
	_pace = -1


## Tells the sound that the firing has stopped.
func _end_burst() -> void:
	if _burst:
		_burst = false
		fire_sound.emit(str(weapon.sound), false)


func _shoot(aim: Dictionary) -> void:
	_cooldown += fire_interval
	ammo -= 1
	var from: Vector3 = rig.muzzle.global_position
	_fire_one(from, aim)
	fx.muzzle_flash(from, Color("ffd27a"), 0.22)
	rig.kick()
	_kick = minf(_kick + float(weapon.kick) * up_steady, 0.09)
	bloom = minf(1.0, bloom + 0.16)
	fx.shake = maxf(fx.shake, 0.1 + 0.03 * float(weapon.kick) * 10.0)
	_burst = true
	fire_sound.emit(str(weapon.sound), true)
	if ammo <= 0:
		reload()


## One bullet: where it goes, what it hits, and what that costs.
func _fire_one(from: Vector3, aim: Dictionary) -> void:
	var space := get_world_3d().direct_space_state
	# Each shot strays a little; holding the trigger makes it worse.
	var stray := spread + bloom * 0.03
	var target: Vector3 = aim.point
	var dist := from.distance_to(target)
	target += Vector3(randf_range(-1, 1), randf_range(-1, 1), randf_range(-1, 1)) * stray * dist

	# The bullet really leaves the gun, so cover next to the trooper stops it.
	var through := from + (target - from).normalized() * (dist + 2.0)
	var hit := space.intersect_ray(PhysicsRayQueryParameters3D.create(from, through, WORLD | ENEMIES))
	var end := through
	if not hit.is_empty():
		end = hit.position
		var push: Vector3 = (end - from).normalized()
		if hit.collider.is_in_group("enemies"):
			var enemy: Node3D = hit.collider
			var crit: bool = end.y > enemy.global_position.y + enemy.rig.height * 0.8
			var amount := damage * (2.0 if crit else 1.0)
			var killed: bool = enemy.take_damage(amount, end, push)
			fx.sparks(end, Color("ffd27a"), 7, 5.0)
			fx.number(end, str(int(amount)), Color("ffd84a") if crit else Color.WHITE, crit)
			if killed:
				kills += 1
			shot_hit.emit(killed, crit)
			sound.emit("hit", randf_range(0.9, 1.2), -7.0)
		else:
			fx.sparks(end, Color("d9dde6"), 5, 3.5, 0.05)
	fx.tracer(from, end)


## A fresh magazine, at once: there is no waiting.
func reload() -> void:
	if dead or ammo >= mag_size:
		return
	ammo = mag_size
	sound.emit(weapon.reload_sound, 1.0, -3.0)


func throw_grenade() -> void:
	if dead or grenade_wait > 0.0:
		return
	grenade_wait = grenade_cooldown
	var basis := view_basis()
	var from := global_position + Vector3(0, 1.5, 0) + basis * Vector3(0.4, 0, -0.6)
	fx.throw_grenade(from, -basis.z * 15.0 + Vector3(0, 5.0, 0), 1.3, _grenade_explode)
	sound.emit("throw", 1.0, 0.0)


func _grenade_explode(at: Vector3) -> void:
	var radius := 6.0
	fx.explosion(at, radius)
	var any := false
	var killed_any := false
	for e: Node3D in get_tree().get_nodes_in_group("enemies"):
		if e.dead:
			continue
		var d: float = e.aim_point().distance_to(at)
		if d < radius:
			var amount := grenade_damage * (1.0 - 0.7 * d / radius)
			any = true
			fx.number(e.aim_point(), str(int(amount)), Color("ff8a1f"), true)
			if e.take_damage(amount, e.aim_point(), (e.aim_point() - at).normalized()):
				kills += 1
				killed_any = true
	if any:
		shot_hit.emit(killed_any, false)


## A short burst of speed during which nothing can hurt the trooper.
func dash() -> void:
	if dead or dash_wait > 0.0:
		return
	dash_wait = dash_cooldown
	var yaw := Basis(Vector3.UP, cam_yaw)
	var dir := yaw * Vector3(move.x, 0, -move.y)
	_dash_dir = dir.normalized() if dir.length() > 0.2 else -yaw.z
	_dash_time = 0.2
	_safe = 0.35
	fx.smoke(global_position + Vector3(0, 0.3, 0), 6, 0.5)
	sound.emit("dash", randf_range(0.5, 0.6), 0.0)


# ---------------------------------------------------------------- getting hurt

func take_damage(amount: float, from: Vector3) -> void:
	if dead or _safe > 0.0:
		return
	hp -= amount
	_regen_wait = 4.0
	rig.flash()
	fx.shake = maxf(fx.shake, 0.5)
	# Which way the hit came from, relative to where the camera looks.
	var to := from - global_position
	hurt.emit(wrapf(atan2(-to.x, -to.z) - cam_yaw, -PI, PI))
	# One cry per burst of hits, not one per bolt.
	if _time_since_cry > 0.6:
		_time_since_cry = 0.0
		sound.emit("hurt", randf_range(0.92, 1.05), -3.0)
	if hp <= 0.0:
		hp = 0.0
		dead = true
		_end_burst()
		_tell_steps(0)
		want_fire = false
		var tw := create_tween()
		tw.tween_property(rig, "rotation:x", -PI * 0.5, 0.5).set_trans(Tween.TRANS_BOUNCE).set_ease(Tween.EASE_OUT)
		died.emit()


func heal(amount: float) -> void:
	hp = minf(max_hp, hp + amount)


# ---------------------------------------------------------------- autopilot

## A simple bot: face the nearest robot, shoot it, keep moving.
func _pilot(dt: float) -> void:
	_pilot_time += dt
	var nearest: Node3D = null
	var best := 1e9
	for e: Node3D in get_tree().get_nodes_in_group("enemies"):
		if e.dead:
			continue
		var d := e.global_position.distance_to(global_position)
		if d < best:
			best = d
			nearest = e
	want_fire = false
	if nearest == null:
		move = Vector2.ZERO
		return
	var to: Vector3 = nearest.aim_point() - camera.global_position
	var want_yaw := atan2(-to.x, -to.z)
	var want_pitch := atan2(to.y, Vector2(to.x, to.z).length())
	cam_yaw = lerp_angle(cam_yaw, want_yaw, minf(1.0, dt * 10.0))
	cam_pitch = clampf(lerpf(cam_pitch, want_pitch, minf(1.0, dt * 10.0)), PITCH_MIN, PITCH_MAX)
	want_fire = true
	move = Vector2(1.0 if fmod(_pilot_time, 4.0) < 2.0 else -1.0, -0.8 if best < 9.0 else 0.2)
	if best < 5.0:
		dash()
	if best < 14.0:
		throw_grenade()
