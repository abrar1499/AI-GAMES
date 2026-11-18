extends Node3D
## The soldier everyone in the game is: the player's trooper, and (in other
## colours and sizes, see robot.gd) every robot. A rigged model with its own
## walk and run, standing in a shooting stance with a rifle in its hands.
## It faces -Z.
##
## The owner sets `speed`, `aim_pitch` and `lean` every frame and calls
## `kick()` when firing and `flash()` when hit.

enum Kind { TROOPER, GRUNT, RUSHER, HEAVY, BOSS }

const SOLDIER := preload("res://assets/soldier.glb")
## The soldier's uniform: its colours, its bumps, and what it is made of
## (shadow in the creases, roughness, bare metal). tools/build_hero_textures.py
## makes them from the two pictures that come inside the model.
const UNIFORM := preload("res://assets/hero/albedo.jpg")
const UNIFORM_BUMPS := preload("res://assets/hero/normal.jpg")
const UNIFORM_MADE_OF := preload("res://assets/hero/orm.jpg")
const Weapons := preload("res://weapons.gd")

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

## A foot has just come down. `running` is true when it landed at a run.
## Whoever wants footsteps listens to this, so the sound always matches the
## legs, at any pace, forwards or backwards.
signal footstep(running: bool)

## A foot is on the ground when its ankle is at this height (metres, in the
## character's own unscaled space) and in the air when above FOOT_UP.
const FOOT_DOWN := 0.142
const FOOT_UP := 0.175

## How each hand sits on the weapon. All in the weapon's own space: x to
## the trooper's right, y up, -z the way the barrel points, with the
## trigger hand's grip at the origin.
##
##   fingers  which way the hand points, wrist to knuckles
##   palm     which way the palm faces
##   wrist    where the wrist is, measured from the point the hand holds.
##            This soldier's gloves are long in the palm (15 cm from wrist
##            to knuckle) with short fingers, so the wrist is well back.
##   elbow    which way the elbow points
##   curl     how far each finger's three joints close, in radians
##   thumb    how far the thumb swings round towards the palm (less than
##            nothing lays it out flat instead), then bends at two joints
##
## The trigger hand is wrapped round the grip from the right, its first
## finger laid along the trigger. The other hand is under the barrel, palm
## up, its fingers coming up the far side.
const TRIGGER_HAND := {
	"fingers": Vector3(0.0, -0.22, -1.0), "palm": Vector3(-1.0, 0.0, 0.0),
	"wrist": Vector3(0.04, 0.02, 0.112), "elbow": Vector3(0.75, -0.75, 0.45),
	"curl": {"Index": [0.3, 0.55, 0.3], "Middle": [1.2, 1.3, 0.7], "Ring": [1.25, 1.3, 0.7], "Pinky": [1.3, 1.3, 0.7]},
	"thumb": [0.9, 0.45, 0.35],
}
const SUPPORT_HAND := {
	"fingers": Vector3(0.8, 0.22, -0.55), "palm": Vector3(0.0, 1.0, 0.0),
	"wrist": Vector3(-0.09, -0.06, 0.04), "elbow": Vector3(-0.5, -0.9, 0.05),
	"curl": {"Index": [1.25, 0.8, 0.45], "Middle": [1.3, 0.8, 0.45], "Ring": [1.3, 0.85, 0.45], "Pinky": [1.35, 0.85, 0.45]},
	"thumb": [-0.4, 0.2, 0.15],
}

## Metres per second over the ground; drives the legs.
var speed := 0.0
## Looking up (+) or down (-), in radians; tilts the weapon.
var aim_pitch := 0.0
## Sideways movement from -1 to 1; the body leans into it.
var lean := 0.0
## Walking backwards: the soldier's steps play in reverse.
var backwards := false
## The weapon this character carries: an entry from weapons.gd.
var weapon: Dictionary = Weapons.LIST[0]

var body: Node3D
var gun: Node3D
var muzzle: Node3D
var meshes: Array[MeshInstance3D] = []
## The meshes among those that belong to the weapon, not the body.
var weapon_meshes: Array[MeshInstance3D] = []
var height := 1.8

var _recoil := 0.0
var _flash := 0.0
var _time := 0.0
var _model: Node3D
var _skeleton: Skeleton3D
var _anim: AnimationPlayer
var _arms: Array = []
var _chest := -1
var _feet: Array = []
## Where the trigger hand is, measured from the chest, and where the other
## hand is on the weapon, measured from the trigger hand.
var _hold := Vector3(0.1, -0.1, -0.2)
var _fore := Vector3.ZERO
var _twist := 0.0
var _up_in_skeleton := Vector3.UP
var _foot_up := [false, false]
var _step_wait := 0.0

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


func _pivot(parent: Node3D, pos: Vector3) -> Node3D:
	var n := Node3D.new()
	n.position = pos
	parent.add_child(n)
	return n


## Builds the soldier. `carrying` is an entry from weapons.gd; left out, it
## is the first one. (Every kind is the same soldier; robot.gd makes the
## robots differ.)
func build(_kind: Kind, look := 0, carrying: Dictionary = {}) -> void:
	if not carrying.is_empty():
		weapon = carrying
	body = _pivot(self, Vector3.ZERO)
	_build_soldier(look)


## Sets up the model: its uniform, its animations, its arms and hands, and
## the weapon. `look` picks one of LOOKS (a colour wash over the uniform).
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
			# A private copy of the material for each character, so each can
			# be its own colour.
			for surface in mi.mesh.get_surface_count():
				var m: StandardMaterial3D = mi.get_active_material(surface).duplicate()
				_dress(m, str(mi.name).to_lower().contains("visor"))
				m.albedo_color = l.tint
				mi.set_surface_override_material(surface, m)

	# The arms hold the weapon and the chest and head are set by the
	# shooting stance, so the walk and run must not move any of them. The
	# hips and the lowest part of the spine keep their sway.
	var held := ["Arm", "Hand", "Shoulder", "Spine1", "Spine2", "Neck", "Head"]
	for anim_name in _anim.get_animation_list():
		var anim := _anim.get_animation(anim_name)
		anim.loop_mode = Animation.LOOP_LINEAR
		for t in range(anim.get_track_count() - 1, -1, -1):
			var bone := str(anim.track_get_path(t)).get_slice(":", 1)
			for part: String in held:
				if bone.contains(part):
					anim.remove_track(t)
					break
	_anim.play("Idle")

	# In the rest pose the arms are straight out sideways with the palms
	# down. From that, find in each hand's own space which way its fingers
	# run and which way its palm faces, so the hand can be turned to a grip.
	_up_in_skeleton = (_skeleton_to_rig().basis.inverse() * Vector3.UP).normalized()
	for side: String in ["Right", "Left"]:
		var arm := _skeleton.find_bone("mixamorig_%sArm" % side)
		var fore := _skeleton.find_bone("mixamorig_%sForeArm" % side)
		var hand := _skeleton.find_bone("mixamorig_%sHand" % side)
		var knuckle := _skeleton.find_bone("mixamorig_%sHandMiddle1" % side)
		var hand_rest := _skeleton.get_bone_global_rest(hand)
		var fingers_rest := (_skeleton.get_bone_global_rest(knuckle).origin - hand_rest.origin).normalized()
		var palm_rest := -_up_in_skeleton
		palm_rest = (palm_rest - fingers_rest * palm_rest.dot(fingers_rest)).normalized()
		var to_hand := hand_rest.basis.orthonormalized().inverse()
		_arms.append({
			"side": side, "arm": arm, "fore": fore, "hand": hand,
			"upper_len": _skeleton.get_bone_rest(fore).origin.length(),
			"fore_len": _skeleton.get_bone_rest(hand).origin.length(),
			"fingers": to_hand * fingers_rest, "palm": to_hand * palm_rest,
			"fingers_rest": fingers_rest, "palm_rest": palm_rest,
		})
	_chest = _skeleton.find_bone("mixamorig_Spine2")
	_feet = [_skeleton.find_bone("mixamorig_LeftFoot"), _skeleton.find_bone("mixamorig_RightFoot")]
	_close_hand(_arms[0], TRIGGER_HAND)
	_close_hand(_arms[1], SUPPORT_HAND)

	# The weapon sits on a pivot at the trigger hand.
	gun = _pivot(body, Vector3(0.1, 1.2, -0.2))
	muzzle = _pivot(gun, Vector3.ZERO)
	set_weapon(weapon)
	height = 1.85


## Puts the uniform on a material: painted armour that catches the light,
## cloth and rubber that do not, bare metal where the paint has chipped,
## and shadow in the creases. The visor is dark glass.
static func _dress(m: StandardMaterial3D, visor: bool) -> void:
	m.albedo_texture = UNIFORM
	m.normal_enabled = true
	m.normal_texture = UNIFORM_BUMPS
	m.normal_scale = 1.0
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS_ANISOTROPIC
	if visor:
		m.metallic = 0.9
		m.roughness = 0.14
		m.metallic_specular = 0.8
		return
	m.ao_enabled = true
	m.ao_texture = UNIFORM_MADE_OF
	m.ao_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_RED
	m.ao_light_affect = 0.4
	m.roughness = 1.0
	m.roughness_texture = UNIFORM_MADE_OF
	m.roughness_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_GREEN
	m.metallic = 1.0
	m.metallic_texture = UNIFORM_MADE_OF
	m.metallic_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_BLUE
	m.metallic_specular = 0.55
	# A thin line of light round the edges, to lift the figure off the ground.
	m.rim_enabled = true
	m.rim = 0.16
	m.rim_tint = 0.6


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
	_watch_feet(dt, want == "Run")
	_recoil = move_toward(_recoil, 0.0, dt * 9.0)
	body.rotation.z = lerpf(body.rotation.z, -lean * 0.08, minf(1.0, dt * 8.0))

	# The weapon follows the chest, tilts with the aim and kicks back on a shot.
	var to_rig := _skeleton_to_rig()
	var chest: Vector3 = to_rig * _skeleton.get_bone_global_pose(_chest).origin
	chest = body.transform.affine_inverse() * chest
	gun.rotation.x = aim_pitch * 0.85 + _recoil * 0.05
	gun.position = Vector3(_hold.x, chest.y + _hold.y, chest.z + _hold.z + _recoil * 0.05)

	# Hands: the right round the grip, the left under the barrel. Each
	# wrist is put where the palm will be on its point, the arm is bent to
	# reach it, and then the hand is turned to face the weapon.
	var gun_in_skeleton := _skeleton_to_rig().affine_inverse() * body.transform * gun.transform
	var holds := [[TRIGGER_HAND, Vector3.ZERO], [SUPPORT_HAND, _fore]]
	for i in 2:
		var hand: Dictionary = holds[i][0]
		var wrist: Vector3 = gun_in_skeleton * ((holds[i][1] as Vector3) + (hand.wrist as Vector3))
		var elbow: Vector3 = (_skeleton_to_rig().basis.inverse() * (hand.elbow as Vector3)).normalized()
		_reach(_arms[i], wrist, elbow)
		_turn_hand(_arms[i], gun_in_skeleton.basis * (hand.fingers as Vector3), gun_in_skeleton.basis * (hand.palm as Vector3))


## Notices each foot coming down and reports it. A foot counts as lifted
## once its ankle is well clear of the ground, and as landed when it is
## back down, so standing still or shuffling makes no sound.
func _watch_feet(dt: float, running: bool) -> void:
	_step_wait = maxf(0.0, _step_wait - dt)
	if footstep.get_connections().is_empty():
		return
	var to_rig := _skeleton_to_rig()
	for i in _feet.size():
		if int(_feet[i]) < 0:
			continue
		var ankle: float = (to_rig * _skeleton.get_bone_global_pose(_feet[i]).origin).y
		if ankle > FOOT_UP:
			_foot_up[i] = true
		elif ankle < FOOT_DOWN and _foot_up[i]:
			_foot_up[i] = false
			if _step_wait <= 0.0:
				_step_wait = 0.13
				footstep.emit(running)


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


## Turns a hand so its fingers run along `fingers` and its palm faces
## `palm` (both in the skeleton's space).
func _turn_hand(arm: Dictionary, fingers: Vector3, palm: Vector3) -> void:
	var f := fingers.normalized()
	var n := (palm - f * palm.dot(f)).normalized()
	var want := Basis(f, n, f.cross(n))
	var f_own: Vector3 = arm.fingers
	var n_own: Vector3 = arm.palm
	var have := Basis(f_own, n_own, f_own.cross(n_own))
	# The turn that takes the hand's own fingers and palm to the wanted ones.
	var turned := want * have.inverse()
	var parent := _skeleton.get_bone_global_pose(_skeleton.get_bone_parent(arm.hand)).basis.orthonormalized()
	_skeleton.set_bone_pose_rotation(arm.hand, (parent.inverse() * turned).get_rotation_quaternion())


## Closes a hand's fingers by the amounts in `grip.curl`, and brings the
## thumb round to meet them. Done once; the fingers then stay as they are.
func _close_hand(arm: Dictionary, grip: Dictionary) -> void:
	var fingers_rest: Vector3 = arm.fingers_rest
	var palm_rest: Vector3 = arm.palm_rest
	# A finger closes by turning about the line across the knuckles.
	var across := fingers_rest.cross(palm_rest).normalized()
	for finger: String in grip.curl:
		var joints: Array = grip.curl[finger]
		for joint in joints.size():
			_turn_joint("mixamorig_%sHand%s%d" % [arm.side, finger, joint + 1], across, float(joints[joint]))
	# The thumb lies out to the side of the hand at rest. It swings round
	# towards the palm about the line of the fingers, then bends at its two
	# further joints, to close on the weapon from the other side.
	var first := _skeleton.find_bone("mixamorig_%sHandThumb1" % arm.side)
	var second := _skeleton.find_bone("mixamorig_%sHandThumb2" % arm.side)
	if first < 0 or second < 0:
		return
	var thumb_rest := (_skeleton.get_bone_global_rest(second).origin - _skeleton.get_bone_global_rest(first).origin).normalized()
	var thumb: Array = grip.thumb
	# Which way round "towards the palm" is depends on the hand.
	var side_of_hand := fingers_rest.cross(palm_rest)
	var swing := -signf(thumb_rest.dot(side_of_hand))
	_turn_joint("mixamorig_%sHandThumb1" % arm.side, fingers_rest, swing * float(thumb[0]))
	var bend := thumb_rest.cross(palm_rest).normalized()
	_turn_joint("mixamorig_%sHandThumb2" % arm.side, bend, float(thumb[1]))
	_turn_joint("mixamorig_%sHandThumb3" % arm.side, bend, float(thumb[2]))


## Turns one bone from its rest by `angle` about `axis`, an axis given in
## the skeleton's space as it is at rest.
func _turn_joint(bone_name: String, axis: Vector3, angle: float) -> void:
	var bone := _skeleton.find_bone(bone_name)
	if bone < 0:
		return
	var rest_global := _skeleton.get_bone_global_rest(bone).basis.orthonormalized()
	var own_axis := (rest_global.inverse() * axis).normalized()
	var rest := _skeleton.get_bone_rest(bone).basis.get_rotation_quaternion()
	_skeleton.set_bone_pose_rotation(bone, rest * Quaternion(own_axis, angle))


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
	if _model == null:
		return
	_animate_soldier(dt)
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


## Puts a weapon (an entry from weapons.gd) in the character's hands.
func set_weapon(carrying: Dictionary) -> void:
	weapon = carrying
	if gun == null:
		return
	for child in gun.get_children():
		if child == muzzle:
			continue
		gun.remove_child(child)
		child.queue_free()
	# Forget the old weapon's meshes. (This must work before the character
	# is in the scene, which is when a robot is given its weapon.)
	var old := weapon_meshes
	meshes = meshes.filter(func(m): return not old.has(m))
	var held := make_weapon(weapon)
	gun.add_child(held)
	weapon_meshes = _find_meshes(held)
	meshes.append_array(weapon_meshes)
	muzzle.position = held.get_meta("muzzle")

	# The butt against the right shoulder, and the body turned a little so
	# the left hand can reach along the barrel.
	var butt: float = held.get_meta("butt")
	_hold = Vector3(0.10, -0.085, -clampf(butt - 0.13, 0.08, 0.26))
	_fore = held.get_meta("fore")
	# Short arms: the left hand never goes further than it can reach.
	_fore.z = maxf(_fore.z, -0.31)
	_set_stance(-32.0)


## Turns the chest `degrees` about the vertical (negative brings the left
## shoulder forward) and turns the head back so it still looks ahead.
func _set_stance(degrees: float) -> void:
	if _skeleton == null:
		return
	_twist = degrees
	_up_in_skeleton = (_skeleton_to_rig().basis.inverse() * Vector3.UP).normalized()
	var share := {"Spine1": 0.5, "Spine2": 0.5, "Neck": -0.5, "Head": -0.5}
	for part: String in share:
		var bone := _skeleton.find_bone("mixamorig_%s" % part)
		if bone < 0:
			continue
		var rest_global := _skeleton.get_bone_global_rest(bone).basis.orthonormalized()
		var axis := (rest_global.inverse() * _up_in_skeleton).normalized()
		var rest := _skeleton.get_bone_rest(bone).basis.get_rotation_quaternion()
		_skeleton.set_bone_pose_rotation(bone, rest * Quaternion(axis, deg_to_rad(degrees) * float(share[part])))


## The skeleton sits a few nodes down, turned and scaled; this takes a point
## in its space to this character's own. It does not need the scene tree.
func _skeleton_to_rig() -> Transform3D:
	var out := Transform3D.IDENTITY
	var node: Node = _skeleton
	while node != null and node != self:
		if node is Node3D:
			out = (node as Node3D).transform * out
		node = node.get_parent()
	return out


## Builds one weapon from its entry in weapons.gd: scaled to its length,
## pointing down -Z, with the point the trigger hand holds at the origin.
## The node it returns carries three facts about the weapon in that space:
## "muzzle" (where bullets leave), "fore" (where the other hand goes) and
## "butt" (how far behind the trigger hand the weapon ends).
static func make_weapon(spec: Dictionary, size := 1.0) -> Node3D:
	var holder := Node3D.new()
	var model: Node3D = load("res://assets/guns/%s.glb" % spec.model).instantiate()
	holder.add_child(model)
	var raw := _bounds(model)
	var length: float = float(spec.length) * size
	var fit: float = length / maxf(raw.size.x, 0.01)
	var tall: float = raw.size.y * fit
	model.scale = Vector3.ONE * fit
	var grip: Vector2 = spec.grip
	# The models lie along X with the muzzle at +X; turn them to point the
	# way the trooper aims, and slide them so the grip is at the origin: the
	# middle of the thickness across, the grip's height up, its place along.
	model.rotation = Vector3(0, PI * 0.5, 0)
	model.position = Vector3(
		-(raw.position.z + raw.size.z * 0.5) * fit,
		-(raw.position.y * fit + grip.y * tall),
		raw.position.x * fit + grip.x * length)
	var bore: float = float(spec.get("bore", 0.6))
	holder.set_meta("muzzle", Vector3(0, (bore - grip.y) * tall, -(1.0 - grip.x) * length))
	holder.set_meta("butt", grip.x * length)
	var fore: Vector2 = spec.fore
	holder.set_meta("fore", Vector3(0, (fore.y - grip.y) * tall, -(fore.x - grip.x) * length))
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
