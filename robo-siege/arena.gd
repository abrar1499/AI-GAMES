extends Node3D
## The outpost: floor, walls, cover, lights, and the grid robots use to find
## their way round the cover.

const Rig := preload("res://rig.gd")

## Photographed PBR materials (CC0, Poly Haven). Each one is a colour map, a
## normal map, and an "arm" map holding ambient occlusion, roughness and
## metallic in its red, green and blue channels.
const TEXTURES := {
	"floor": "concrete_floor_02",
	"wall": "concrete_floor_01",
	"panel": "factory_wall",
	"metal": "metal_plate",
	"container": "container_side",
	"iron": "rusty_corrugated_iron",
}

static var _pbr := {}

const HALF := 30.0
const WALL_H := 4.5

## Cover: [x, z, width (x), height, depth (z), style]. Styles: 0 pillar,
## 1 low wall, 2 crate, 3 container.
const COVER := [
	[-6, -6, 2, 4, 2, 0], [6, -6, 2, 4, 2, 0], [-6, 6, 2, 4, 2, 0], [6, 6, 2, 4, 2, 0],
	[0, -13, 8, 1.2, 1, 1], [0, 13, 8, 1.2, 1, 1], [-13, 0, 1, 1.2, 8, 1], [13, 0, 1, 1.2, 8, 1],
	[-16, -16, 2, 2, 2, 2], [16, -16, 2, 2, 2, 2], [-16, 16, 2, 2, 2, 2], [16, 16, 2, 2, 2, 2],
	[-18.3, -15, 1.4, 1.4, 1.4, 2], [18.3, 15, 1.4, 1.4, 1.4, 2], [-15, 18.3, 1.4, 1.4, 1.4, 2], [15, -18.3, 1.4, 1.4, 1.4, 2],
	[-22, -6, 1, 2.6, 7, 1], [22, 6, 1, 2.6, 7, 1], [-6, 22, 7, 2.6, 1, 1], [6, -22, 7, 2.6, 1, 1],
	[-9, -21, 6, 2.6, 2.4, 3], [9, 21, 6, 2.6, 2.4, 3], [21, -9, 2.4, 2.6, 6, 3], [-21, 9, 2.4, 2.6, 6, 3],
]

## Where robots come in.
const GATES := [
	Vector3(0, 0, -27), Vector3(0, 0, 27), Vector3(-27, 0, 0), Vector3(27, 0, 0),
	Vector3(-25, 0, -25), Vector3(25, 0, -25), Vector3(-25, 0, 25), Vector3(25, 0, 25),
]

var grid := AStarGrid2D.new()
var sun: DirectionalLight3D


func _ready() -> void:
	_build_sky()
	_build_floor()
	_build_walls()
	for c: Array in COVER:
		_build_cover(Vector3(c[0], 0, c[1]), Vector3(c[2], c[3], c[4]), int(c[5]))
	_build_gates()
	_build_grid()


# ---------------------------------------------------------------- finding the way

func _build_grid() -> void:
	grid.region = Rect2i(int(-HALF), int(-HALF), int(HALF * 2), int(HALF * 2))
	grid.cell_size = Vector2.ONE
	grid.offset = Vector2(0.5, 0.5)
	grid.diagonal_mode = AStarGrid2D.DIAGONAL_MODE_ONLY_IF_NO_OBSTACLES
	grid.update()
	var margin := 0.65
	for c: Array in COVER:
		var x0 := float(c[0]) - float(c[2]) * 0.5 - margin
		var x1 := float(c[0]) + float(c[2]) * 0.5 + margin
		var z0 := float(c[1]) - float(c[4]) * 0.5 - margin
		var z1 := float(c[1]) + float(c[4]) * 0.5 + margin
		for i in range(int(floor(x0)), int(ceil(x1))):
			for j in range(int(floor(z0)), int(ceil(z1))):
				var centre := Vector2(i + 0.5, j + 0.5)
				if centre.x > x0 and centre.x < x1 and centre.y > z0 and centre.y < z1 and grid.is_in_boundsv(Vector2i(i, j)):
					grid.set_point_solid(Vector2i(i, j), true)


func _cell(p: Vector3) -> Vector2i:
	var c := Vector2i(int(floor(p.x)), int(floor(p.z)))
	c.x = clampi(c.x, int(-HALF) + 1, int(HALF) - 2)
	c.y = clampi(c.y, int(-HALF) + 1, int(HALF) - 2)
	if not grid.is_point_solid(c):
		return c
	# Standing inside cover's margin: use the nearest open cell.
	for r in range(1, 5):
		for dx in range(-r, r + 1):
			for dz in range(-r, r + 1):
				var n := c + Vector2i(dx, dz)
				if grid.is_in_boundsv(n) and not grid.is_point_solid(n):
					return n
	return c


## Points to walk through to get from `from` to `to`, going round cover.
func path(from: Vector3, to: Vector3) -> PackedVector3Array:
	var out := PackedVector3Array()
	for p in grid.get_point_path(_cell(from), _cell(to)):
		out.append(Vector3(p.x, 0.0, p.y))
	return out


func is_open(p: Vector3) -> bool:
	var c := Vector2i(int(floor(p.x)), int(floor(p.z)))
	return grid.is_in_boundsv(c) and not grid.is_point_solid(c)


## A gate that is not right next to `avoid`.
func gate_away_from(avoid: Vector3) -> Vector3:
	var far: Array = []
	for g: Vector3 in GATES:
		if g.distance_to(avoid) > 16.0:
			far.append(g)
	if far.is_empty():
		return GATES[randi() % GATES.size()]
	return far[randi() % far.size()]


# ---------------------------------------------------------------- building

func _solid(size: Vector3, pos: Vector3, material: Material, shadows := true) -> StaticBody3D:
	var body := StaticBody3D.new()
	body.position = pos
	var shape := BoxShape3D.new()
	shape.size = size
	var cs := CollisionShape3D.new()
	cs.shape = shape
	body.add_child(cs)
	var mesh := BoxMesh.new()
	mesh.size = size
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = material
	if not shadows:
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	body.add_child(mi)
	add_child(body)
	return body


func _trim(parent: Node3D, size: Vector3, pos: Vector3, material: Material) -> void:
	var mesh := BoxMesh.new()
	mesh.size = size
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.position = pos
	mi.material_override = material
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	parent.add_child(mi)


## A material from one of the photographed texture sets. `tile` is how many
## metres one tile of the texture covers; `triplanar` keeps the texel size
## even on long walls and the floor, where plain tiling would stretch.
static func pbr(which: String, tile := 2.0, triplanar := false, tint := Color.WHITE) -> StandardMaterial3D:
	var key := "%s|%.2f|%s|%s" % [which, tile, triplanar, tint.to_html()]
	if _pbr.has(key):
		return _pbr[key]
	var base: String = TEXTURES[which]
	var m := StandardMaterial3D.new()
	m.albedo_texture = load("res://assets/textures/%s_diff.jpg" % base)
	m.albedo_color = tint
	m.normal_enabled = true
	m.normal_texture = load("res://assets/textures/%s_nor_gl.jpg" % base)
	var arm: Texture2D = load("res://assets/textures/%s_arm.jpg" % base)
	m.ao_enabled = true
	m.ao_texture = arm
	m.ao_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_RED
	m.ao_light_affect = 0.6
	m.roughness_texture = arm
	m.roughness_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_GREEN
	m.metallic = 1.0
	m.metallic_texture = arm
	m.metallic_texture_channel = BaseMaterial3D.TEXTURE_CHANNEL_BLUE
	m.uv1_triplanar = triplanar
	m.uv1_scale = Vector3.ONE / tile
	_pbr[key] = m
	return m


## The same material sized for one box, so its tiles stay square.
static func pbr_for_box(which: String, size: Vector3, tile := 2.0, tint := Color.WHITE) -> StandardMaterial3D:
	var m: StandardMaterial3D = pbr(which, tile, false, tint).duplicate()
	# A box face spans 0..1 in UV, so the scale has to carry the face's size.
	m.uv1_scale = Vector3(maxf(size.x, size.z), size.y, 1.0) / tile
	return m


func _build_sky() -> void:
	# A photographed sky (CC0, Poly Haven) lights the whole scene: the colour
	# of the light, the shadows and the reflections all come from it.
	var sky_mat := PanoramaSkyMaterial.new()
	sky_mat.panorama = load("res://assets/sky/sky.hdr")
	sky_mat.energy_multiplier = 1.0
	var sky := Sky.new()
	sky.sky_material = sky_mat
	sky.radiance_size = Sky.RADIANCE_SIZE_128

	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 1.0
	env.reflected_light_source = Environment.REFLECTION_SOURCE_SKY
	env.tonemap_mode = Environment.TONE_MAPPER_ACES
	env.tonemap_white = 9.0
	env.ssao_enabled = false
	env.fog_enabled = true
	env.fog_mode = Environment.FOG_MODE_DEPTH
	env.fog_light_color = Color("cfe0f0")
	env.fog_density = 0.0016
	env.fog_sky_affect = 0.0
	env.adjustment_enabled = true
	env.adjustment_contrast = 1.06
	env.adjustment_saturation = 1.1
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)

	# The sun is aimed to match the one in the sky image, so shadows agree
	# with the background.
	sun = DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-42, 125, 0)
	sun.light_color = Color("fff1dd")
	sun.light_energy = 1.5
	sun.light_specular = 0.6
	sun.shadow_enabled = true
	sun.shadow_bias = 0.04
	sun.shadow_normal_bias = 1.2
	sun.directional_shadow_max_distance = 60.0
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_PARALLEL_2_SPLITS
	add_child(sun)


func _build_floor() -> void:
	var m := pbr("floor", 3.0, true)
	var body := _solid(Vector3(HALF * 2 + 8, 1, HALF * 2 + 8), Vector3(0, -0.5, 0), m, false)
	body.name = "Floor"
	# Painted ring in the middle of the outpost.
	var paint := Rig.mat(Color("ff7a1a"), 0.15, 0.0, 0.8)
	var ring := TorusMesh.new()
	ring.inner_radius = 3.3
	ring.outer_radius = 3.5
	var mi := MeshInstance3D.new()
	mi.mesh = ring
	mi.scale = Vector3(1, 0.02, 1)
	mi.position = Vector3(0, 0.012, 0)
	mi.material_override = paint
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(mi)


func _build_walls() -> void:
	var wall := Rig.mat(Color("647080"), 0.0, 0.3, 0.7)
	var strip := Rig.mat(Color("ff7a1a"), 1.3, 0.0, 0.5)
	var cap := Rig.mat(Color("3d4450"), 0.0, 0.9, 0.35)   # dark steel trim
	var span := HALF * 2 + 2
	for side in 4:
		var along_x := side < 2
		var sign_ := -1.0 if side % 2 == 0 else 1.0
		var pos := Vector3(0, WALL_H * 0.5, sign_ * (HALF + 0.5)) if along_x else Vector3(sign_ * (HALF + 0.5), WALL_H * 0.5, 0)
		var size := Vector3(span, WALL_H, 1) if along_x else Vector3(1, WALL_H, span)
		var body := _solid(size, pos, wall)
		var inward := Vector3(0, 0, -sign_ * 0.52) if along_x else Vector3(-sign_ * 0.52, 0, 0)
		var strip_size := Vector3(span, 0.12, 0.04) if along_x else Vector3(0.04, 0.12, span)
		_trim(body, strip_size, inward + Vector3(0, 0.6, 0), strip)
		_trim(body, strip_size, inward + Vector3(0, -1.2, 0), cap)
		_trim(body, (size + Vector3(0.3, 0, 0.3)) * Vector3(1, 0, 1) + Vector3(0, 0.25, 0), Vector3(0, WALL_H * 0.5, 0), cap)
		# Buttresses break up the long wall.
		for k in range(-2, 3):
			var off := Vector3(k * 12.0, 0, 0) if along_x else Vector3(0, 0, k * 12.0)
			var b_size := Vector3(1.2, WALL_H + 0.6, 0.5) if along_x else Vector3(0.5, WALL_H + 0.6, 1.2)
			_trim(body, b_size, off + inward * 1.2 + Vector3(0, 0.3, 0), cap)


func _build_cover(pos: Vector3, size: Vector3, style: int) -> void:
	var centre := pos + Vector3(0, size.y * 0.5, 0)
	match style:
		0:  # metal pillar with a glowing band
			var body := _solid(size, centre, pbr_for_box("wall", size, 1.8, Color("cfcabf")))
			_trim(body, Vector3(size.x + 0.06, 0.14, size.z + 0.06), Vector3(0, 0.4, 0), Rig.mat(Color("35e0ff"), 2.0, 0.0, 0.4))
			var trim := Rig.mat(Color("3d4450"), 0.0, 0.9, 0.35)
			_trim(body, Vector3(size.x + 0.3, 0.3, size.z + 0.3), Vector3(0, -size.y * 0.5 + 0.15, 0), trim)
			_trim(body, Vector3(size.x + 0.3, 0.3, size.z + 0.3), Vector3(0, size.y * 0.5 - 0.15, 0), trim)
		1:  # concrete barrier with hazard paint on top
			var body := _solid(size, centre, pbr_for_box("wall", size, 2.0, Color("cdc9c0")))
			_trim(body, Vector3(size.x + 0.02, 0.1, size.z + 0.02), Vector3(0, size.y * 0.5 - 0.12, 0), Rig.mat(Color("ffc400"), 0.0, 0.0, 0.7))
		2:  # supply crate
			var body := _solid(size, centre, pbr_for_box("container", size, 1.3, Color("d9a24e")))
			var edge := Rig.mat(Color("4a4238"), 0.0, 0.8, 0.45)
			for sx: float in [-1.0, 1.0]:
				for sz: float in [-1.0, 1.0]:
					_trim(body, Vector3(0.14, size.y + 0.04, 0.14), Vector3(sx * size.x * 0.5, 0, sz * size.z * 0.5), edge)
			_trim(body, Vector3(size.x + 0.04, 0.14, size.z + 0.04), Vector3(0, size.y * 0.5, 0), edge)
			_trim(body, Vector3(size.x + 0.04, 0.14, size.z + 0.04), Vector3(0, -size.y * 0.5 + 0.07, 0), edge)
		3:  # shipping container
			var tint: Color = [Color("9fc6d8"), Color("d89a62"), Color("c0bfae")][int(absf(pos.x + pos.z)) % 3]
			var m := pbr_for_box("container", size, 2.4, tint)
			var body := _solid(size, centre, m)
			var rail := Rig.mat(Color("3d4450"), 0.0, 0.9, 0.4)
			for sy: float in [-1.0, 1.0]:
				_trim(body, Vector3(size.x + 0.06, 0.18, size.z + 0.06), Vector3(0, sy * (size.y * 0.5 - 0.09), 0), rail)


func _build_gates() -> void:
	var frame := Rig.mat(Color("3a4350"), 0.0, 0.9, 0.4)
	var glow := Rig.mat(Color("ff3b30"), 2.2, 0.0, 0.4)
	for g: Vector3 in GATES:
		# A pad on the floor that robots beam in on.
		var pad := CylinderMesh.new()
		pad.top_radius = 1.5
		pad.bottom_radius = 1.5
		pad.height = 0.06
		var mi := MeshInstance3D.new()
		mi.mesh = pad
		mi.position = g + Vector3(0, 0.03, 0)
		mi.material_override = frame
		mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		add_child(mi)
		var ring := TorusMesh.new()
		ring.inner_radius = 1.25
		ring.outer_radius = 1.4
		var ri := MeshInstance3D.new()
		ri.mesh = ring
		ri.scale = Vector3(1, 0.15, 1)
		ri.position = g + Vector3(0, 0.07, 0)
		ri.material_override = glow
		ri.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		add_child(ri)
