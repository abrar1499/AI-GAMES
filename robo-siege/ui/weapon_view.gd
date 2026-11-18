extends Control
## One weapon, drawn in 3D inside a menu.
##
## As an icon it is a still picture from the side, drawn once. Set `live`
## before adding it to the scene and it becomes a showpiece instead: lit
## like a studio, turning slowly on a stand, and turned by hand when dragged.

const UI := preload("res://ui.gd")
const Rig := preload("res://rig.gd")

## The lighting the weapons are shown in: shared, so it is only made once.
static var _studio: Environment

var live := false

var _view: SubViewport
var _picture: TextureRect
var _yaw: Node3D
var _pitch: Node3D
var _cam: Camera3D
var _stand: Node3D
var _held: Node3D
var _box := AABB()
var _dragging := false
var _idle := 9.0
var _spec: Dictionary = {}


func _init() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE


func _ready() -> void:
	if live:
		mouse_filter = Control.MOUSE_FILTER_STOP
	_view = SubViewport.new()
	_view.transparent_bg = true
	_view.own_world_3d = true
	_view.world_3d = World3D.new()
	_view.msaa_3d = Viewport.MSAA_4X
	_view.render_target_update_mode = SubViewport.UPDATE_ALWAYS if live else SubViewport.UPDATE_DISABLED
	add_child(_view)
	_picture = TextureRect.new()
	_picture.texture = _view.get_texture()
	_picture.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	_picture.stretch_mode = TextureRect.STRETCH_SCALE
	_picture.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	_picture.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(_picture)

	var we := WorldEnvironment.new()
	we.environment = _studio_light()
	_view.add_child(we)
	# A key light from the front and above, a cooler one filling the shadows,
	# and one from behind to pick out the edges.
	var key := DirectionalLight3D.new()
	key.rotation_degrees = Vector3(-38, 35, 0)
	key.light_energy = 1.5
	_view.add_child(key)
	var fill := DirectionalLight3D.new()
	fill.rotation_degrees = Vector3(-10, -70, 0)
	fill.light_color = Color("9cc4ff")
	fill.light_energy = 0.55
	_view.add_child(fill)
	var rim := DirectionalLight3D.new()
	rim.rotation_degrees = Vector3(-25, 165, 0)
	rim.light_color = Color("8fe6ff") if live else Color.WHITE
	rim.light_energy = 1.1 if live else 0.5
	_view.add_child(rim)

	_yaw = Node3D.new()
	_view.add_child(_yaw)
	_pitch = Node3D.new()
	_yaw.add_child(_pitch)
	_cam = Camera3D.new()
	_view.add_child(_cam)
	if live:
		_cam.fov = 26.0
		_make_stand()
	else:
		_cam.projection = Camera3D.PROJECTION_ORTHOGONAL
	_fit()
	if not _spec.is_empty():
		show_weapon(_spec)


func _notification(what: int) -> void:
	if what == NOTIFICATION_RESIZED and _view != null:
		_fit()


## Daylight from the game's own sky, so metal has something to reflect,
## but with no sky drawn: the menu shows through behind the weapon.
static func _studio_light() -> Environment:
	if _studio != null:
		return _studio
	_studio = Environment.new()
	_studio.background_mode = Environment.BG_COLOR
	_studio.background_color = Color(0, 0, 0, 0)
	var sky_path := "res://assets/sky/sky.hdr"
	if ResourceLoader.exists(sky_path):
		var material := PanoramaSkyMaterial.new()
		material.panorama = load(sky_path)
		var sky := Sky.new()
		sky.sky_material = material
		sky.radiance_size = Sky.RADIANCE_SIZE_128
		_studio.sky = sky
		_studio.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
		_studio.reflected_light_source = Environment.REFLECTION_SOURCE_SKY
		_studio.ambient_light_energy = 1.1
	else:
		_studio.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
		_studio.ambient_light_color = Color("a9bfdc")
		_studio.ambient_light_energy = 1.3
	_studio.tonemap_mode = Environment.TONE_MAPPER_ACES
	_studio.tonemap_white = 6.0
	return _studio


## The stand a live weapon turns above: a dark disc with rings of light.
## It is built one metre across and scaled to the weapon when one is shown.
func _make_stand() -> void:
	_stand = Node3D.new()
	_view.add_child(_stand)
	var disc := MeshInstance3D.new()
	var plate := CylinderMesh.new()
	plate.top_radius = 0.5
	plate.bottom_radius = 0.5
	plate.height = 0.004
	plate.radial_segments = 64
	disc.mesh = plate
	var dark := StandardMaterial3D.new()
	dark.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	dark.albedo_color = Color(0.07, 0.14, 0.24, 0.55)
	dark.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	disc.material_override = dark
	_stand.add_child(disc)
	for ring_radius: float in [0.5, 0.36, 0.2]:
		var ring := MeshInstance3D.new()
		var torus := TorusMesh.new()
		var thick := 0.005 if ring_radius > 0.45 else 0.0025
		torus.inner_radius = ring_radius - thick
		torus.outer_radius = ring_radius + thick
		torus.rings = 72
		torus.ring_segments = 6
		ring.mesh = torus
		var lit := StandardMaterial3D.new()
		lit.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		lit.albedo_color = Color(UI.CYAN, 1.0 if ring_radius > 0.45 else 0.4)
		lit.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		ring.material_override = lit
		ring.position.y = 0.004
		_stand.add_child(ring)


## Draws the picture as sharply as the screen is, not as the menu's own
## coarser units, and frames the weapon in it.
func _fit() -> void:
	var sharp := 1.0
	if is_inside_tree():
		sharp = clampf(get_viewport().get_screen_transform().get_scale().y, 1.0, 2.5)
	var pixels := Vector2i((size * sharp * (1.0 if live else 1.5)).max(Vector2(16, 16)))
	if _view.size != pixels:
		_view.size = pixels
	_frame()


## Shows this weapon (an entry from weapons.gd).
func show_weapon(spec: Dictionary) -> void:
	_spec = spec
	if _view == null:
		return
	if _held:
		_held.queue_free()
	# Every weapon is shown one metre long, so a pistol fills its frame too;
	# the showpiece keeps a little of the real difference in size.
	var shown := 1.0 / float(spec.length)
	if live:
		shown *= lerpf(0.72, 1.0, clampf((float(spec.length) - 0.3) / 0.8, 0.0, 1.0))
	_held = Rig.make_weapon(spec, shown)
	_pitch.add_child(_held)
	_box = Rig._bounds(_held.get_child(0))
	# Centre it, so it turns about its own middle.
	_held.position = -_box.get_center()
	_frame()
	if live:
		_yaw.rotation.y = -2.0
		_pitch.rotation.x = 0.0
		_idle = 9.0
		_held.scale = Vector3.ONE * 0.8
		_held.create_tween().tween_property(_held, "scale", Vector3.ONE, 0.3).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	else:
		_view.render_target_update_mode = SubViewport.UPDATE_ONCE


func _frame() -> void:
	if _cam == null or _box.size == Vector3.ZERO:
		return
	var long := _box.size.z
	var tall := _box.size.y
	var shape := float(_view.size.x) / maxf(float(_view.size.y), 1.0)
	if live:
		# Far enough back for the whole length, whichever way it is turned.
		var reach := maxf(long * 0.5 / shape, tall * 0.7) / tan(deg_to_rad(_cam.fov * 0.5)) * 1.16
		_cam.look_at_from_position(Vector3(0, tall * 0.2 + long * 0.16, reach + long * 0.08), Vector3(0, -long * 0.05, 0))
		if _stand:
			# Under the weapon's lowest point, and a little wider than it is long.
			_stand.position.y = -tall * 0.5 - 0.06 - long * 0.05
			_stand.scale = Vector3.ONE * long * 1.02
	else:
		_cam.size = maxf(long * 1.08 / shape, tall * 1.12)
		# From the weapon's left, so the muzzle is on the right.
		_cam.look_at_from_position(Vector3(3, 0, 0), Vector3.ZERO)
		_view.render_target_update_mode = SubViewport.UPDATE_ONCE


func _process(dt: float) -> void:
	if not live or _yaw == null:
		return
	_idle += dt
	if not _dragging and _idle > 1.2:
		_yaw.rotation.y += dt * 0.55
		_pitch.rotation.x = lerpf(_pitch.rotation.x, 0.0, minf(1.0, dt * 2.0))


func _gui_input(event: InputEvent) -> void:
	if not live:
		return
	if event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT:
		_dragging = event.pressed
		_idle = 0.0
	elif event is InputEventMouseMotion and _dragging:
		_yaw.rotation.y += event.relative.x * 0.012
		_pitch.rotation.x = clampf(_pitch.rotation.x + event.relative.y * 0.008, -0.6, 0.6)
		_idle = 0.0
