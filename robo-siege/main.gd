extends Node3D
## Robo Siege 3D: a third-person shooter. Hold the outpost against waves of
## robots; after each wave pick one upgrade.
##
## This file runs the game: menus, waves, upgrades, score. The trooper is in
## player.gd, the robots in enemy.gd, the level in arena.gd.

const Arena := preload("res://arena.gd")
const Player := preload("res://player.gd")
const Enemy := preload("res://enemy.gd")
const Fx := preload("res://fx.gd")
const Hud := preload("res://hud.gd")
const Sfx := preload("res://sfx.gd")
const Rig := preload("res://rig.gd")
const Weapons := preload("res://weapons.gd")
const UI := preload("res://ui.gd")
const Lobby := preload("res://lobby.gd")
const Armory := preload("res://armory.gd")
const Plate := preload("res://ui/plate.gd")
const TechButton := preload("res://ui/tech_button.gd")
const UpgradeCard := preload("res://ui/upgrade_card.gd")

enum State { MENU, PLAYING, UPGRADE, PAUSED, OVER }

## Where the settings and best scores are kept. The tools in the tools
## folder point this somewhere else, so a test never touches a real save.
var save_path := "user://robo_siege.cfg"
const MAX_ALIVE := 11
const BOSS_EVERY := 5

const C_ORANGE := Color("ff7a1a")

## Upgrades offered between waves: [title, what it does, colour].
const UPGRADES := {
	"damage": ["Hot Rounds", "+25% bullet damage", Color("ff5a3c")],
	"rate": ["Rapid Fire", "+18% fire rate", Color("ffc400")],
	"mag": ["Extended Mag", "+12 rounds, faster reload", Color("35e0ff")],
	"armour": ["Thick Armour", "+30 armour, full repair", Color("4cd964")],
	"regen": ["Nano Repair", "Armour repairs 60% faster", Color("7be0a0")],
	"speed": ["Servo Legs", "+12% speed, dash more often", Color("b08cff")],
	"grenade": ["Big Bombs", "Grenades hit harder, ready sooner", Color("ff8a1f")],
}

var state: State = State.MENU
var wave := 0
var score := 0
var best_wave := 0
var best_score := 0
var sound_on := true
var auto_fire := true
var skin := 0
## The two weapons the trooper takes into the fight.
var loadout: Array[String] = ["rifle", "ak"]

var arena: Node3D
var player: CharacterBody3D
var fx: Node3D
var hud: Control
var sfx: Node
var ui: CanvasLayer
var panel: Control
var world: Node3D

var _to_spawn: Array = []
var _spawn_wait := 0.0
var _wave_gap := 0.0
var _menu_cam: Camera3D
var _menu_angle := 0.0
var _over_wait := 0.0
var _model: Node3D
var _stage: Node3D
var _test := 0


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	_load()
	sfx = Sfx.new()
	sfx.enabled = sound_on
	add_child(sfx)
	UI.install()
	UI.on_click = func(): sfx.play("click", 1.0, -4.0)

	world = Node3D.new()
	world.process_mode = Node.PROCESS_MODE_PAUSABLE
	add_child(world)
	arena = Arena.new()
	world.add_child(arena)
	fx = Fx.new()
	fx.sound_at.connect(sfx.play_at)
	world.add_child(fx)

	_menu_cam = Camera3D.new()
	_menu_cam.fov = 38.0
	add_child(_menu_cam)

	ui = CanvasLayer.new()
	add_child(ui)
	hud = Hud.new()
	hud.pause_pressed.connect(_toggle_pause)
	ui.add_child(hud)
	get_tree().quit_on_go_back = false
	_show_menu()


func _notification(what: int) -> void:
	if what == NOTIFICATION_WM_GO_BACK_REQUEST:
		if state == State.PLAYING or state == State.PAUSED:
			_toggle_pause()
		elif state == State.MENU:
			get_tree().quit()
	elif what == NOTIFICATION_APPLICATION_FOCUS_OUT and state == State.PLAYING:
		_toggle_pause()


func _on_sound(sound_name: String, pitch: float, volume_db: float) -> void:
	sfx.play(sound_name, pitch, volume_db)


# ---------------------------------------------------------------- saving

func _load() -> void:
	var cfg := ConfigFile.new()
	if cfg.load(save_path) != OK:
		auto_fire = DisplayServer.is_touchscreen_available()
		return
	best_wave = cfg.get_value("best", "wave", 0)
	best_score = cfg.get_value("best", "score", 0)
	sound_on = cfg.get_value("options", "sound", true)
	auto_fire = cfg.get_value("options", "auto_fire", DisplayServer.is_touchscreen_available())
	skin = clampi(cfg.get_value("options", "skin", 0), 0, Rig.LOOKS.size() - 1)
	var saved_loadout: Array = cfg.get_value("options", "loadout", [])
	for i in mini(saved_loadout.size(), loadout.size()):
		if Weapons.exists(str(saved_loadout[i])):
			loadout[i] = str(saved_loadout[i])
	# A save from an older version can name a weapon that has since gone.
	if loadout[0] == loadout[1]:
		loadout = ["rifle", "ak"]


func _save() -> void:
	var cfg := ConfigFile.new()
	cfg.set_value("best", "wave", best_wave)
	cfg.set_value("best", "score", best_score)
	cfg.set_value("options", "sound", sound_on)
	cfg.set_value("options", "auto_fire", auto_fire)
	cfg.set_value("options", "skin", skin)
	cfg.set_value("options", "loadout", loadout)
	cfg.save(save_path)


# ---------------------------------------------------------------- starting and ending

## Stands the chosen trooper in the outpost for the lobby, holding the
## primary weapon, on a ring of light.
func _show_model() -> void:
	if _model:
		_model.queue_free()
	_model = Rig.new()
	world.add_child(_model)
	_model.build(Rig.Kind.TROOPER, skin, Weapons.by_id(loadout[0]))
	_model.position = Vector3.ZERO
	_model.rotation.y = 0.42
	_model.scale = Vector3.ONE * 0.7
	_model.create_tween().tween_property(_model, "scale", Vector3.ONE, 0.25).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	if _stage == null:
		_stage = Node3D.new()
		world.add_child(_stage)
		for radius: float in [0.95, 1.25]:
			var ring := MeshInstance3D.new()
			var torus := TorusMesh.new()
			torus.inner_radius = radius - 0.012
			torus.outer_radius = radius + 0.012
			torus.rings = 72
			torus.ring_segments = 6
			ring.mesh = torus
			var lit := StandardMaterial3D.new()
			lit.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
			lit.albedo_color = Color(UI.CYAN, 0.9 if radius < 1.0 else 0.35)
			lit.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
			ring.material_override = lit
			ring.position.y = 0.03
			_stage.add_child(ring)
		# A cool light from behind picks the trooper out of the background.
		var rim := OmniLight3D.new()
		rim.light_color = Color("7fd8ff")
		rim.light_energy = 2.2
		rim.omni_range = 6.0
		rim.position = Vector3(-1.6, 2.4, 1.8)
		_stage.add_child(rim)


func _clear_run() -> void:
	if _model:
		_model.queue_free()
		_model = null
	if _stage:
		_stage.queue_free()
		_stage = null
	for e in get_tree().get_nodes_in_group("enemies"):
		e.queue_free()
	for n in world.get_children():
		if n is RigidBody3D:
			n.queue_free()
	fx.clear()
	if player:
		player.queue_free()
		player = null
	hud.player = null
	hud.boss = null
	_to_spawn.clear()


func start_game() -> void:
	_close_panel()
	_clear_run()
	get_tree().paused = false
	wave = 0
	score = 0
	player = Player.new()
	player.fx = fx
	player.auto_fire = auto_fire
	player.look = skin
	player.slots = loadout.duplicate()
	player.assist = 0.75 if hud.touch else 0.35
	player.position = Vector3(0, 0.1, 2)
	player.shot_hit.connect(hud.on_shot_hit)
	player.hurt.connect(hud.on_hurt)
	player.died.connect(_on_player_died)
	player.sound.connect(_on_sound)
	world.add_child(player)
	fx.player = player
	hud.player = player
	hud.score = 0
	state = State.PLAYING
	hud.set_active(true)
	_wave_gap = 1.2


func _on_player_died() -> void:
	state = State.OVER
	_over_wait = 1.6
	hud.set_active(false)
	sfx.play("over")
	if wave > best_wave:
		best_wave = wave
	if score > best_score:
		best_score = score
	_save()


# ---------------------------------------------------------------- waves

## Which robots a wave is made of.
func _wave_list(n: int) -> Array:
	var list: Array = []
	var count := 4 + n * 2
	if n % BOSS_EVERY == 0:
		list.append(Rig.Kind.BOSS)
		count = int(count * 0.6)
	for i in count:
		var roll := randf()
		if n >= 4 and roll < minf(0.08 + n * 0.012, 0.22):
			list.append(Rig.Kind.HEAVY)
		elif n >= 2 and roll < minf(0.3 + n * 0.02, 0.5):
			list.append(Rig.Kind.RUSHER)
		else:
			list.append(Rig.Kind.GRUNT)
	return list


func _start_wave() -> void:
	wave += 1
	_to_spawn = _wave_list(wave)
	_spawn_wait = 1.0
	var boss_wave := wave % BOSS_EVERY == 0
	hud.wave = wave
	hud.show_banner("WAVE %d" % wave, "WAR MACHINE INCOMING" if boss_wave else "")
	sfx.play("wave")


func _spawn(kind: Rig.Kind) -> void:
	var gate: Vector3 = arena.gate_away_from(player.global_position)
	var at := gate + Vector3(randf_range(-1.0, 1.0), 0.05, randf_range(-1.0, 1.0))
	var e: CharacterBody3D = Enemy.new()
	# Robots get tougher slowly; there are also more of them each wave.
	e.setup(kind, 1.0 + (wave - 1) * 0.07, player, arena, fx)
	e.position = at
	e.destroyed.connect(_on_enemy_destroyed)
	# What a robot does is heard from where it stands.
	e.sound.connect(func(sound_name: String, pitch: float, volume_db: float):
		sfx.play_at(sound_name, e.global_position + Vector3(0, 1.2, 0), pitch, volume_db))
	world.add_child(e)
	fx.beam(at)
	if kind == Rig.Kind.BOSS:
		hud.boss = e


func _on_enemy_destroyed(e: Node3D) -> void:
	score += e.score
	hud.score = score
	hud.add_popup("+%d" % e.score, C_ORANGE)
	# Now and then a robot drops a repair kit: it is used at once.
	if player and not player.dead and player.hp < player.max_hp * 0.6 and randf() < 0.25:
		player.heal(20.0)
		hud.add_popup("+20 armour", Color("4cd964"))
		sfx.play("pickup")


func _alive() -> int:
	return get_tree().get_nodes_in_group("enemies").size()


func _process(dt: float) -> void:
	match state:
		State.MENU:
			# The camera drifts a little way round the trooper, who stands to
			# the right of the screen, clear of the menu on the left.
			_menu_angle += dt * 0.22
			var swing := sin(_menu_angle) * 0.2
			var out := Vector3(sin(swing), 0, -cos(swing))
			var eye := out * 3.9 + Vector3(0, 1.25, 0)
			var right := (-out).cross(Vector3.UP)
			_menu_cam.look_at_from_position(eye, Vector3(0, 1.02, 0) - right * 0.42)
			if _model:
				_model.speed = 0.0
		State.PLAYING:
			_run_wave(dt)
		State.OVER:
			if _over_wait > 0.0:
				_over_wait -= dt
				if _over_wait <= 0.0:
					_show_game_over()


func _run_wave(dt: float) -> void:
	hud.robots_left = _alive() + _to_spawn.size()
	if _wave_gap > 0.0:
		_wave_gap -= dt
		if _wave_gap <= 0.0:
			_start_wave()
		return
	if not _to_spawn.is_empty():
		_spawn_wait -= dt
		if _spawn_wait <= 0.0 and _alive() < MAX_ALIVE:
			_spawn(_to_spawn.pop_front())
			_spawn_wait = maxf(0.35, 1.1 - wave * 0.05)
	elif _alive() == 0 and wave > 0:
		_wave_cleared()


func _wave_cleared() -> void:
	sfx.play("clear")
	score += wave * 50
	hud.score = score
	state = State.UPGRADE
	hud.set_active(false)
	hud.show_banner("WAVE CLEAR", "+%d bonus" % (wave * 50), 1.4)
	await get_tree().create_timer(1.1).timeout
	if state == State.UPGRADE:
		_show_upgrades()


func _apply_upgrade(id: String) -> void:
	match id:
		"damage":
			player.up_damage *= 1.25
		"rate":
			player.up_rate *= 1.18
		"mag":
			player.up_mag += 12
			player.up_reload *= 0.85
		"armour":
			player.max_hp += 30.0
			player.hp = player.max_hp
		"regen":
			player.regen *= 1.6
		"speed":
			player.move_speed *= 1.12
			player.dash_cooldown *= 0.8
		"grenade":
			player.grenade_damage *= 1.35
			player.grenade_cooldown *= 0.8
	# Weapon upgrades are multipliers, so the weapon has to be taken up again.
	if id in ["damage", "rate", "mag"]:
		player.equip(player.slot, true)
	sfx.play("upgrade")
	_close_panel()
	get_tree().paused = false
	state = State.PLAYING
	hud.set_active(true)
	_wave_gap = 0.8


func _toggle_pause() -> void:
	if state == State.PLAYING:
		state = State.PAUSED
		get_tree().paused = true
		hud.set_active(false)
		_show_pause()
	elif state == State.PAUSED:
		_close_panel()
		get_tree().paused = false
		state = State.PLAYING
		hud.set_active(true)


# ---------------------------------------------------------------- menus

func _close_panel() -> void:
	_test += 1
	if panel:
		panel.queue_free()
		panel = null


## Puts a screen (the lobby or the armory) up as the current panel.
func _show_screen(screen: Control) -> void:
	_close_panel()
	screen.process_mode = Node.PROCESS_MODE_ALWAYS
	panel = screen
	ui.add_child(screen)
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE


## A sheet in the middle of a dimmed screen, for pausing, upgrades and the
## end of a run. Returns the column to fill in.
func _open_sheet(width: float, accent: Color, dim := 0.6) -> VBoxContainer:
	_close_panel()
	var shade := ColorRect.new()
	shade.color = Color(UI.INK, dim)
	shade.set_anchors_preset(Control.PRESET_FULL_RECT)
	shade.process_mode = Node.PROCESS_MODE_ALWAYS
	panel = shade
	ui.add_child(shade)
	var centre := CenterContainer.new()
	centre.set_anchors_preset(Control.PRESET_FULL_RECT)
	shade.add_child(centre)
	# The plate fills the frame; the column sits inside it with room round it.
	var frame := MarginContainer.new()
	centre.add_child(frame)
	if width > 0.0:
		var plate := Plate.new()
		plate.accent = accent
		plate.cut = 24.0
		frame.add_child(plate)
		frame.custom_minimum_size.x = width
	var inside := MarginContainer.new()
	for side in ["left", "right"]:
		inside.add_theme_constant_override("margin_" + side, 36)
	inside.add_theme_constant_override("margin_top", 28)
	inside.add_theme_constant_override("margin_bottom", 34)
	frame.add_child(inside)
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 14)
	col.alignment = BoxContainer.ALIGNMENT_CENTER
	inside.add_child(col)
	shade.modulate.a = 0.0
	shade.create_tween().tween_property(shade, "modulate:a", 1.0, 0.2)
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	return col


func _centred(text: String, size: int, color := UI.TEXT, kind := "bold", spacing := 0) -> Label:
	var l := UI.label(text, size, color, kind, spacing)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	return l


func _wide_button(text: String, primary := false, color := UI.ORANGE) -> BaseButton:
	var b := TechButton.new()
	b.text = text
	b.primary = primary
	b.align = HORIZONTAL_ALIGNMENT_CENTER
	b.custom_minimum_size = Vector2(380, 76 if primary else 62)
	b.font_size = 32 if primary else 24
	if primary:
		b.color = color
	return b


## SOUND and AUTO FIRE, side by side.
func _switches(col: VBoxContainer) -> void:
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("separation", 10)
	col.add_child(row)
	var make := func(title: String, on: bool) -> BaseButton:
		var b := TechButton.new()
		b.text = title
		b.font_size = 19
		b.pad = 14.0
		b.tag_width = 46.0
		b.custom_minimum_size = Vector2(185, 54)
		return b
	var paint := func(b: BaseButton, on: bool):
		b.tag = "ON" if on else "OFF"
		b.tag_color = UI.GREEN if on else UI.DIM
		b.accent = UI.GREEN if on else UI.LINE.lightened(0.3)
	var sound: BaseButton = make.call("SOUND", sound_on)
	paint.call(sound, sound_on)
	sound.pressed.connect(func():
		_set_sound(not sound_on)
		paint.call(sound, sound_on))
	row.add_child(sound)
	var auto: BaseButton = make.call("AUTO FIRE", auto_fire)
	paint.call(auto, auto_fire)
	auto.pressed.connect(func():
		_set_auto_fire(not auto_fire)
		paint.call(auto, auto_fire))
	row.add_child(auto)


func _set_sound(on: bool) -> void:
	sound_on = on
	sfx.enabled = on
	_save()


func _set_auto_fire(on: bool) -> void:
	auto_fire = on
	if player:
		player.auto_fire = on
	_save()


func _show_menu() -> void:
	state = State.MENU
	get_tree().paused = false
	_clear_run()
	hud.set_active(false)
	_menu_cam.make_current()
	_show_model()
	var lobby := Lobby.new()
	lobby.loadout = loadout.duplicate()
	lobby.skin = skin
	lobby.best_wave = best_wave
	lobby.best_score = best_score
	lobby.sound_on = sound_on
	lobby.auto_fire = auto_fire
	lobby.touch = hud.touch
	lobby.deploy.connect(start_game)
	lobby.open_armory.connect(_show_weapons)
	lobby.skin_changed.connect(func(index: int):
		skin = index
		_show_model()
		_save())
	lobby.sound_toggled.connect(_set_sound)
	lobby.auto_fire_toggled.connect(_set_auto_fire)
	_show_screen(lobby)


func _show_weapons(active_slot := 0) -> void:
	var armory := Armory.new()
	armory.loadout = loadout.duplicate()
	armory.slot = active_slot
	armory.closed.connect(_show_menu)
	armory.loadout_changed.connect(func(chosen: Array):
		for i in loadout.size():
			loadout[i] = str(chosen[i])
		_save()
		sfx.play("swap", 1.0, -4.0)
		if _model:
			_model.set_weapon(Weapons.by_id(loadout[0])))
	armory.test_fire.connect(_test_fire)
	_show_screen(armory)


## Plays a weapon's shots as they will sound in the fight: a short burst
## at its own rate for an automatic, two shots for the rest.
func _test_fire(w: Dictionary) -> void:
	_test += 1
	var mine := _test
	var shots := 7 if w.auto else 2
	for i in shots:
		if mine != _test:
			return
		sfx.play(Sfx.shot_name(w), randf_range(0.97, 1.03), -3.0)
		await get_tree().create_timer(float(w.interval) if w.auto else maxf(float(w.interval), 0.5)).timeout


func _show_pause() -> void:
	var col := _open_sheet(470.0, UI.CYAN)
	col.add_child(_centred("PAUSED", 46, UI.TEXT, "title"))
	col.add_child(_centred("WAVE %d   -   %s POINTS" % [wave, UI.thousands(score)], 18, UI.DIM, "bold", 2))
	var resume := _wide_button("RESUME", true)
	resume.pressed.connect(_toggle_pause)
	col.add_child(resume)
	_switches(col)
	var quit := _wide_button("QUIT TO LOBBY")
	quit.pressed.connect(_show_menu)
	col.add_child(quit)


func _show_game_over() -> void:
	var col := _open_sheet(640.0, UI.RED, 0.72)
	col.add_child(_centred("OUTPOST LOST", 52, UI.RED, "title"))
	var record: bool = wave >= best_wave and wave > 0
	col.add_child(_centred("NEW BEST RUN" if record else "THE MACHINES BROKE THROUGH", 18, UI.GOLD if record else UI.DIM, "bold", 3))
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("separation", 10)
	col.add_child(row)
	var figures := [
		["WAVE", str(wave), UI.CYAN],
		["SCORE", UI.thousands(score), UI.ORANGE],
		["DESTROYED", str(player.kills if player else 0), UI.TEXT],
		["BEST WAVE", str(best_wave), UI.GOLD],
	]
	for figure: Array in figures:
		var plate := Plate.new()
		plate.custom_minimum_size = Vector2(134, 78)
		plate.cut = 12.0
		plate.accent = figure[2]
		var inner := VBoxContainer.new()
		inner.set_anchors_preset(Control.PRESET_FULL_RECT)
		inner.alignment = BoxContainer.ALIGNMENT_CENTER
		inner.add_theme_constant_override("separation", -4)
		plate.add_child(inner)
		inner.add_child(_centred(figure[0], 14, UI.DIM, "bold", 2))
		inner.add_child(_centred(figure[1], 32, figure[2]))
		row.add_child(plate)
	var again := _wide_button("PLAY AGAIN", true)
	again.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	again.pressed.connect(start_game)
	col.add_child(again)
	var menu := _wide_button("LOBBY")
	menu.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	menu.pressed.connect(_show_menu)
	col.add_child(menu)


func _show_upgrades() -> void:
	get_tree().paused = true
	var col := _open_sheet(0.0, UI.CYAN, 0.66)
	col.add_theme_constant_override("separation", 8)
	col.add_child(_centred("WAVE %d CLEARED" % wave, 18, UI.CYAN, "bold", 4))
	col.add_child(_centred("CHOOSE AN UPGRADE", 44, UI.TEXT, "title"))
	col.add_child(Control.new())
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("separation", 20)
	col.add_child(row)
	var ids: Array = UPGRADES.keys()
	ids.shuffle()
	for i in 3:
		var id: String = ids[i]
		var info: Array = UPGRADES[id]
		var card := UpgradeCard.new()
		card.title = info[0]
		card.text = info[1]
		card.color = info[2]
		card.symbol = id
		card.pressed.connect(_apply_upgrade.bind(id))
		row.add_child(card)
		# Cards drop in one after the other.
		card.modulate.a = 0.0
		var tw := card.create_tween()
		tw.tween_interval(0.09 * i)
		tw.tween_property(card, "modulate:a", 1.0, 0.22)
