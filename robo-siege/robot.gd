extends Node3D
## One enemy machine. They are the same soldier the player is, in another
## colour, at another size, carrying another weapon from the same armory, so
## the whole fight reads as one army against one trooper.
##
## It offers the handful of calls enemy.gd needs: set `speed`, `aim_pitch`
## and `charge` each frame, call `kick()` when it fires, `flash()` when it is
## hit and `explode()` when it is destroyed.

const Rig := preload("res://rig.gd")

const Weapons := preload("res://weapons.gd")

## Each kind: how tall it stands, its colours, the weapon from weapons.gd it
## carries, what its fire sounds like, and whether it wears shoulder armour.
const KINDS := {
	Rig.Kind.GRUNT: {"name": "Scout", "height": 1.85, "look": "scout", "weapon": "carbine", "sound": "enemy_scout_shot", "plates": false},
	Rig.Kind.RUSHER: {"name": "Rusher", "height": 1.7, "look": "rusher", "weapon": "rifle", "sound": "enemy_scout_shot", "plates": false},
	Rig.Kind.HEAVY: {"name": "Gunner", "height": 2.3, "look": "gunner", "weapon": "ak", "sound": "enemy_gunner_shot", "plates": true},
	Rig.Kind.BOSS: {"name": "War Machine", "height": 3.4, "look": "boss", "weapon": "heavy", "sound": "enemy_boss_shot", "plates": true},
}

# Set by the owner every frame.
var speed := 0.0:
	set(value):
		speed = value
		if _body:
			_body.speed = value
var aim_pitch := 0.0:
	set(value):
		aim_pitch = value
		if _body:
			_body.aim_pitch = value
var lean := 0.0
## 0..1 while the machine winds up a shot; its uniform flares.
var charge := 0.0

var height := 1.9
var muzzle: Node3D

var _body: Node3D
var _info: Dictionary = {}
var _glow: StandardMaterial3D
var _dying := false
var _time := 0.0


func build(kind: Rig.Kind) -> void:
	var info: Dictionary = KINDS[kind]
	_info = info
	height = float(info.height)

	_body = Rig.new()
	add_child(_body)
	_body.build(Rig.Kind.TROOPER, 0, Weapons.by_id(info.weapon))
	# The soldier is built about 1.85 m tall; scale it to this kind's size.
	_body.scale = Vector3.ONE * (height / 1.85)
	muzzle = _body.muzzle

	# Repaint the uniform. The weapon keeps its own colours.
	var tint: Color = Rig.ENEMY_LOOKS[info.look]
	for mi in _body.meshes:
		if _body.weapon_meshes.has(mi):
			continue
		for surface in mi.mesh.get_surface_count():
			var base: StandardMaterial3D = mi.get_active_material(surface)
			if base == null:
				continue
			var m: StandardMaterial3D = base.duplicate()
			m.albedo_color = tint
			m.emission_enabled = true
			m.emission = Color(tint).clamp()
			m.emission_energy_multiplier = 0.0
			mi.set_surface_override_material(surface, m)
			_glow = m

	if info.plates:
		_add_plates(tint)


## The name of the sound this kind's fire makes. The robots' weapons are
## heard from across the outpost, so each kind has a voice of its own.
static func shot_sound(kind: Rig.Kind) -> String:
	return KINDS[kind].sound


## Heavier machines wear shoulder armour, so they read as tougher before
## they have fired a shot.
func _add_plates(tint: Color) -> void:
	var skeleton: Skeleton3D = _body.find_child("Skeleton3D", true, false)
	if skeleton == null:
		return
	var steel := Rig.mat(Color(tint).clamp().darkened(0.3), 0.0, 0.7, 0.45)
	for side: String in ["Left", "Right"]:
		var mount := BoneAttachment3D.new()
		mount.bone_name = "mixamorig_%sArm" % side
		skeleton.add_child(mount)
		var plate := MeshInstance3D.new()
		# The soldier's skeleton is in centimetres, so the pauldron is too.
		var shell := SphereMesh.new()
		shell.radius = 11.0
		shell.height = 15.0
		shell.radial_segments = 10
		shell.rings = 5
		plate.mesh = shell
		plate.material_override = steel
		var out := 1.0 if side == "Right" else -1.0
		plate.position = Vector3(5.0 * out, 1.0, 0.0)
		plate.scale = Vector3(1.0, 0.75, 1.1)
		plate.rotation = Vector3(0, 0, -0.35 * out)
		mount.add_child(plate)


func _process(dt: float) -> void:
	_time += dt
	if _body:
		_body.lean = lean
	if _glow and not _dying:
		# The uniform's lights flare as the machine winds up a shot.
		_glow.emission_energy_multiplier = charge * 1.2 + 0.02


## The machine jolts as it fires.
func kick(amount := 1.0) -> void:
	if _dying or _body == null:
		return
	_body.kick(amount)


## Everything flashes white for a moment; call when hit.
func flash() -> void:
	if _body:
		_body.flash()


## The machine drops where it stands, then sinks into the deck and is gone.
func explode(world: Node3D, push: Vector3) -> void:
	if _dying:
		return
	_dying = true
	var at := global_transform
	get_parent().remove_child(self)
	world.add_child(self)
	global_transform = at
	if _body:
		_body.speed = 0.0
	var shove := Vector3(push.x, 0, push.z).limit_length(1.0) * 0.5
	var tw := create_tween()
	tw.set_parallel(true)
	tw.tween_property(self, "position", position + shove, 0.3)
	if _body:
		tw.tween_property(_body, "rotation:x", -PI * 0.5, 0.45).set_trans(Tween.TRANS_BOUNCE).set_ease(Tween.EASE_OUT)
	tw.set_parallel(false)
	tw.tween_interval(1.4)
	tw.tween_property(self, "position:y", position.y - height, 0.8).set_ease(Tween.EASE_IN)
	tw.tween_callback(queue_free)
