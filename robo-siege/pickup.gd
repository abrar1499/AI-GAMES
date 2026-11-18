extends Node3D
## Something lying on the ground that makes the trooper's weapon better.
## Walk over it to take it. main.gd puts them down and takes them up; this
## is only what one looks like.
##
## It is a bright core inside two rings that turn, on a ring of light, with
## a shaft of light above it so it can be seen from across the outpost.

## What each kind does to the weapon, what it is called, and its colour.
const KINDS := {
	"power": {"title": "DAMAGE +20%", "color": Color("ff7a1a")},
	"mag": {"title": "MAGAZINE +10", "color": Color("3fd8ff")},
	"steady": {"title": "ACCURACY UP", "color": Color("56e08a")},
}

var kind := "power"
var age := 0.0

var _core: Node3D
var _rings: Array[Node3D] = []


func _ready() -> void:
	add_to_group("pickups")
	var color: Color = KINDS[kind].color
	_core = Node3D.new()
	_core.position.y = 0.85
	add_child(_core)
	var ball := SphereMesh.new()
	ball.radius = 0.17
	ball.height = 0.34
	ball.radial_segments = 16
	ball.rings = 8
	_core.add_child(_part(ball, color.lerp(Color.WHITE, 0.55), 1.0))
	for i in 2:
		var hoop := TorusMesh.new()
		hoop.inner_radius = 0.27 + i * 0.09
		hoop.outer_radius = 0.31 + i * 0.09
		hoop.rings = 32
		hoop.ring_segments = 6
		var ring := _part(hoop, color, 1.0)
		ring.rotation.x = PI * 0.5 if i == 0 else 0.6
		_core.add_child(ring)
		_rings.append(ring)
	# On the ground: a ring to stand in.
	var mark := TorusMesh.new()
	mark.inner_radius = 0.62
	mark.outer_radius = 0.7
	mark.rings = 40
	mark.ring_segments = 4
	var on_ground := _part(mark, color, 0.85)
	on_ground.position.y = 0.04
	add_child(on_ground)
	# And a shaft of light, thinning out upwards.
	var shaft := CylinderMesh.new()
	shaft.top_radius = 0.02
	shaft.bottom_radius = 0.2
	shaft.height = 5.0
	shaft.radial_segments = 10
	shaft.cap_top = false
	shaft.cap_bottom = false
	var light := _part(shaft, color, 0.22)
	light.position.y = 2.5
	add_child(light)
	scale = Vector3.ONE * 0.2
	create_tween().tween_property(self, "scale", Vector3.ONE, 0.35).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)


func _part(mesh: Mesh, color: Color, alpha: float) -> MeshInstance3D:
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.albedo_color = Color(color, alpha)
	if alpha < 1.0:
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		m.cull_mode = BaseMaterial3D.CULL_DISABLED
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = m
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	return mi


func _process(dt: float) -> void:
	age += dt
	_core.position.y = 0.85 + sin(age * 2.4) * 0.1
	_core.rotation.y += dt * 1.8
	_rings[0].rotation.z += dt * 2.6
	_rings[1].rotation.x += dt * 3.4
