extends SceneTree
## Close pictures of the trooper in the outpost's own light, holding a
## weapon: the whole figure, the chest and hands from each side, and the
## view from behind that the game is played from. For checking by eye that
## the hands are on the weapon and that the uniform looks right.
## Needs a display (on a server: xvfb-run).
##
##   godot --path . -s tools/hero_shots.gd -- <output folder> [weapon id ...] [views=a,b,c]

const Rig := preload("res://rig.gd")
const Arena := preload("res://arena.gd")
const Weapons := preload("res://weapons.gd")

## Each view: where the camera is and what it looks at, as seen by a
## trooper standing at the origin facing -Z, and how wide the lens is.
const VIEWS := {
	"front": [Vector3(1.5, 1.35, -3.6), Vector3(0.0, 1.0, 0.0), 36.0],
	"bust": [Vector3(0.85, 1.5, -1.5), Vector3(0.05, 1.3, -0.1), 36.0],
	"right": [Vector3(1.35, 1.35, -0.3), Vector3(0.1, 1.27, -0.3), 34.0],
	"left": [Vector3(-1.35, 1.35, -0.5), Vector3(0.1, 1.27, -0.3), 34.0],
	"under": [Vector3(0.25, 0.55, -1.25), Vector3(0.1, 1.25, -0.3), 34.0],
	"back": [Vector3(0.8, 1.85, 2.7), Vector3(0.1, 1.25, -1.0), 50.0],
	# The two hands, from the sides they can be seen from.
	"trigger_out": [Vector3(0.8, 1.3, -0.28), Vector3(0.1, 1.2, -0.2), 26.0],
	"trigger_low": [Vector3(0.5, 0.75, -0.55), Vector3(0.1, 1.2, -0.2), 26.0],
	"trigger_rear": [Vector3(0.5, 1.5, 0.42), Vector3(0.1, 1.2, -0.18), 26.0],
	"support_out": [Vector3(-0.62, 1.16, -0.62), Vector3(0.1, 1.24, -0.48), 26.0],
	"support_in": [Vector3(0.72, 1.12, -0.78), Vector3(0.1, 1.24, -0.48), 26.0],
	"support_top": [Vector3(0.2, 1.95, -0.85), Vector3(0.1, 1.24, -0.48), 26.0],
}


func _initialize() -> void:
	_run()


func _run() -> void:
	var args := OS.get_cmdline_user_args()
	var out_dir: String = args[0] if args.size() > 0 else "user://hero"
	DirAccess.make_dir_recursive_absolute(out_dir)
	var ids: Array = []
	var wanted: Array = VIEWS.keys()
	for arg: String in args.slice(1):
		if arg.begins_with("views="):
			wanted = Array(arg.trim_prefix("views=").split(","))
		else:
			ids.append(arg)
	if ids.is_empty():
		for w: Dictionary in Weapons.LIST:
			ids.append(w.id)

	var arena := Arena.new()
	root.add_child(arena)
	# Stand where the sun is on the trooper's front and left.
	var spot := Node3D.new()
	spot.position = Vector3(0, 0, 3)
	spot.rotation.y = deg_to_rad(-150.0)
	root.add_child(spot)
	var cam := Camera3D.new()
	root.add_child(cam)
	cam.make_current()

	for id: String in ids:
		var rig := Rig.new()
		spot.add_child(rig)
		rig.build(Rig.Kind.TROOPER, 0, Weapons.by_id(id))
		for i in 14:
			await process_frame
		for view: String in wanted:
			var setup: Array = VIEWS[view]
			cam.fov = setup[2]
			cam.look_at_from_position(spot.global_transform * (setup[0] as Vector3), spot.global_transform * (setup[1] as Vector3))
			await process_frame
			await process_frame
			root.get_texture().get_image().save_png(out_dir.path_join("%s_%s.png" % [id, view]))
		print("saved ", id)
		rig.queue_free()
		await process_frame
	quit()
