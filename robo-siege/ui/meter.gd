extends Control
## A bar made of small blocks, for one figure of a weapon. It fills up when
## its value is set, and can show the same figure for another weapon, in
## green where this one is better and red where it is worse.

const UI := preload("res://ui.gd")

var blocks := 22
var color := UI.ORANGE
## The other weapon's figure, 0 to 1; negative for none.
var compare := -1.0

var _value := 0.0
var _shown := 0.0


func _init() -> void:
	custom_minimum_size = Vector2(120, 12)
	mouse_filter = Control.MOUSE_FILTER_IGNORE


## Sets the figure, 0 to 1. The bar runs up to it unless `at_once`.
func set_value(value: float, at_once := false) -> void:
	_value = clampf(value, 0.0, 1.0)
	if at_once or not is_inside_tree():
		_shown = _value
		queue_redraw()
		return
	var tw := create_tween()
	tw.tween_method(func(v: float):
		_shown = v
		queue_redraw(), _shown, _value, 0.35).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)


func _draw() -> void:
	var gap := 3.0
	var wide := (size.x - gap * (blocks - 1)) / blocks
	for i in blocks:
		var at := (i + 0.5) / blocks
		var box := Rect2(i * (wide + gap), 0, wide, size.y)
		var lit := at <= _shown
		var paint := Color(1, 1, 1, 0.09)
		if lit:
			paint = color
		if compare >= 0.0:
			var theirs := at <= compare
			if lit and not theirs:
				paint = UI.GREEN
			elif theirs and not lit:
				paint = Color(UI.RED, 0.55)
		draw_rect(box, paint)
