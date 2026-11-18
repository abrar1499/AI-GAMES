extends Control
## A dark plate with two corners cut off: the background of every panel.

const UI := preload("res://ui.gd")

## The plate shades from `fill` at the top to `fill_low` at the bottom.
var fill := Color(UI.PANEL, 0.94)
var fill_low := Color(UI.INK, 0.94)
var edge := UI.LINE
var cut := 16.0
var corners := [true, false, true, false]
## A short bright line along the top edge; transparent for none.
var accent := Color.TRANSPARENT
## Small marks in the two square corners.
var ticks := true


func _init() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE


func _notification(what: int) -> void:
	if what == NOTIFICATION_RESIZED:
		queue_redraw()


func _draw() -> void:
	var points := UI.cut_box(Rect2(Vector2.ZERO, size), cut, corners)
	UI.fill_shaded(self, points, fill, fill_low)
	UI.outline(self, points, edge, 1.5)
	if accent.a > 0.0:
		var from := cut + 2.0 if corners[0] else 2.0
		draw_line(Vector2(from, 1.5), Vector2(from + minf(size.x * 0.35, 150.0), 1.5), accent, 3.0)
	if ticks:
		var mark := Color(edge.lightened(0.35), 0.9)
		var arm := 9.0
		if not corners[1]:
			draw_polyline([Vector2(size.x - arm - 5, 5), Vector2(size.x - 5, 5), Vector2(size.x - 5, 5 + arm)], mark, 1.5)
		if not corners[3]:
			draw_polyline([Vector2(5, size.y - arm - 5), Vector2(5, size.y - 5), Vector2(5 + arm, size.y - 5)], mark, 1.5)
