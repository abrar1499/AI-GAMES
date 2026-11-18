extends SceneTree
## A side view of every weapon with its three points marked, to set the
## `grip`, `fore` and `bore` numbers in weapons.gd by eye:
##
##   green  where the trigger hand holds it (grip)
##   blue   where the other hand holds it (fore)
##   red    where bullets leave (the muzzle, at the height `bore`)
##
## The ruler under each gun runs from the butt (0) to the muzzle (1); the
## one at the side from its lowest point (0) to its highest (1).
## Needs a display (on a server: xvfb-run).
##
##   godot --path . -s tools/gun_shots.gd -- <output folder> [weapon id ...]

const Rig := preload("res://rig.gd")
const Weapons := preload("res://weapons.gd")


func _initialize() -> void:
	_run()


func _dot(parent: Node3D, at: Vector3, color: Color) -> void:
	var dot := MeshInstance3D.new()
	var ball := SphereMesh.new()
	ball.radius = 0.012
	ball.height = 0.024
	dot.mesh = ball
	var paint := StandardMaterial3D.new()
	paint.albedo_color = color
	paint.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	paint.no_depth_test = true
	dot.material_override = paint
	dot.position = at
	parent.add_child(dot)


func _run() -> void:
	var args := OS.get_cmdline_user_args()
	var out_dir: String = args[0] if args.size() > 0 else "user://guns"
	DirAccess.make_dir_recursive_absolute(out_dir)
	var ids: Array = args.slice(1)
	if ids.is_empty():
		for w: Dictionary in Weapons.LIST:
			ids.append(w.id)

	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color("9aa9ba")
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color.WHITE
	env.ambient_light_energy = 1.0
	var we := WorldEnvironment.new()
	we.environment = env
	root.add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-35, 120, 0)
	sun.light_energy = 1.6
	root.add_child(sun)
	var cam := Camera3D.new()
	cam.projection = Camera3D.PROJECTION_ORTHOGONAL
	root.add_child(cam)
	cam.make_current()

	var layer := CanvasLayer.new()
	root.add_child(layer)
	var ruler := Control.new()
	ruler.set_anchors_preset(Control.PRESET_FULL_RECT)
	layer.add_child(ruler)
	var state := {"box": AABB(), "cam": cam}
	ruler.draw.connect(func():
		var box: AABB = state.box
		var font := ruler.get_theme_default_font()
		for k in 11:
			# Along: 0 at the butt (largest z), 1 at the muzzle (smallest z).
			var at := cam.unproject_position(Vector3(0, box.position.y, box.end.z - box.size.z * k / 10.0))
			ruler.draw_line(at + Vector2(0, 6), at + Vector2(0, 22), Color.DARK_RED, 2.0)
			ruler.draw_string(font, at + Vector2(-10, 40), "%.1f" % (k / 10.0), HORIZONTAL_ALIGNMENT_LEFT, -1, 16, Color.DARK_RED)
			var up := cam.unproject_position(Vector3(0, box.position.y + box.size.y * k / 10.0, box.end.z))
			ruler.draw_line(up + Vector2(-22, 0), up + Vector2(-6, 0), Color.DARK_GREEN, 2.0)
			if k % 2 == 0:
				ruler.draw_string(font, up + Vector2(-56, 6), "%.1f" % (k / 10.0), HORIZONTAL_ALIGNMENT_LEFT, -1, 16, Color.DARK_GREEN))

	for id: String in ids:
		var w := Weapons.by_id(id)
		var held := Rig.make_weapon(w)
		root.add_child(held)
		_dot(held, Vector3.ZERO, Color.GREEN)
		_dot(held, held.get_meta("fore"), Color.DODGER_BLUE)
		_dot(held, held.get_meta("muzzle"), Color.RED)
		var box := Rig._bounds(held.get_child(0))
		state.box = box
		var centre := box.get_center()
		var view := root.get_visible_rect().size
		cam.size = maxf(box.size.z * 1.3 * view.y / view.x, box.size.y * 1.7)
		# From the weapon's left, so the muzzle is on the right of the picture.
		cam.look_at_from_position(centre + Vector3(3, 0, 0), centre)
		ruler.queue_redraw()
		for i in 4:
			await process_frame
		root.get_texture().get_image().save_png(out_dir.path_join("%s.png" % id))
		print("saved ", id)
		held.queue_free()
		await process_frame
	quit()
