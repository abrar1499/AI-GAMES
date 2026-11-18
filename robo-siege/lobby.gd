extends Control
## The first screen: the trooper stands in the outpost with the weapons
## chosen, and everything the player can do before a fight is one tap away.
##
## It only shows and asks; main.gd holds the settings and does the work.

signal deploy
signal open_armory(slot: int)
signal skin_changed(index: int)
signal sound_toggled(on: bool)
signal auto_fire_toggled(on: bool)

const UI := preload("res://ui.gd")
const Plate := preload("res://ui/plate.gd")
const TechButton := preload("res://ui/tech_button.gd")
const WeaponView := preload("res://ui/weapon_view.gd")
const Rig := preload("res://rig.gd")
const Weapons := preload("res://weapons.gd")

const SLOT_NAMES := ["PRIMARY", "SECONDARY"]

var loadout: Array[String] = ["rifle", "ak"]
var skin := 0
var best_wave := 0
var best_score := 0
var sound_on := true
var auto_fire := true
var touch := false


func _ready() -> void:
	# Fill the screen. (The plain anchors call would keep the present size, which is nothing.)
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	mouse_filter = Control.MOUSE_FILTER_IGNORE

	# Dark washes down the left and along the bottom, where the type is.
	var left_shade := UI.shade(Color(UI.INK, 0.94), Vector2(0.0, 0.5), Vector2(0.62, 0.5))
	left_shade.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(left_shade)
	var low_shade := UI.shade(Color(UI.INK, 0.85), Vector2(0.5, 1.0), Vector2(0.5, 0.58))
	low_shade.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(low_shade)
	var top_shade := UI.shade(Color(UI.INK, 0.7), Vector2(0.5, 0.0), Vector2(0.5, 0.2))
	top_shade.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(top_shade)

	_add_title()
	_add_records()
	_add_menu()
	_add_loadout()
	_add_deploy()


func _place(c: Control, corner: int, offset: Vector2, box: Vector2) -> void:
	add_child(c)
	c.set_anchors_preset(corner)
	c.size = box
	match corner:
		Control.PRESET_TOP_LEFT:
			c.position = offset
		Control.PRESET_TOP_RIGHT:
			c.set_offsets_preset(Control.PRESET_TOP_RIGHT)
			c.offset_left = -offset.x - box.x
			c.offset_right = -offset.x
			c.offset_top = offset.y
			c.offset_bottom = offset.y + box.y
		Control.PRESET_BOTTOM_LEFT:
			c.offset_left = offset.x
			c.offset_right = offset.x + box.x
			c.offset_top = -offset.y - box.y
			c.offset_bottom = -offset.y
		Control.PRESET_BOTTOM_RIGHT:
			c.offset_left = -offset.x - box.x
			c.offset_right = -offset.x
			c.offset_top = -offset.y - box.y
			c.offset_bottom = -offset.y


func _add_title() -> void:
	var block := VBoxContainer.new()
	block.add_theme_constant_override("separation", 0)
	block.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_place(block, Control.PRESET_TOP_LEFT, Vector2(52, 30), Vector2(640, 150))
	var name_row := HBoxContainer.new()
	name_row.add_theme_constant_override("separation", 18)
	name_row.mouse_filter = Control.MOUSE_FILTER_IGNORE
	block.add_child(name_row)
	name_row.add_child(UI.headline("ROBO", 74))
	name_row.add_child(UI.headline("SIEGE", 74, UI.ORANGE))
	var under := HBoxContainer.new()
	under.add_theme_constant_override("separation", 14)
	under.mouse_filter = Control.MOUSE_FILTER_IGNORE
	block.add_child(under)
	var rule := ColorRect.new()
	rule.color = UI.CYAN
	rule.custom_minimum_size = Vector2(64, 3)
	rule.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	rule.mouse_filter = Control.MOUSE_FILTER_IGNORE
	under.add_child(rule)
	under.add_child(UI.label("HOLD THE OUTPOST.  BREAK THE MACHINES.", 19, UI.DIM, "bold", 3))
	UI.enter(block, Vector2(-40, 0), 0.0)


## The player's best run, top right.
func _add_records() -> void:
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 12)
	row.alignment = BoxContainer.ALIGNMENT_END
	row.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_place(row, Control.PRESET_TOP_RIGHT, Vector2(40, 34), Vector2(380, 66))
	row.add_child(_record("BEST WAVE", str(best_wave) if best_wave > 0 else "--", UI.CYAN))
	row.add_child(_record("HIGH SCORE", UI.thousands(best_score) if best_score > 0 else "--", UI.GOLD))
	UI.enter(row, Vector2(0, -24), 0.1)


func _record(title: String, value: String, color: Color) -> Control:
	var plate := Plate.new()
	plate.custom_minimum_size = Vector2(176, 66)
	plate.accent = color
	plate.cut = 12.0
	var col := VBoxContainer.new()
	col.set_anchors_preset(Control.PRESET_FULL_RECT)
	col.offset_left = 16
	col.offset_top = 7
	col.add_theme_constant_override("separation", -6)
	col.mouse_filter = Control.MOUSE_FILTER_IGNORE
	plate.add_child(col)
	col.add_child(UI.label(title, 14, UI.DIM, "bold", 2))
	col.add_child(UI.label(value, 30, color, "bold"))
	return plate


func _add_menu() -> void:
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 10)
	_place(col, Control.PRESET_TOP_LEFT, Vector2(52, 196), Vector2(372, 220))

	var armory := TechButton.new()
	armory.text = "ARMORY"
	armory.sub = "%d weapons  -  carry any two" % Weapons.LIST.size()
	armory.accent = UI.ORANGE
	armory.custom_minimum_size = Vector2(372, 70)
	armory.pressed.connect(func(): open_armory.emit(0))
	col.add_child(armory)

	var operator := TechButton.new()
	operator.text = "OPERATOR"
	operator.custom_minimum_size = Vector2(372, 70)
	var show_skin := func():
		operator.sub = "%s uniform  -  tap to change" % Rig.LOOKS[skin].name
		operator.tag = "%d/%d" % [skin + 1, Rig.LOOKS.size()]
	show_skin.call()
	operator.pressed.connect(func():
		skin = (skin + 1) % Rig.LOOKS.size()
		show_skin.call()
		skin_changed.emit(skin))
	col.add_child(operator)

	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	col.add_child(row)
	var sound := _switch("SOUND", sound_on)
	sound.pressed.connect(func():
		sound_on = not sound_on
		_set_switch(sound, sound_on)
		sound_toggled.emit(sound_on))
	row.add_child(sound)
	var auto := _switch("AUTO FIRE", auto_fire)
	auto.pressed.connect(func():
		auto_fire = not auto_fire
		_set_switch(auto, auto_fire)
		auto_fire_toggled.emit(auto_fire))
	row.add_child(auto)
	UI.enter(col, Vector2(-60, 0), 0.08)


func _switch(title: String, on: bool) -> BaseButton:
	var b := TechButton.new()
	b.text = title
	b.font_size = 19
	b.pad = 14.0
	b.tag_width = 46.0
	b.custom_minimum_size = Vector2(181, 54)
	_set_switch(b, on)
	return b


func _set_switch(b: BaseButton, on: bool) -> void:
	b.tag = "ON" if on else "OFF"
	b.tag_color = UI.GREEN if on else UI.DIM
	b.accent = UI.GREEN if on else UI.LINE.lightened(0.3)
	b.queue_redraw()


## The two weapons going into the fight; tap one to change it.
func _add_loadout() -> void:
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 8)
	_place(col, Control.PRESET_BOTTOM_LEFT, Vector2(52, 34), Vector2(530, 152))
	col.add_child(UI.section("LOADOUT"))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 12)
	col.add_child(row)
	for i in loadout.size():
		row.add_child(_slot_tile(i))
	UI.enter(col, Vector2(-60, 0), 0.16)


func _slot_tile(index: int) -> Control:
	var w := Weapons.by_id(loadout[index])
	var tile := TechButton.new()
	tile.custom_minimum_size = Vector2(259, 118)
	tile.accent = UI.ORANGE if index == 0 else UI.CYAN
	tile.pressed.connect(func(): open_armory.emit(index))
	var icon := WeaponView.new()
	icon.set_anchors_preset(Control.PRESET_FULL_RECT)
	icon.offset_left = 18
	icon.offset_right = -14
	icon.offset_top = 26
	icon.offset_bottom = -34
	tile.add_child(icon)
	icon.show_weapon(w)
	var slot_name := UI.label(SLOT_NAMES[index], 14, tile.accent, "bold", 3)
	slot_name.position = Vector2(16, 6)
	tile.add_child(slot_name)
	var weapon_name := UI.label(w.name, 22, UI.TEXT, "bold", 1)
	weapon_name.set_anchors_preset(Control.PRESET_BOTTOM_LEFT)
	weapon_name.offset_left = 16
	weapon_name.offset_top = -34
	weapon_name.offset_bottom = -6
	weapon_name.offset_right = 250
	tile.add_child(weapon_name)
	var kind := UI.label(w["class"], 14, UI.DIM, "bold", 1)
	kind.set_anchors_preset(Control.PRESET_BOTTOM_RIGHT)
	kind.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	kind.offset_left = -150
	kind.offset_right = -14
	kind.offset_top = -29
	kind.offset_bottom = -7
	tile.add_child(kind)
	return tile


func _add_deploy() -> void:
	var go := TechButton.new()
	go.primary = true
	go.color = UI.ORANGE
	go.text = "DEPLOY"
	go.sub = "WAVE SURVIVAL"
	go.font_size = 50
	go.sub_size = 18
	go.cut = 22.0
	go.align = HORIZONTAL_ALIGNMENT_CENTER
	go.pressed.connect(func(): deploy.emit())
	_place(go, Control.PRESET_BOTTOM_RIGHT, Vector2(44, 62), Vector2(350, 108))
	UI.enter(go, Vector2(60, 0), 0.2)

	var how := "LEFT THUMB MOVES   -   RIGHT THUMB AIMS" if touch \
		else "WASD MOVE   MOUSE AIM   CLICK FIRE   R RELOAD   G BOMB   SPACE DASH   Q SWAP"
	var hint := UI.label(how, 14, Color(UI.DIM, 0.95), "bold", 1)
	hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	_place(hint, Control.PRESET_BOTTOM_RIGHT, Vector2(46, 28), Vector2(640, 20))
	UI.enter(hint, Vector2(0, 16), 0.3)
