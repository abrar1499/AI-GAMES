extends CanvasLayer
## The one place the game asks for adverts.
##
## No ad SDK ships with this project, so every call shows a short stand-in
## screen and then carries on as if the ad had been watched. To earn money,
## add an AdMob plugin for Godot 4 to the Android export and replace the two
## `_show_placeholder` calls below with the plugin's "show rewarded" and
## "show interstitial" calls. The rest of the game only talks to this file.

## Levels 1..FREE_LEVELS never show a full-screen ad.
const FREE_LEVELS := 5
## After that, one full-screen ad every this many wins.
const WINS_PER_AD := 3

var _busy := false


func _ready() -> void:
	layer = 100


## A rewarded ad the player chose to watch. `done` is called with true when
## the reward was earned.
func show_rewarded(done: Callable) -> void:
	if _busy:
		return
	_show_placeholder("Rewarded ad", func(): done.call(true))


## A full-screen ad between levels. `done` is called when it closes.
func show_interstitial(done: Callable) -> void:
	if _busy:
		return
	_show_placeholder("Ad break", done)


func _show_placeholder(title: String, done: Callable) -> void:
	_busy = true
	var dim := ColorRect.new()
	dim.color = Color(0.07, 0.08, 0.13, 0.96)
	dim.set_anchors_preset(Control.PRESET_FULL_RECT)
	dim.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(dim)
	var box := VBoxContainer.new()
	box.set_anchors_preset(Control.PRESET_CENTER)
	box.grow_horizontal = Control.GROW_DIRECTION_BOTH
	box.grow_vertical = Control.GROW_DIRECTION_BOTH
	box.add_theme_constant_override("separation", 18)
	dim.add_child(box)
	var head := Label.new()
	head.text = title
	head.add_theme_font_size_override("font_size", 54)
	head.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(head)
	var note := Label.new()
	note.text = "Test screen - a real ad shows here\nonce AdMob is connected."
	note.add_theme_font_size_override("font_size", 28)
	note.add_theme_color_override("font_color", Color(1, 1, 1, 0.7))
	note.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(note)
	await get_tree().create_timer(1.4).timeout
	dim.queue_free()
	_busy = false
	done.call()
