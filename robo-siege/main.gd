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

enum State { MENU, PLAYING, UPGRADE, PAUSED, OVER }

const SAVE_PATH := "user://robo_siege.cfg"
const MAX_ALIVE := 11
const BOSS_EVERY := 5

const C_ORANGE := Color("ff7a1a")
const C_PANEL := Color(0.07, 0.09, 0.14, 0.92)

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


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	_load()
	sfx = Sfx.new()
	sfx.enabled = sound_on
	add_child(sfx)

	world = Node3D.new()
	world.process_mode = Node.PROCESS_MODE_PAUSABLE
	add_child(world)
	arena = Arena.new()
	world.add_child(arena)
	fx = Fx.new()
	fx.sound.connect(_on_sound)
	world.add_child(fx)

	_menu_cam = Camera3D.new()
	_menu_cam.fov = 60.0
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
	if cfg.load(SAVE_PATH) != OK:
		auto_fire = DisplayServer.is_touchscreen_available()
		return
	best_wave = cfg.get_value("best", "wave", 0)
	best_score = cfg.get_value("best", "score", 0)
	sound_on = cfg.get_value("options", "sound", true)
	auto_fire = cfg.get_value("options", "auto_fire", DisplayServer.is_touchscreen_available())
	skin = clampi(cfg.get_value("options", "skin", 0), 0, Rig.LOOKS.size() - 1)


func _save() -> void:
	var cfg := ConfigFile.new()
	cfg.set_value("best", "wave", best_wave)
	cfg.set_value("best", "score", best_score)
	cfg.set_value("options", "sound", sound_on)
	cfg.set_value("options", "auto_fire", auto_fire)
	cfg.set_value("options", "skin", skin)
	cfg.save(SAVE_PATH)


# ---------------------------------------------------------------- starting and ending

## Puts the chosen trooper in the middle of the outpost for the menu.
func _show_model() -> void:
	if _model:
		_model.queue_free()
	_model = Rig.new()
	world.add_child(_model)
	_model.build(Rig.Kind.TROOPER, skin)
	_model.position = Vector3(-0.4, 0, 0)
	_model.rotation.y = -0.35
	_model.scale = Vector3.ONE * 0.6
	_model.create_tween().tween_property(_model, "scale", Vector3.ONE, 0.25).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)


func _clear_run() -> void:
	if _model:
		_model.queue_free()
		_model = null
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
	e.sound.connect(_on_sound)
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
			# The camera circles the empty outpost behind the menu.
			# The camera drifts round the chosen trooper, who stands to one side
			# of the buttons.
			_menu_angle += dt * 0.25
			var swing := sin(_menu_angle) * 0.45
			var at := Vector3(sin(swing) * 3.5, 1.6, -cos(swing) * 3.5)
			_menu_cam.look_at_from_position(at, Vector3(1.25, 1.1, 0))
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
			player.damage *= 1.25
		"rate":
			player.fire_interval /= 1.18
		"mag":
			player.mag_size += 12
			player.reload_time *= 0.85
			player.ammo = player.mag_size
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

func _box(color: Color, radius := 18, border := Color.TRANSPARENT) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.set_corner_radius_all(radius)
	s.content_margin_left = 26
	s.content_margin_right = 26
	s.content_margin_top = 14
	s.content_margin_bottom = 14
	if border.a > 0.0:
		s.border_color = border
		s.set_border_width_all(3)
	return s


func _label(text: String, size: int, color := Color.WHITE) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	l.add_theme_constant_override("outline_size", maxi(4, size / 6))
	l.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.7))
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	return l


func _button(text: String, color: Color, min_size := Vector2(340, 78), font := 30) -> Button:
	var b := Button.new()
	b.text = text
	b.custom_minimum_size = min_size
	b.focus_mode = Control.FOCUS_NONE
	b.add_theme_font_size_override("font_size", font)
	for c in ["font_color", "font_hover_color", "font_pressed_color"]:
		b.add_theme_color_override(c, Color.WHITE)
	b.add_theme_stylebox_override("normal", _box(color))
	b.add_theme_stylebox_override("hover", _box(color.lightened(0.1)))
	b.add_theme_stylebox_override("pressed", _box(color.darkened(0.15)))
	b.pressed.connect(func(): sfx.play("click"))
	b.resized.connect(func(): b.pivot_offset = b.size * 0.5)
	b.button_down.connect(func(): b.create_tween().tween_property(b, "scale", Vector2(0.94, 0.94), 0.06))
	b.button_up.connect(func(): b.create_tween().tween_property(b, "scale", Vector2.ONE, 0.2).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT))
	return b


func _close_panel() -> void:
	if panel:
		panel.queue_free()
		panel = null


## An empty screen-filling panel with a column in the middle to fill in.
func _open_panel(dim := 0.55) -> VBoxContainer:
	_close_panel()
	var shade := ColorRect.new()
	shade.color = Color(0.02, 0.03, 0.06, dim)
	shade.set_anchors_preset(Control.PRESET_FULL_RECT)
	shade.process_mode = Node.PROCESS_MODE_ALWAYS
	panel = shade
	ui.add_child(shade)
	var centre := CenterContainer.new()
	centre.set_anchors_preset(Control.PRESET_FULL_RECT)
	shade.add_child(centre)
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 16)
	col.alignment = BoxContainer.ALIGNMENT_CENTER
	centre.add_child(col)
	shade.modulate.a = 0.0
	shade.create_tween().tween_property(shade, "modulate:a", 1.0, 0.2)
	Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
	return col


func _centered(c: Control) -> Control:
	c.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	return c


func _option_buttons(col: VBoxContainer) -> void:
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("separation", 14)
	col.add_child(row)
	var grey := Color(0.25, 0.3, 0.4, 0.9)
	var sound := _button("Sound: on" if sound_on else "Sound: off", grey, Vector2(220, 58), 22)
	sound.pressed.connect(func():
		sound_on = not sound_on
		sfx.enabled = sound_on
		sound.text = "Sound: on" if sound_on else "Sound: off"
		_save())
	row.add_child(sound)
	var auto := _button("Auto fire: on" if auto_fire else "Auto fire: off", grey, Vector2(240, 58), 22)
	auto.pressed.connect(func():
		auto_fire = not auto_fire
		auto.text = "Auto fire: on" if auto_fire else "Auto fire: off"
		if player:
			player.auto_fire = auto_fire
		_save())
	row.add_child(auto)


func _show_menu() -> void:
	state = State.MENU
	get_tree().paused = false
	_clear_run()
	hud.set_active(false)
	_menu_cam.make_current()
	_show_model()
	var col := _open_panel(0.25)
	var title := _label("ROBO SIEGE", 110, Color.WHITE)
	col.add_child(title)
	col.add_child(_label("Hold the outpost. Break the machines.", 28, C_ORANGE))
	col.add_child(Control.new())
	var play := _button("PLAY", C_ORANGE, Vector2(380, 96), 44)
	play.pressed.connect(start_game)
	col.add_child(_centered(play))
	var pick := _button("Skin: %s" % Rig.LOOKS[skin].name, Color("3d6fb5"), Vector2(380, 66), 26)
	pick.pressed.connect(func():
		skin = (skin + 1) % Rig.LOOKS.size()
		pick.text = "Skin: %s" % Rig.LOOKS[skin].name
		_show_model()
		_save())
	col.add_child(_centered(pick))
	if best_wave > 0:
		col.add_child(_label("Best: wave %d   -   %d points" % [best_wave, best_score], 24, Color(1, 1, 1, 0.85)))
	_option_buttons(col)
	var how := "Left side: move    Right side: aim    FIRE, BOMB, DASH buttons" if hud.touch \
		else "WASD move   Mouse aim   Click fire   R reload   G grenade   Space dash"
	col.add_child(_label(how, 20, Color(1, 1, 1, 0.7)))
	# The PLAY button breathes to catch the eye.
	play.resized.connect(func(): play.pivot_offset = play.size * 0.5)
	var tw := play.create_tween().set_loops()
	tw.tween_property(play, "scale", Vector2.ONE * 1.05, 0.7).set_trans(Tween.TRANS_SINE)
	tw.tween_property(play, "scale", Vector2.ONE, 0.7).set_trans(Tween.TRANS_SINE)


func _show_pause() -> void:
	var col := _open_panel()
	col.add_child(_label("PAUSED", 72))
	var resume := _button("Resume", C_ORANGE)
	resume.pressed.connect(_toggle_pause)
	col.add_child(_centered(resume))
	_option_buttons(col)
	var quit := _button("Quit to menu", Color(0.25, 0.3, 0.4, 0.9))
	quit.pressed.connect(_show_menu)
	col.add_child(_centered(quit))


func _show_game_over() -> void:
	var col := _open_panel(0.7)
	col.add_child(_label("OUTPOST LOST", 84, Color("ff3b30")))
	col.add_child(_label("You reached wave %d" % wave, 34))
	col.add_child(_label("%d points   -   %d robots destroyed" % [score, player.kills if player else 0], 26, C_ORANGE))
	if wave >= best_wave and wave > 0:
		col.add_child(_label("New best!", 30, Color("ffd84a")))
	else:
		col.add_child(_label("Best: wave %d" % best_wave, 24, Color(1, 1, 1, 0.8)))
	var again := _button("Play again", C_ORANGE, Vector2(380, 90), 38)
	again.pressed.connect(start_game)
	col.add_child(_centered(again))
	var menu := _button("Menu", Color(0.25, 0.3, 0.4, 0.9))
	menu.pressed.connect(_show_menu)
	col.add_child(_centered(menu))


func _show_upgrades() -> void:
	get_tree().paused = true
	var col := _open_panel()
	col.add_child(_label("CHOOSE AN UPGRADE", 54))
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("separation", 22)
	col.add_child(row)
	var ids: Array = UPGRADES.keys()
	ids.shuffle()
	for i in 3:
		var id: String = ids[i]
		var info: Array = UPGRADES[id]
		var card := Button.new()
		card.custom_minimum_size = Vector2(300, 250)
		card.focus_mode = Control.FOCUS_NONE
		var tint: Color = info[2]
		card.add_theme_stylebox_override("normal", _box(C_PANEL, 22, tint))
		card.add_theme_stylebox_override("hover", _box(Color(0.12, 0.15, 0.22, 0.95), 22, tint.lightened(0.2)))
		card.add_theme_stylebox_override("pressed", _box(tint.darkened(0.5), 22, tint))
		var inner := VBoxContainer.new()
		inner.set_anchors_preset(Control.PRESET_FULL_RECT)
		inner.alignment = BoxContainer.ALIGNMENT_CENTER
		inner.add_theme_constant_override("separation", 14)
		inner.mouse_filter = Control.MOUSE_FILTER_IGNORE
		card.add_child(inner)
		var swatch := ColorRect.new()
		swatch.color = tint
		swatch.custom_minimum_size = Vector2(70, 10)
		swatch.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		swatch.mouse_filter = Control.MOUSE_FILTER_IGNORE
		inner.add_child(swatch)
		var name_label := _label(info[0], 32, tint)
		name_label.mouse_filter = Control.MOUSE_FILTER_IGNORE
		inner.add_child(name_label)
		var text := _label(info[1], 22, Color(1, 1, 1, 0.9))
		text.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		text.custom_minimum_size.x = 250
		text.mouse_filter = Control.MOUSE_FILTER_IGNORE
		inner.add_child(text)
		card.pressed.connect(_apply_upgrade.bind(id))
		row.add_child(card)
		# Cards drop in one after the other.
		card.modulate.a = 0.0
		var tw := card.create_tween()
		tw.tween_interval(0.08 * i)
		tw.tween_property(card, "modulate:a", 1.0, 0.2)
