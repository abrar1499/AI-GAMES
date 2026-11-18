extends Control
## The armory: every weapon in a list, the chosen one turning on a stand
## in the middle, and its figures on the right, set against the weapon it
## would replace.
##
## The trooper carries two. One of the two slots at the top is lit; a
## weapon is looked at by tapping it in the list, and goes into the lit
## slot with the button under the figures.

signal closed
signal loadout_changed(loadout: Array)
## Asks for the weapon's shot to be played, to hear it before choosing it.
signal test_fire(weapon: Dictionary)

const UI := preload("res://ui.gd")
const Plate := preload("res://ui/plate.gd")
const TechButton := preload("res://ui/tech_button.gd")
const Meter := preload("res://ui/meter.gd")
const WeaponView := preload("res://ui/weapon_view.gd")
const Weapons := preload("res://weapons.gd")

const SLOT_NAMES := ["PRIMARY", "SECONDARY"]
const SLOT_COLORS := [UI.ORANGE, UI.CYAN]

var loadout: Array[String] = ["rifle", "ak"]
## Which of the two slots a weapon would go into.
var slot := 0
## The weapon being looked at.
var looking := "rifle"

var _tabs: Array = []
var _rows := {}
var _badges := {}
var _view: Control
var _kind: Label
var _name: Label
var _blurb: Label
var _stat_values: Array = []
var _stat_meters: Array = []
var _versus: Label
var _equip: BaseButton
var _dps: Label
var _dps_change: Label


func _ready() -> void:
	# Fill the screen. (The plain anchors call would keep the present size, which is nothing.)
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	looking = loadout[slot]

	var back_shade := ColorRect.new()
	back_shade.color = Color(UI.INK, 0.9)
	back_shade.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(back_shade)
	var glow := UI.shade(Color(UI.CYAN, 0.10), Vector2(0.5, 1.0), Vector2(0.5, 0.35))
	glow.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(glow)

	_add_top_bar()
	_add_list()
	_add_showcase()
	_add_figures()
	_refresh(true)


# ---------------------------------------------------------------- building

func _add_top_bar() -> void:
	var back := TechButton.new()
	back.text = "<  BACK"
	back.font_size = 22
	back.align = HORIZONTAL_ALIGNMENT_CENTER
	back.custom_minimum_size = Vector2(150, 56)
	back.position = Vector2(32, 22)
	back.size = Vector2(150, 56)
	back.pressed.connect(func(): closed.emit())
	add_child(back)

	var title := UI.headline("ARMORY", 40)
	title.position = Vector2(206, 20)
	add_child(title)
	var note := UI.label("CHOOSE THE TWO WEAPONS YOU CARRY", 15, UI.DIM, "bold", 2)
	note.position = Vector2(208, 62)
	add_child(note)

	# The two slots, top right. The lit one is what EQUIP fills.
	var tabs := HBoxContainer.new()
	tabs.add_theme_constant_override("separation", 10)
	tabs.alignment = BoxContainer.ALIGNMENT_END
	add_child(tabs)
	tabs.set_anchors_preset(Control.PRESET_TOP_RIGHT)
	tabs.offset_left = -32 - 540
	tabs.offset_right = -32
	tabs.offset_top = 20
	tabs.offset_bottom = 84
	for i in loadout.size():
		var tab := TechButton.new()
		tab.custom_minimum_size = Vector2(264, 62)
		tab.font_size = 22
		tab.sub_size = 15
		tab.accent = SLOT_COLORS[i]
		tab.pressed.connect(func():
			slot = i
			looking = loadout[i]
			_refresh())
		tabs.add_child(tab)
		_tabs.append(tab)


func _add_list() -> void:
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 8)
	add_child(col)
	col.set_anchors_preset(Control.PRESET_LEFT_WIDE)
	col.offset_left = 32
	col.offset_right = 362
	col.offset_top = 100
	col.offset_bottom = -18
	for n in Weapons.LIST.size():
		var w: Dictionary = Weapons.LIST[n]
		# A tall card: the weapon's picture across the top, its name under it.
		var row := TechButton.new()
		row.custom_minimum_size = Vector2(330, 120)
		row.size_flags_vertical = Control.SIZE_EXPAND_FILL
		# The card draws no words of its own; this is its name for the tests.
		row.set_meta("title", w.name)
		row.cut = 14.0
		row.pressed.connect(func():
			looking = w.id
			_refresh())
		col.add_child(row)
		var icon := WeaponView.new()
		row.add_child(icon)
		icon.set_anchors_preset(Control.PRESET_FULL_RECT)
		icon.offset_left = 22
		icon.offset_right = -22
		icon.offset_top = 12
		icon.offset_bottom = -46
		icon.show_weapon(w)
		var weapon_name := UI.label(w.name, 22, UI.TEXT, "bold", 1)
		row.add_child(weapon_name)
		weapon_name.set_anchors_preset(Control.PRESET_BOTTOM_LEFT)
		weapon_name.offset_left = 20
		weapon_name.offset_right = 230
		weapon_name.offset_top = -40
		weapon_name.offset_bottom = -12
		var kind := UI.label(w["class"], 14, UI.DIM, "bold", 1)
		kind.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
		row.add_child(kind)
		kind.set_anchors_preset(Control.PRESET_BOTTOM_RIGHT)
		kind.offset_left = -170
		kind.offset_right = -18
		kind.offset_top = -34
		kind.offset_bottom = -12
		# Which slot carries it, if either: a tag in the card's top corner.
		var badge := UI.label("", 14, UI.ORANGE, "bold", 2)
		badge.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
		row.add_child(badge)
		badge.set_anchors_preset(Control.PRESET_TOP_RIGHT)
		badge.offset_left = -150
		badge.offset_right = -16
		badge.offset_top = 8
		badge.offset_bottom = 28
		_rows[w.id] = row
		_badges[w.id] = badge
		UI.enter(row, Vector2(-50, 0), 0.04 * n, 0.3)


func _add_showcase() -> void:
	var plate := Plate.new()
	plate.accent = UI.CYAN
	plate.cut = 22.0
	add_child(plate)
	plate.set_anchors_preset(Control.PRESET_FULL_RECT)
	plate.offset_left = 378
	plate.offset_right = -372
	plate.offset_top = 100
	plate.offset_bottom = -18

	_view = WeaponView.new()
	_view.live = true
	plate.add_child(_view)
	_view.set_anchors_preset(Control.PRESET_FULL_RECT)
	_view.offset_left = 6
	_view.offset_right = -6
	_view.offset_top = 96
	_view.offset_bottom = -64

	_kind = UI.label("", 16, UI.CYAN, "bold", 3)
	_kind.position = Vector2(26, 16)
	plate.add_child(_kind)
	_name = UI.headline("", 40)
	_name.position = Vector2(24, 34)
	plate.add_child(_name)
	_blurb = UI.label("", 18, UI.DIM, "semi")
	_blurb.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	plate.add_child(_blurb)
	_blurb.set_anchors_preset(Control.PRESET_TOP_WIDE)
	_blurb.offset_left = 26
	_blurb.offset_right = -24
	_blurb.offset_top = 84
	_blurb.offset_bottom = 132

	var fire := TechButton.new()
	fire.text = "TEST FIRE"
	fire.sub = "hear it"
	fire.font_size = 20
	fire.sub_size = 14
	fire.accent = UI.ORANGE
	plate.add_child(fire)
	fire.set_anchors_preset(Control.PRESET_BOTTOM_LEFT)
	fire.offset_left = 18
	fire.offset_right = 198
	fire.offset_top = -72
	fire.offset_bottom = -16
	fire.pressed.connect(func(): test_fire.emit(Weapons.by_id(looking)))

	var hint := UI.label("DRAG TO TURN", 14, Color(UI.DIM, 0.8), "bold", 3)
	hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	plate.add_child(hint)
	hint.set_anchors_preset(Control.PRESET_BOTTOM_RIGHT)
	hint.offset_left = -220
	hint.offset_right = -24
	hint.offset_top = -42
	hint.offset_bottom = -18
	UI.enter(plate, Vector2(0, 30), 0.05)


func _add_figures() -> void:
	var plate := Plate.new()
	plate.accent = UI.ORANGE
	plate.cut = 18.0
	add_child(plate)
	plate.set_anchors_preset(Control.PRESET_RIGHT_WIDE)
	plate.offset_left = -356
	plate.offset_right = -32
	plate.offset_top = 100
	plate.offset_bottom = -18

	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 11)
	plate.add_child(col)
	col.set_anchors_preset(Control.PRESET_FULL_RECT)
	col.offset_left = 22
	col.offset_right = -22
	col.offset_top = 18
	col.offset_bottom = -104
	col.add_child(UI.section("PERFORMANCE", UI.ORANGE))

	# The one figure that sums a weapon up, in large type.
	var head := HBoxContainer.new()
	head.add_theme_constant_override("separation", 10)
	head.mouse_filter = Control.MOUSE_FILTER_IGNORE
	col.add_child(head)
	_dps = UI.label("", 50, UI.TEXT, "bold")
	head.add_child(_dps)
	var words := VBoxContainer.new()
	words.add_theme_constant_override("separation", -5)
	words.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	words.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	words.mouse_filter = Control.MOUSE_FILTER_IGNORE
	head.add_child(words)
	words.add_child(UI.label("DAMAGE", 15, UI.DIM, "bold", 2))
	words.add_child(UI.label("PER SECOND", 15, UI.DIM, "bold", 2))
	_dps_change = UI.label("", 24, UI.GREEN, "bold")
	_dps_change.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	head.add_child(_dps_change)

	for stat: Dictionary in Weapons.stats(Weapons.LIST[0]):
		var block := VBoxContainer.new()
		block.add_theme_constant_override("separation", 1)
		block.mouse_filter = Control.MOUSE_FILTER_IGNORE
		col.add_child(block)
		var line := HBoxContainer.new()
		line.mouse_filter = Control.MOUSE_FILTER_IGNORE
		block.add_child(line)
		var title := UI.label(stat.name, 16, UI.DIM, "bold", 2)
		title.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		line.add_child(title)
		var value := UI.label("", 24, UI.TEXT, "bold")
		line.add_child(value)
		_stat_values.append(value)
		var meter := Meter.new()
		meter.custom_minimum_size = Vector2(280, 13)
		block.add_child(meter)
		_stat_meters.append(meter)

	_versus = UI.label("", 15, UI.DIM, "semi", 1)
	_versus.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	col.add_child(_versus)

	_equip = TechButton.new()
	_equip.primary = true
	_equip.color = UI.ORANGE
	_equip.font_size = 26
	_equip.sub_size = 15
	_equip.cut = 16.0
	_equip.align = HORIZONTAL_ALIGNMENT_CENTER
	plate.add_child(_equip)
	_equip.set_anchors_preset(Control.PRESET_BOTTOM_WIDE)
	_equip.offset_left = 18
	_equip.offset_right = -18
	_equip.offset_top = -92
	_equip.offset_bottom = -18
	_equip.pressed.connect(_on_equip)
	UI.enter(plate, Vector2(50, 0), 0.1)


# ---------------------------------------------------------------- behaviour

## Puts the weapon being looked at into the lit slot. A weapon is only
## carried once: if it is in the other slot, the two change places.
func _on_equip() -> void:
	if loadout[slot] == looking:
		return
	var other := 1 - slot
	if loadout[other] == looking:
		loadout[other] = loadout[slot]
	loadout[slot] = looking
	loadout_changed.emit(loadout)
	_refresh()


## Brings every part of the screen in line with `slot` and `looking`.
func _refresh(at_once := false) -> void:
	var w := Weapons.by_id(looking)
	var mine := Weapons.by_id(loadout[slot])
	var accent: Color = SLOT_COLORS[slot]

	for i in _tabs.size():
		var tab: BaseButton = _tabs[i]
		tab.text = SLOT_NAMES[i]
		tab.sub = Weapons.by_id(loadout[i]).name
		tab.selected = i == slot

	for id: String in _rows:
		var row: BaseButton = _rows[id]
		row.selected = id == looking
		var held := loadout.find(id)
		row.accent = SLOT_COLORS[held] if held >= 0 else UI.CYAN
		var badge: Label = _badges[id]
		badge.text = SLOT_NAMES[held] if held >= 0 else ""
		if held >= 0:
			badge.add_theme_color_override("font_color", SLOT_COLORS[held])

	if _view._spec.get("id", "") != w.id:
		_view.show_weapon(w)
	_kind.text = w["class"]
	_name.text = w.name
	_blurb.text = w.blurb

	var figures := Weapons.stats(w)
	var theirs := Weapons.stats(mine)
	var comparing: bool = w.id != mine.id
	_dps.text = str(int(round(Weapons.dps(w))))
	var gain := int(round(Weapons.dps(w) - Weapons.dps(mine)))
	_dps_change.text = "" if not comparing or gain == 0 else "%+d" % gain
	_dps_change.add_theme_color_override("font_color", UI.GREEN if gain > 0 else UI.RED)
	for i in figures.size():
		(_stat_values[i] as Label).text = figures[i].text
		var meter = _stat_meters[i]
		meter.compare = float(theirs[i].value) if comparing else -1.0
		meter.set_value(float(figures[i].value), at_once)
		var better: float = float(figures[i].value) - float(theirs[i].value)
		var paint := UI.TEXT
		if comparing and better > 0.02:
			paint = UI.GREEN
		elif comparing and better < -0.02:
			paint = UI.RED
		(_stat_values[i] as Label).add_theme_color_override("font_color", paint)
	_versus.text = "Against your %s, the %s. Green is better, red is worse." % [SLOT_NAMES[slot].to_lower(), mine.name] if comparing \
		else "This is your %s weapon." % SLOT_NAMES[slot].to_lower()

	_equip.color = accent
	if not comparing:
		_equip.text = "EQUIPPED"
		_equip.sub = "%s WEAPON" % SLOT_NAMES[slot]
		_equip.disabled = true
	else:
		_equip.disabled = false
		_equip.text = "EQUIP"
		var other := 1 - slot
		_equip.sub = "SWAP WITH %s" % SLOT_NAMES[slot] if loadout[other] == looking else "AS %s" % SLOT_NAMES[slot]
	_equip.queue_redraw()
