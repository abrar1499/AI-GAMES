extends Node
## Draw Guard: menus, progress and the flow between levels.
## The level itself lives in game.gd; every screen here is built in code.

const Game := preload("res://game.gd")
const Egg := preload("res://egg.gd")
const Levels := preload("res://levels.gd")
const Sfx := preload("res://sfx.gd")
const Ads := preload("res://ads.gd")

const SAVE_PATH := "user://draw_guard.cfg"
const HINT_COST := 40
const SKIP_AFTER_FAILS := 3
const DAILY := [20, 30, 40, 50, 60, 80, 150]
const LEVELS_PER_CHAPTER := 5

const C_INK := Color("2b2d42")
const C_GREEN := Color("4caf50")
const C_BLUE := Color("3d8bfd")
const C_ORANGE := Color("ff8a3d")
const C_PURPLE := Color("8e6fd8")
const C_GREY := Color("8a93a6")
const C_GOLD := Color("ffc93c")
const C_PANEL := Color("fffaf0")

var levels: Array = []
var stars: Array = []
var unlocked := 0
var coins := 0
var skin := 0
var owned: Array = [0]
var daily_day := 0
var daily_streak := 0
var wins_since_ad := 0
var sound_on := true

var level := 0
var fails := 0
var want_hint := false

var world: Node2D
var game: Node2D
var ui: CanvasLayer
var screen: Control
var popup: Control
var sfx: Node
var ads: CanvasLayer

var _ink_bar: InkBar
var _time_label: Label
var _tip_label: Label
var _coin_labels: Array = []


func _ready() -> void:
	levels = Levels.all()
	stars.resize(levels.size())
	stars.fill(0)
	_load()

	world = Node2D.new()
	add_child(world)
	ui = CanvasLayer.new()
	add_child(ui)
	sfx = Sfx.new()
	sfx.enabled = sound_on
	add_child(sfx)
	ads = Ads.new()
	add_child(ads)

	get_tree().quit_on_go_back = false
	get_viewport().size_changed.connect(_layout)
	_layout()
	show_home()
	if _daily_state().can:
		_show_daily()


func _notification(what: int) -> void:
	if what == NOTIFICATION_WM_GO_BACK_REQUEST:
		if popup:
			_close_popup()
		elif game and not game.demo:
			show_home()
		else:
			get_tree().quit()


func _layout() -> void:
	# The level is 720 wide: centre it, and keep its top at the top of the screen.
	var size := get_viewport().get_visible_rect().size
	world.position = Vector2((size.x - Game.W) * 0.5, 0.0)


func _process(_dt: float) -> void:
	if game and not game.demo and _time_label:
		if game.phase == Game.Phase.RUN:
			var left: float = game.time_left()
			_time_label.text = "%.1f" % left
			_time_label.visible = true
			# The countdown thumps once a second.
			_time_label.pivot_offset = _time_label.size * 0.5
			_time_label.scale = Vector2.ONE * (1.0 + 0.25 * pow(fposmod(left, 1.0), 4.0))
		else:
			_time_label.visible = false


# ---------------------------------------------------------------- saving

func _load() -> void:
	var cfg := ConfigFile.new()
	if cfg.load(SAVE_PATH) != OK:
		return
	var saved: Array = cfg.get_value("progress", "stars", [])
	for i in mini(saved.size(), stars.size()):
		stars[i] = int(saved[i])
	unlocked = clampi(cfg.get_value("progress", "unlocked", 0), 0, levels.size() - 1)
	coins = cfg.get_value("player", "coins", 0)
	owned = cfg.get_value("player", "owned", [0])
	skin = cfg.get_value("player", "skin", 0)
	sound_on = cfg.get_value("player", "sound", true)
	daily_day = cfg.get_value("daily", "day", 0)
	daily_streak = cfg.get_value("daily", "streak", 0)
	wins_since_ad = cfg.get_value("ads", "wins", 0)


func _save() -> void:
	var cfg := ConfigFile.new()
	cfg.set_value("progress", "stars", stars)
	cfg.set_value("progress", "unlocked", unlocked)
	cfg.set_value("player", "coins", coins)
	cfg.set_value("player", "owned", owned)
	cfg.set_value("player", "skin", skin)
	cfg.set_value("player", "sound", sound_on)
	cfg.set_value("daily", "day", daily_day)
	cfg.set_value("daily", "streak", daily_streak)
	cfg.set_value("ads", "wins", wins_since_ad)
	cfg.save(SAVE_PATH)


func _add_coins(amount: int) -> void:
	coins += amount
	_save()
	for l: Label in _coin_labels:
		if is_instance_valid(l):
			l.text = str(coins)


# ---------------------------------------------------------------- UI parts

func _box(color: Color, radius := 22, lip := 0) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.set_corner_radius_all(radius)
	s.border_color = color.darkened(0.28)
	s.border_width_bottom = lip
	s.content_margin_left = 22
	s.content_margin_right = 22
	s.content_margin_top = 10
	s.content_margin_bottom = 10 + lip
	s.anti_aliasing_size = 1.5
	return s


func _label(text: String, size: int, color := Color.WHITE, outline := 0) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	if outline > 0:
		l.add_theme_constant_override("outline_size", outline)
		l.add_theme_color_override("font_outline_color", C_INK)
	return l


func _button(text: String, color: Color, min_size := Vector2(320, 92), font := 34) -> Button:
	var b := Button.new()
	b.text = text
	b.custom_minimum_size = min_size
	b.focus_mode = Control.FOCUS_NONE
	b.add_theme_font_size_override("font_size", font)
	for c in ["font_color", "font_hover_color", "font_pressed_color", "font_focus_color"]:
		b.add_theme_color_override(c, Color.WHITE)
	b.add_theme_color_override("font_disabled_color", Color(1, 1, 1, 0.75))
	b.add_theme_stylebox_override("normal", _box(color, 22, 7))
	b.add_theme_stylebox_override("hover", _box(color.lightened(0.06), 22, 7))
	var down := _box(color.darkened(0.08), 22, 2)
	down.content_margin_top = 15
	b.add_theme_stylebox_override("pressed", down)
	b.add_theme_stylebox_override("disabled", _box(Color("b4bac6"), 22, 7))
	b.pressed.connect(func(): sfx.play("click"))
	# Buttons squeeze a little under the finger.
	b.resized.connect(func(): b.pivot_offset = b.size * 0.5)
	b.button_down.connect(func(): create_tween().tween_property(b, "scale", Vector2(0.93, 0.93), 0.06))
	b.button_up.connect(func():
		create_tween().tween_property(b, "scale", Vector2.ONE, 0.25).set_trans(Tween.TRANS_ELASTIC).set_ease(Tween.EASE_OUT))
	return b


## Makes a control gently grow and shrink for ever, to draw the eye.
func _pulse(c: Control, amount := 0.05, seconds := 0.7) -> void:
	c.resized.connect(func(): c.pivot_offset = c.size * 0.5)
	var tw := c.create_tween().set_loops()
	tw.tween_property(c, "scale", Vector2.ONE * (1.0 + amount), seconds).set_trans(Tween.TRANS_SINE)
	tw.tween_property(c, "scale", Vector2.ONE, seconds).set_trans(Tween.TRANS_SINE)


func _coin_pill() -> Control:
	var pill := PanelContainer.new()
	pill.add_theme_stylebox_override("panel", _box(Color(0, 0, 0, 0.35), 30))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	pill.add_child(row)
	var coin := CoinIcon.new()
	coin.custom_minimum_size = Vector2(36, 36)
	row.add_child(coin)
	var l := _label(str(coins), 32)
	row.add_child(l)
	_coin_labels.append(l)
	return pill


## Call before building a screen: forgets the widgets of the old one.
func _begin_screen() -> void:
	_close_popup()
	_coin_labels.clear()
	_ink_bar = null
	_time_label = null
	_tip_label = null


func _set_screen(c: Control) -> void:
	if screen:
		screen.queue_free()
	screen = c
	ui.add_child(c)
	c.modulate.a = 0.0
	c.create_tween().tween_property(c, "modulate:a", 1.0, 0.2)


func _full_rect() -> MarginContainer:
	var m := MarginContainer.new()
	m.set_anchors_preset(Control.PRESET_FULL_RECT)
	m.mouse_filter = Control.MOUSE_FILTER_IGNORE
	for side in ["left", "right"]:
		m.add_theme_constant_override("margin_" + side, 24)
	m.add_theme_constant_override("margin_top", 28)
	m.add_theme_constant_override("margin_bottom", 36)
	return m


func _vbox(gap := 16) -> VBoxContainer:
	var v := VBoxContainer.new()
	v.mouse_filter = Control.MOUSE_FILTER_IGNORE
	v.add_theme_constant_override("separation", gap)
	return v


func _hbox(gap := 16) -> HBoxContainer:
	var h := HBoxContainer.new()
	h.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_theme_constant_override("separation", gap)
	h.alignment = BoxContainer.ALIGNMENT_CENTER
	return h


func _spacer(height := 0.0) -> Control:
	var s := Control.new()
	s.mouse_filter = Control.MOUSE_FILTER_IGNORE
	if height > 0.0:
		s.custom_minimum_size.y = height
	else:
		s.size_flags_vertical = Control.SIZE_EXPAND_FILL
		s.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	return s


func _open_popup(title: String, title_color := C_INK) -> VBoxContainer:
	_close_popup()
	popup = ColorRect.new()
	(popup as ColorRect).color = Color(0.08, 0.09, 0.16, 0.6)
	popup.set_anchors_preset(Control.PRESET_FULL_RECT)
	ui.add_child(popup)
	var centre := CenterContainer.new()
	centre.set_anchors_preset(Control.PRESET_FULL_RECT)
	popup.add_child(centre)
	var panel := PanelContainer.new()
	var style := _box(C_PANEL, 34)
	style.content_margin_left = 36
	style.content_margin_right = 36
	style.content_margin_top = 30
	style.content_margin_bottom = 34
	style.border_color = C_INK
	style.set_border_width_all(5)
	panel.add_theme_stylebox_override("panel", style)
	panel.custom_minimum_size.x = 560
	centre.add_child(panel)
	var box := _vbox(18)
	panel.add_child(box)
	box.add_child(_label(title, 58, title_color))
	panel.pivot_offset = Vector2(280, 200)
	panel.scale = Vector2(0.8, 0.8)
	create_tween().tween_property(panel, "scale", Vector2.ONE, 0.22).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	return box


func _close_popup() -> void:
	if popup:
		popup.queue_free()
		popup = null


func _centered(c: Control) -> Control:
	c.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	return c


# ---------------------------------------------------------------- home

func _clear_game() -> void:
	if game:
		game.queue_free()
		game = null


func show_home() -> void:
	_begin_screen()
	_start_demo()

	var root := _full_rect()
	var col := _vbox(14)
	root.add_child(col)

	var top := _hbox()
	col.add_child(top)
	var sound := _button("Sound: on" if sound_on else "Sound: off", Color(0, 0, 0, 0.35), Vector2(190, 60), 26)
	sound.pressed.connect(func():
		sound_on = not sound_on
		sfx.enabled = sound_on
		sound.text = "Sound: on" if sound_on else "Sound: off"
		_save())
	top.add_child(sound)
	top.add_child(_spacer())
	top.add_child(_coin_pill())

	col.add_child(_spacer(70))
	var title := _label("DRAW\nGUARD", 118, Color.WHITE, 22)
	col.add_child(title)
	title.resized.connect(func(): title.pivot_offset = title.size * 0.5)
	var sway := title.create_tween().set_loops()
	sway.tween_property(title, "rotation", 0.035, 1.3).set_trans(Tween.TRANS_SINE)
	sway.tween_property(title, "rotation", -0.035, 1.3).set_trans(Tween.TRANS_SINE)
	col.add_child(_label("One line. Save the egg.", 36, Color.WHITE, 10))
	col.add_child(_spacer())

	var done: bool = unlocked >= levels.size() - 1 and stars[levels.size() - 1] > 0
	var play := _button("Play again" if done else "Play  -  Level %d" % (unlocked + 1), C_GREEN, Vector2(440, 116), 44)
	play.pressed.connect(func(): start_level(unlocked))
	col.add_child(_centered(play))
	_pulse(play)

	var row := _hbox(14)
	col.add_child(row)
	var b_levels := _button("Levels", C_BLUE, Vector2(210, 88), 32)
	b_levels.pressed.connect(show_levels)
	row.add_child(b_levels)
	var b_skins := _button("Eggs", C_PURPLE, Vector2(210, 88), 32)
	b_skins.pressed.connect(show_skins)
	row.add_child(b_skins)
	var b_daily := _button("Gift!" if _daily_state().can else "Gift", C_ORANGE, Vector2(210, 88), 32)
	b_daily.pressed.connect(_show_daily)
	row.add_child(b_daily)
	if _daily_state().can:
		_pulse(b_daily, 0.07, 0.4)
	_set_screen(root)


## The home screen plays a little level by itself, over and over.
func _start_demo() -> void:
	_clear_game()
	var floor_y := 880.0
	game = Game.new()
	game.setup({
		"demo": true,
		"eggs": [Vector2(360, floor_y - Levels.EGG)],
		"ground": [Levels.ground(floor_y)],
		"rocks": [{"t": 0.6, "x": 360, "r": 34}, {"t": 1.3, "x": 300, "r": 26}, {"t": 1.9, "x": 430, "r": 28}],
		"time": 3.8,
		"sol": Levels.arc(360, floor_y - 12, 80, 120, 30),
	}, skin, _chapter(unlocked))
	world.add_child(game)
	_play_demo(game)


func _play_demo(g: Node2D) -> void:
	var line: PackedVector2Array = g.data.sol
	await get_tree().create_timer(1.3).timeout
	for p in line:
		if not is_instance_valid(g) or g != game:
			return
		g._add_point(p)
		await get_tree().create_timer(0.03).timeout
	await get_tree().create_timer(0.3).timeout
	if not is_instance_valid(g) or g != game:
		return
	g._start_run()
	await g.finished
	await get_tree().create_timer(2.6).timeout
	if is_instance_valid(g) and g == game:
		_start_demo()


func _chapter(index: int) -> int:
	return int(index / float(LEVELS_PER_CHAPTER))


# ---------------------------------------------------------------- level list

func _header(title: String) -> HBoxContainer:
	var top := _hbox()
	var back := _button("Back", Color(0, 0, 0, 0.35), Vector2(130, 64), 28)
	back.pressed.connect(show_home)
	top.add_child(back)
	var l := _label(title, 46, Color.WHITE, 10)
	l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(l)
	top.add_child(_coin_pill())
	return top


func show_levels() -> void:
	_begin_screen()
	var root := _full_rect()
	var shade := ColorRect.new()
	shade.color = Color(0.1, 0.12, 0.22, 0.55)
	shade.set_anchors_preset(Control.PRESET_FULL_RECT)
	var holder := Control.new()
	holder.set_anchors_preset(Control.PRESET_FULL_RECT)
	holder.add_child(shade)
	holder.add_child(root)
	var col := _vbox(22)
	root.add_child(col)
	col.add_child(_header("Levels"))

	var total := 0
	for s in stars:
		total += int(s)
	col.add_child(_label("%d / %d stars" % [total, levels.size() * 3], 30, Color.WHITE, 8))

	var grid := GridContainer.new()
	grid.columns = 5
	grid.add_theme_constant_override("h_separation", 14)
	grid.add_theme_constant_override("v_separation", 14)
	col.add_child(_centered(grid))
	for i in levels.size():
		var open := i <= unlocked
		var hue := C_BLUE if stars[i] == 0 else C_GREEN
		var b := _button(str(i + 1), hue, Vector2(118, 132), 40)
		b.disabled = not open
		if open:
			var row := StarRow.new()
			row.count = stars[i]
			row.star_size = 13.0
			row.set_anchors_preset(Control.PRESET_BOTTOM_WIDE)
			row.offset_top = -46
			row.offset_bottom = -16
			row.mouse_filter = Control.MOUSE_FILTER_IGNORE
			b.add_child(row)
			b.add_theme_constant_override("outline_size", 0)
			var n := i
			b.pressed.connect(func(): start_level(n))
			# Lift the number so the stars fit underneath.
			for state in ["normal", "hover", "pressed"]:
				var sb: StyleBoxFlat = b.get_theme_stylebox(state).duplicate()
				sb.content_margin_bottom = 46
				b.add_theme_stylebox_override(state, sb)
		grid.add_child(b)
	col.add_child(_spacer())
	_set_screen(holder)


# ---------------------------------------------------------------- skins

func show_skins() -> void:
	_begin_screen()
	var holder := Control.new()
	holder.set_anchors_preset(Control.PRESET_FULL_RECT)
	var shade := ColorRect.new()
	shade.color = Color(0.1, 0.12, 0.22, 0.55)
	shade.set_anchors_preset(Control.PRESET_FULL_RECT)
	holder.add_child(shade)
	var root := _full_rect()
	holder.add_child(root)
	var col := _vbox(22)
	root.add_child(col)
	col.add_child(_header("Eggs"))

	var grid := GridContainer.new()
	grid.columns = 2
	grid.add_theme_constant_override("h_separation", 18)
	grid.add_theme_constant_override("v_separation", 18)
	col.add_child(_centered(grid))
	for i in Egg.SKINS.size():
		var info: Dictionary = Egg.SKINS[i]
		var card := PanelContainer.new()
		var style := _box(C_PANEL, 28)
		style.set_border_width_all(4)
		style.border_color = C_GOLD if i == skin else C_INK
		card.add_theme_stylebox_override("panel", style)
		card.custom_minimum_size = Vector2(310, 300)
		var box := _vbox(8)
		card.add_child(box)
		var icon := EggIcon.new()
		icon.skin = i
		icon.zoom = 1.9
		icon.custom_minimum_size = Vector2(200, 150)
		box.add_child(_centered(icon))
		box.add_child(_label(info.name, 30, C_INK))
		var b: Button
		if i == skin:
			b = _button("In use", C_GREY, Vector2(220, 70), 28)
			b.disabled = true
		elif owned.has(i):
			b = _button("Use", C_GREEN, Vector2(220, 70), 28)
			b.pressed.connect(func():
				skin = i
				_save()
				show_skins())
		else:
			b = _button("%d coins" % info.price, C_ORANGE, Vector2(220, 70), 28)
			b.disabled = coins < int(info.price)
			b.pressed.connect(func():
				owned.append(i)
				skin = i
				_add_coins(-int(info.price))
				sfx.play("coin")
				show_skins())
		box.add_child(_centered(b))
		grid.add_child(card)
	col.add_child(_spacer())
	_set_screen(holder)
	# Show the chosen egg in the scene behind the list.
	if game and game.demo and not game.eggs.is_empty():
		game.eggs[0].skin = skin


# ---------------------------------------------------------------- daily gift

func _today() -> int:
	var zone := Time.get_time_zone_from_system()
	return int((Time.get_unix_time_from_system() + float(zone.bias) * 60.0) / 86400.0)


## `can` says whether a gift is waiting; `index` is the day of the 7-day run.
func _daily_state() -> Dictionary:
	var today := _today()
	if daily_day == today:
		return {"can": false, "index": daily_streak - 1}
	var run := daily_streak if daily_day == today - 1 else 0
	if run >= DAILY.size():
		run = 0
	return {"can": true, "index": run}


func _show_daily() -> void:
	var state := _daily_state()
	var box := _open_popup("Daily gift")
	box.add_child(_label("Come back every day for a bigger gift.", 26, C_GREY.darkened(0.3)))
	var grid := GridContainer.new()
	grid.columns = 4
	grid.add_theme_constant_override("h_separation", 10)
	grid.add_theme_constant_override("v_separation", 10)
	box.add_child(_centered(grid))
	for i in DAILY.size():
		var cell := PanelContainer.new()
		var taken: bool = i < state.index or (i == state.index and not state.can)
		var is_today: bool = i == state.index and state.can
		var col := C_GREEN if taken else (C_ORANGE if is_today else Color("d9dde6"))
		cell.add_theme_stylebox_override("panel", _box(col, 16))
		cell.custom_minimum_size = Vector2(112, 104)
		var text := "Day %d\n%s" % [i + 1, "Done" if taken else "+%d" % DAILY[i]]
		cell.add_child(_label(text, 24, Color.WHITE if (taken or is_today) else C_INK))
		grid.add_child(cell)
	if state.can:
		var amount: int = DAILY[state.index]
		var claim := _button("Collect +%d coins" % amount, C_GREEN, Vector2(420, 96), 34)
		claim.pressed.connect(func():
			daily_streak = int(state.index) + 1
			daily_day = _today()
			_add_coins(amount)
			sfx.play("coin")
			_close_popup()
			if game and game.demo:
				show_home())
		box.add_child(_centered(claim))
	else:
		box.add_child(_label("Next gift tomorrow!", 30, C_INK))
		var ok := _button("OK", C_BLUE, Vector2(260, 84), 32)
		ok.pressed.connect(_close_popup)
		box.add_child(_centered(ok))


# ---------------------------------------------------------------- playing

func start_level(index: int) -> void:
	if index != level:
		fails = 0
	level = clampi(index, 0, levels.size() - 1)
	_begin_screen()
	_clear_game()
	var data: Dictionary = levels[level]
	game = Game.new()
	game.setup(data, skin, _chapter(level))
	game.ink_changed.connect(_on_ink_changed)
	game.run_started.connect(_on_run_started)
	game.finished.connect(_on_finished.bind(game))
	game.sound.connect(func(n: String): sfx.play(n))
	world.add_child(game)

	var root := _full_rect()
	root.add_theme_constant_override("margin_left", 16)
	root.add_theme_constant_override("margin_right", 16)
	root.add_theme_constant_override("margin_top", 20)
	var col := _vbox(10)
	root.add_child(col)

	var top := _hbox(10)
	col.add_child(top)
	var menu := _button("Menu", Color(0, 0, 0, 0.35), Vector2(120, 62), 26)
	menu.pressed.connect(show_home)
	top.add_child(menu)
	var title := _label("Level %d" % (level + 1), 44, Color.WHITE, 10)
	title.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(title)
	var hint := _button("Hint", C_ORANGE, Vector2(110, 62), 26)
	hint.pressed.connect(_ask_hint)
	top.add_child(hint)
	var retry := _button("Retry", C_BLUE, Vector2(120, 62), 26)
	retry.pressed.connect(func(): start_level(level))
	top.add_child(retry)

	_ink_bar = InkBar.new()
	_ink_bar.custom_minimum_size = Vector2(0, 74)
	_ink_bar.limit = game.ink_max
	_ink_bar.s3 = game.star3
	_ink_bar.s2 = game.star2
	col.add_child(_ink_bar)

	_time_label = _label("", 76, Color.WHITE, 16)
	_time_label.visible = false
	col.add_child(_time_label)

	col.add_child(_spacer())
	_tip_label = _label(data.get("tip", ""), 32, Color.WHITE, 10)
	col.add_child(_tip_label)
	_set_screen(root)

	if want_hint:
		want_hint = false
		game.show_hint()


func _on_ink_changed(used: float, _limit: float) -> void:
	if _ink_bar:
		_ink_bar.used = used
		_ink_bar.queue_redraw()


func _on_run_started() -> void:
	if _tip_label:
		_tip_label.visible = false


func _on_finished(is_win: bool, which: Node2D) -> void:
	if is_win:
		sfx.play("win")
	else:
		sfx.play("lose")
		Input.vibrate_handheld(120)
	await get_tree().create_timer(0.8 if is_win else 1.1).timeout
	# The player may have hit Retry or Menu while we waited.
	if which != game or not is_instance_valid(which):
		return
	if is_win:
		_show_win()
	else:
		fails += 1
		_show_fail()


func _show_win() -> void:
	var got: int = game.stars()
	var before: int = stars[level]
	var reward := 10 * maxi(0, got - before) + 5
	stars[level] = maxi(before, got)
	unlocked = maxi(unlocked, mini(level + 1, levels.size() - 1))
	wins_since_ad += 1
	fails = 0
	_add_coins(reward)

	var last := level >= levels.size() - 1
	var box := _open_popup("Saved!", C_GREEN.darkened(0.15))
	var row := StarRow.new()
	row.count = got
	row.star_size = 44.0
	row.custom_minimum_size = Vector2(380, 110)
	row.animate(sfx)
	box.add_child(_centered(row))
	var coin_row := _hbox(10)
	var coin := CoinIcon.new()
	coin.custom_minimum_size = Vector2(40, 40)
	coin_row.add_child(coin)
	var amount := _label("+0", 40, C_INK)
	coin_row.add_child(amount)
	var count := amount.create_tween()
	count.tween_interval(0.3 + 0.25 * got)
	count.tween_method(func(v: float): amount.text = "+%d" % int(v), 0.0, float(reward), 0.5)
	count.tween_callback(func(): sfx.play("coin"))
	box.add_child(coin_row)
	if got < 3:
		box.add_child(_label("Use less ink for 3 stars.", 26, C_GREY.darkened(0.3)))
	if last:
		box.add_child(_label("You saved every egg!\nMore levels are coming.", 30, C_INK))

	var next := _button("Levels" if last else "Next level", C_GREEN, Vector2(420, 100), 38)
	next.pressed.connect(show_levels if last else _next_level)
	box.add_child(_centered(next))
	var twice := _button("Double coins  (watch ad)", C_ORANGE, Vector2(420, 80), 28)
	twice.pressed.connect(func():
		ads.show_rewarded(func(earned: bool):
			if earned and is_instance_valid(twice):
				_add_coins(reward)
				sfx.play("coin")
				amount.text = "+%d" % (reward * 2)
				twice.disabled = true
				twice.text = "Doubled!"))
	box.add_child(_centered(twice))
	var again := _button("Play again", C_BLUE, Vector2(420, 80), 28)
	again.pressed.connect(func(): start_level(level))
	box.add_child(_centered(again))


func _next_level() -> void:
	var target := mini(level + 1, levels.size() - 1)
	# No full-screen ads in the first levels, then one every few wins.
	if level + 1 > Ads.FREE_LEVELS and wins_since_ad >= Ads.WINS_PER_AD:
		wins_since_ad = 0
		_save()
		_close_popup()
		ads.show_interstitial(func(): start_level(target))
	else:
		start_level(target)


func _show_fail() -> void:
	var box := _open_popup("Cracked!", Color("e5533d"))
	box.add_child(_label("Try another shape.", 28, C_GREY.darkened(0.3)))
	var again := _button("Try again", C_GREEN, Vector2(420, 100), 38)
	again.pressed.connect(func(): start_level(level))
	box.add_child(_centered(again))
	var hint := _button("Show hint  (watch ad)", C_ORANGE, Vector2(420, 80), 28)
	hint.pressed.connect(func(): ads.show_rewarded(func(earned: bool):
		if earned:
			_give_hint()))
	box.add_child(_centered(hint))
	if fails >= SKIP_AFTER_FAILS and level < levels.size() - 1:
		var skip := _button("Skip level  (watch ad)", C_PURPLE, Vector2(420, 80), 28)
		skip.pressed.connect(func(): ads.show_rewarded(func(earned: bool):
			if earned:
				unlocked = maxi(unlocked, level + 1)
				_save()
				start_level(level + 1)))
		box.add_child(_centered(skip))


func _ask_hint() -> void:
	var box := _open_popup("Need a hint?")
	box.add_child(_label("See one line that saves the egg.", 28, C_GREY.darkened(0.3)))
	var pay := _button("Use %d coins" % HINT_COST, C_GREEN, Vector2(420, 92), 34)
	pay.disabled = coins < HINT_COST
	pay.pressed.connect(func():
		_add_coins(-HINT_COST)
		_give_hint())
	box.add_child(_centered(pay))
	var watch := _button("Watch an ad  (free)", C_ORANGE, Vector2(420, 92), 34)
	watch.pressed.connect(func(): ads.show_rewarded(func(earned: bool):
		if earned:
			_give_hint()))
	box.add_child(_centered(watch))
	var no := _button("No thanks", C_GREY, Vector2(420, 76), 28)
	no.pressed.connect(_close_popup)
	box.add_child(_centered(no))


func _give_hint() -> void:
	want_hint = true
	start_level(level)


# ---------------------------------------------------------------- widgets

## Ink left, with the three stars sitting where each one is lost.
class InkBar extends Control:
	var used := 0.0
	var limit := 1.0
	var s3 := 0.0
	var s2 := 0.0

	func _draw() -> void:
		var bar := Rect2(8, size.y - 30, size.x - 16, 22)
		var back := StyleBoxFlat.new()
		back.bg_color = Color(1, 1, 1, 0.55)
		back.set_corner_radius_all(11)
		draw_style_box(back, bar)
		var left := clampf(1.0 - used / limit, 0.0, 1.0)
		if left > 0.01:
			var fill := StyleBoxFlat.new()
			fill.bg_color = Color("2b2d42")
			fill.set_corner_radius_all(9)
			fill.border_color = Color.WHITE
			fill.set_border_width_all(2)
			draw_style_box(fill, Rect2(bar.position, Vector2(maxf(22.0, bar.size.x * left), bar.size.y)))
		var marks := [[limit, true], [s2, used <= s2], [s3, used <= s3]]
		for m: Array in marks:
			var x := bar.position.x + bar.size.x * clampf(1.0 - float(m[0]) / limit, 0.0, 1.0)
			x = clampf(x, 20.0, size.x - 20.0)
			draw_line(Vector2(x, bar.position.y - 2), Vector2(x, bar.end.y + 2), Color(1, 1, 1, 0.8), 2.0)
			StarRow.draw_star(self, Vector2(x, bar.position.y - 20), 17.0, m[1])


class StarRow extends Control:
	static func star_points(centre: Vector2, r: float) -> PackedVector2Array:
		var p := PackedVector2Array()
		for i in 10:
			var radius := r if i % 2 == 0 else r * 0.46
			p.append(centre + Vector2.from_angle(-PI * 0.5 + TAU * i / 10.0) * radius)
		return p


	static func draw_star(ci: CanvasItem, centre: Vector2, r: float, lit: bool) -> void:
		var outer := star_points(centre, r)
		ci.draw_colored_polygon(outer, Color("ffc93c") if lit else Color(0.55, 0.58, 0.66, 0.55))
		outer.append(outer[0])
		ci.draw_polyline(outer, Color("a8741a") if lit else Color(0.3, 0.33, 0.42, 0.6), maxf(1.5, r * 0.11), true)

	var count := 0
	var star_size := 40.0
	var shown := 3.0

	func animate(sfx: Node) -> void:
		shown = 0.0
		var tw := create_tween()
		tw.tween_interval(0.2)
		for i in count:
			tw.tween_method(func(v: float):
				shown = v
				queue_redraw(), float(i), float(i + 1), 0.22)
			tw.tween_callback(func(): sfx.play("star", 1.0 + 0.12 * i))

	func _draw() -> void:
		var gap := star_size * 2.3
		for i in 3:
			var c := Vector2(size.x * 0.5 + (i - 1) * gap, size.y * 0.5 + (0.0 if i == 1 else star_size * 0.25))
			var lit := i < count
			var grow := clampf(shown - i, 0.0, 1.0) if lit else 1.0
			if lit and grow <= 0.0:
				draw_star(self, c, star_size, false)
			else:
				draw_star(self, c, star_size * (0.6 + 0.4 * grow) if lit else star_size, lit)


class EggIcon extends Control:
	var skin := 0
	var zoom := 1.0

	func _draw() -> void:
		draw_set_transform(size * 0.5, 0.0, Vector2(zoom, zoom))
		preload("res://egg.gd").draw_egg(self, skin)


class CoinIcon extends Control:
	func _draw() -> void:
		var c := size * 0.5
		var r := minf(size.x, size.y) * 0.5
		draw_circle(c, r, Color("c98a12"))
		draw_circle(c, r * 0.84, Color("ffc93c"))
		draw_circle(c, r * 0.55, Color("ffdf7e"))
		draw_circle(c + Vector2(-r * 0.3, -r * 0.3), r * 0.16, Color(1, 1, 1, 0.8))
