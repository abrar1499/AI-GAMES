extends RefCounted
## Level data for Draw Guard.
##
## Every level is a Dictionary:
##   eggs    Array of Vector2      where each egg starts (its centre)
##   ground  Array of polygons     solid terrain
##   spikes  Array of [x, y, w]    spike strips standing on height y
##   rocks   Array of {t, x, r}    falling rocks: time, x, radius (+ y, vx, vy)
##   bombs   Array of {t, x, fuse} bombs: time, x, seconds until the blast
##   hail    {from, to, x0, x1, rate, slant}
##   wind    sideways push in px/s^2 (negative blows left)
##   time    seconds the egg has to survive
##   sol     one line that wins the level: used for the hint and the star limits
##   tip     text shown at the bottom (optional)
##
## `tools/check_levels.gd` plays every level with its `sol` line and with no
## line at all, so a level that cannot be won or cannot be lost is caught.

const G := 1000.0  # usual ground height
const EGG := 36.0  # egg centre sits this far above the ground


static func P(a: Array) -> PackedVector2Array:
	var out := PackedVector2Array()
	for i in range(0, a.size(), 2):
		out.append(Vector2(a[i], a[i + 1]))
	return out


## Flat ground from far left to far right.
static func ground(y := G) -> PackedVector2Array:
	return P([-1500, y, 2220, y, 2220, 3200, -1500, 3200])


static func box(x: float, y: float, w: float, h: float) -> PackedVector2Array:
	return P([x, y, x + w, y, x + w, y + h, x, y + h])


## Half an ellipse standing on `base`: a dome over whatever is at cx.
static func arc(cx: float, base: float, rx: float, ry: float, n := 18) -> PackedVector2Array:
	var out := PackedVector2Array()
	for i in n + 1:
		var a := PI * i / n
		out.append(Vector2(cx - cos(a) * rx, base - sin(a) * ry))
	return out


## A straight or bent line through the given points, with extra points between.
static func path(a: Array, step := 24.0) -> PackedVector2Array:
	var corners := P(a)
	var out := PackedVector2Array([corners[0]])
	for i in range(1, corners.size()):
		var from := corners[i - 1]
		var to := corners[i]
		var n := maxi(1, int(ceil(from.distance_to(to) / step)))
		for k in range(1, n + 1):
			out.append(from.lerp(to, float(k) / n))
	return out


static func egg(x: float, ground_y := G) -> Vector2:
	return Vector2(x, ground_y - EGG)


## The egg centre for an egg resting on a slope: `y` is the slope height there.
static func on_slope(x: float, y: float) -> Vector2:
	return Vector2(x, y - 42.0)


static func all() -> Array:
	var L: Array = []
	var far := 2220.0
	var deep := 3200.0

	# ---- 1-5: falling rocks -------------------------------------------------
	L.append({
		"eggs": [egg(360)],
		"ground": [ground()],
		"rocks": [{"t": 0.9, "x": 360, "r": 34}],
		"time": 4.5,
		"sol": arc(360, G - 12, 78, 118),
		"tip": "Draw a line over the egg,\nthen lift your finger.",
	})
	L.append({
		"eggs": [egg(360)],
		"ground": [ground()],
		"rocks": [{"t": 0.8, "x": 360, "r": 28}, {"t": 1.6, "x": 300, "r": 34}, {"t": 2.4, "x": 430, "r": 30}],
		"time": 5.5,
		"sol": arc(360, G - 12, 85, 122),
		"tip": "Less ink = more stars.",
	})
	L.append({
		"eggs": [egg(360)],
		"ground": [P([-1500, 700, 120, 700, 280, G, 440, G, 600, 700, far, 700, far, deep, -1500, deep])],
		"rocks": [{"t": 0.8, "x": 170, "r": 30}, {"t": 1.5, "x": 560, "r": 30}, {"t": 2.3, "x": 360, "r": 26}],
		"time": 5.5,
		"sol": path([216, 850, 504, 850]),
		"tip": "A line can rest on the hills.",
	})
	L.append({
		"eggs": [egg(360, 760)],
		"ground": [ground(), box(300, 760, 120, 300)],
		"rocks": [{"t": 0.9, "x": 360, "r": 30}, {"t": 2.0, "x": 345, "r": 26}],
		"time": 5.5,
		"sol": arc(360, 800, 80, 150),
		"tip": "No ground to stand on? Hang it.",
	})
	L.append({
		"eggs": [egg(330)],
		"ground": [P([-1500, G, 470, G, 760, 820, far, 820, far, deep, -1500, deep]), box(180, 620, 200, 40)],
		"rocks": [{"t": 0.8, "x": 640, "r": 32}, {"t": 1.2, "x": 300, "r": 26}, {"t": 2.2, "x": 700, "r": 28}],
		"time": 6.0,
		"sol": arc(330, G - 12, 64, 105),
	})

	# ---- 6-10: slopes and spikes --------------------------------------------
	L.append({
		"eggs": [on_slope(200, 817)],
		"ground": [P([-1500, 780, 140, 780, 300, 880, 320, 880, 320, 1060, 480, 1060, 480, 880, 600, 880, 720, 800,
			far, 800, far, deep, -1500, deep])],
		"spikes": [[320, 1060, 160]],
		"time": 5.0,
		"sol": path([290, 860, 510, 860]),
		"tip": "The egg rolls! Build it a bridge.",
	})
	L.append({
		"eggs": [on_slope(160, 737)],
		"ground": [P([-1500, 700, 100, 700, 420, 900, far, 900, far, deep, -1500, deep])],
		"spikes": [[610, 900, 500]],
		"time": 5.0,
		"sol": path([470, 888, 520, 780, 570, 888]),
		"tip": "Stop the egg before the spikes.",
	})
	L.append({
		"eggs": [egg(560, 940)],
		"ground": [P([-1500, 560, -100, 560, 400, 940, 690, 940, 690, 700, far, 700, far, deep, -1500, deep])],
		"rocks": [{"t": 0.6, "x": 0, "y": 480, "r": 46, "vx": 150}],
		"time": 6.0,
		"sol": arc(560, 928, 78, 112),
	})
	L.append({
		"eggs": [egg(360, 820)],
		"ground": [ground(), box(260, 820, 200, 400)],
		"spikes": [[-200, G, 460], [460, G, 460]],
		"rocks": [{"t": 0.8, "x": 360, "r": 30}, {"t": 1.8, "x": 300, "r": 28}, {"t": 2.8, "x": 425, "r": 28}],
		"time": 6.0,
		"sol": arc(360, 808, 82, 112),
	})
	L.append({
		"eggs": [on_slope(200, 687)],
		"ground": [P([-1500, 640, 100, 640, 480, 820, 680, 820, 680, 600, far, 600, far, deep, -1500, deep]),
			box(60, 430, 260, 40)],
		"hail": {"from": 0.3, "to": 5.0, "x0": -100, "x1": 820, "rate": 34},
		"time": 6.0,
		"sol": path([244, 688, 268, 620, 304, 716]),
		"tip": "Stay under the roof!",
	})

	# ---- 11-15: hail --------------------------------------------------------
	L.append({
		"eggs": [egg(360)],
		"ground": [ground()],
		"hail": {"from": 0.4, "to": 4.6, "rate": 36},
		"time": 5.5,
		"sol": arc(360, G - 12, 72, 112),
		"tip": "Hail! Leave no gap.",
	})
	L.append({
		"eggs": [egg(378)],
		"ground": [ground(), box(420, 700, 900, 300)],
		"hail": {"from": 0.4, "to": 5.0, "x0": -800, "x1": 500, "rate": 44, "slant": 0.6},
		"time": 6.0,
		"sol": path([240, G - 12, 412, 764]),
		"tip": "Lean the line on the wall.",
	})
	L.append({
		"eggs": [egg(360, 760)],
		"ground": [ground(), box(300, 760, 120, 300)],
		"hail": {"from": 0.4, "to": 5.0, "rate": 36},
		"time": 6.0,
		"sol": arc(360, 800, 80, 150),
	})
	L.append({
		"eggs": [egg(360)],
		"ground": [ground(), box(300, 760, 320, 40)],
		"hail": {"from": 0.4, "to": 5.0, "x0": -800, "x1": 700, "rate": 46, "slant": 0.7},
		"time": 6.0,
		"sol": path([262, G - 12, 292, 746]),
	})
	L.append({
		"eggs": [egg(360)],
		"ground": [ground()],
		"hail": {"from": 0.4, "to": 5.8, "rate": 36},
		"rocks": [{"t": 1.4, "x": 310, "r": 32}, {"t": 3.0, "x": 420, "r": 32}],
		"time": 6.8,
		"sol": arc(360, G - 12, 92, 125),
	})

	# ---- 16-20: bombs -------------------------------------------------------
	L.append({
		"eggs": [egg(300)],
		"ground": [ground()],
		"bombs": [{"t": 0.6, "x": 470, "fuse": 2.4}],
		"time": 4.5,
		"sol": path([360, G - 12, 392, 880, 424, G - 12]),
		"tip": "The blast cannot pass your line.",
	})
	L.append({
		"eggs": [egg(360)],
		"ground": [ground()],
		"bombs": [{"t": 0.6, "x": 210, "fuse": 2.2}, {"t": 1.4, "x": 520, "fuse": 2.6}],
		"time": 5.5,
		"sol": arc(360, G - 12, 74, 112),
	})
	L.append({
		"eggs": [egg(360, 940)],
		"ground": [P([-1500, 560, -60, 560, 260, 940, 460, 940, 780, 560, far, 560, far, deep, -1500, deep])],
		"bombs": [{"t": 0.5, "x": 20, "y": 480, "fuse": 2.6, "vx": 120}, {"t": 1.6, "x": 700, "y": 480, "fuse": 2.6, "vx": -120}],
		"time": 6.0,
		"sol": arc(360, 928, 72, 110),
	})
	L.append({
		"eggs": [egg(360, 1040)],
		"ground": [P([-1500, 900, 300, 900, 300, 1040, 420, 1040, 420, 900, far, 900, far, deep, -1500, deep])],
		"bombs": [{"t": 0.6, "x": 360, "fuse": 2.4}],
		"rocks": [{"t": 3.6, "x": 360, "r": 30}],
		"time": 6.0,
		"sol": path([272, 886, 448, 886]),
		"tip": "A small lid is enough.",
	})
	L.append({
		"eggs": [egg(360)],
		"ground": [ground()],
		"hail": {"from": 0.4, "to": 6.0, "rate": 30},
		"bombs": [{"t": 0.8, "x": 200, "fuse": 2.6}],
		"rocks": [{"t": 4.4, "x": 370, "r": 32}],
		"time": 7.0,
		"sol": arc(360, G - 12, 84, 120),
	})

	# ---- 21-25: wind --------------------------------------------------------
	L.append({
		"eggs": [egg(260)],
		"ground": [ground()],
		"spikes": [[540, G, 400]],
		"wind": 650,
		"time": 5.0,
		"sol": path([390, G - 12, 430, 880, 470, G - 12]),
		"tip": "Wind! Do not let the egg roll away.",
	})
	L.append({
		"eggs": [egg(300)],
		"ground": [ground()],
		"hail": {"from": 0.5, "to": 5.0, "x0": -700, "x1": 800, "rate": 44, "slant": 0.5},
		"wind": 650,
		"time": 6.0,
		"sol": arc(300, G - 12, 76, 114),
	})
	L.append({
		"eggs": [egg(430)],
		"ground": [ground(), box(310, 830, 30, 200), box(580, 760, 900, 300)],
		"rocks": [{"t": 1.6, "x": 120, "r": 30}, {"t": 2.6, "x": 100, "r": 28}],
		"wind": 650,
		"time": 6.0,
		"sol": path([300, 812, 610, 742]),
		"tip": "Make a roof from post to wall.",
	})
	L.append({
		"eggs": [egg(580, 900)],
		"ground": [P([-1500, 700, 110, 700, 110, 900, 280, 900, 280, 1080, 440, 1080, 440, 900, far, 900, far, deep, -1500, deep])],
		"spikes": [[280, 1080, 160]],
		"wind": -650,
		"time": 5.5,
		"sol": path([250, 886, 470, 886]),
	})
	L.append({
		"eggs": [egg(240)],
		"ground": [ground()],
		"spikes": [[560, G, 400]],
		"hail": {"from": 0.5, "to": 5.2, "x0": -700, "x1": 800, "rate": 40, "slant": 0.5},
		"bombs": [{"t": 0.9, "x": 0, "fuse": 2.4}],
		"wind": 650,
		"time": 6.2,
		"sol": arc(240, G - 12, 78, 116),
	})

	# ---- 26-30: two eggs ----------------------------------------------------
	L.append({
		"eggs": [egg(300), egg(420)],
		"ground": [ground()],
		"rocks": [{"t": 0.8, "x": 300, "r": 30}, {"t": 1.5, "x": 420, "r": 30}, {"t": 2.4, "x": 360, "r": 34}],
		"time": 5.5,
		"sol": arc(360, G - 12, 140, 125, 24),
		"tip": "Two eggs. Still one line.",
	})
	L.append({
		"eggs": [egg(250, 1040), egg(470, 1040)],
		"ground": [P([-1500, 900, 195, 900, 195, 1040, 305, 1040, 305, 900, 415, 900, 415, 1040, 525, 1040, 525, 900,
			far, 900, far, deep, -1500, deep])],
		"rocks": [{"t": 0.8, "x": 250, "r": 30}, {"t": 1.6, "x": 470, "r": 30}],
		"bombs": [{"t": 2.4, "x": 360, "fuse": 2.0}],
		"time": 6.0,
		"sol": path([170, 886, 550, 886]),
	})
	L.append({
		"eggs": [on_slope(150, 756), on_slope(570, 756)],
		"ground": [P([-1500, 700, 60, 700, 300, 1000, 420, 1000, 660, 700, far, 700, far, deep, -1500, deep])],
		"spikes": [[300, G, 120]],
		"time": 5.5,
		"sol": path([236, 900, 484, 900]),
	})
	L.append({
		"eggs": [egg(400), egg(480)],
		"ground": [ground(), box(270, 830, 30, 200), box(590, 760, 900, 300)],
		"hail": {"from": 0.8, "to": 5.2, "x0": -700, "x1": 800, "rate": 44, "slant": 0.4},
		"wind": 650,
		"time": 6.2,
		"sol": path([258, 812, 620, 742]),
	})
	L.append({
		"eggs": [egg(290), egg(430)],
		"ground": [ground()],
		"hail": {"from": 0.4, "to": 6.4, "rate": 34},
		"bombs": [{"t": 0.8, "x": 120, "fuse": 2.4}, {"t": 2.0, "x": 610, "fuse": 2.4}],
		"rocks": [{"t": 4.6, "x": 300, "r": 32}, {"t": 5.2, "x": 420, "r": 32}],
		"time": 7.5,
		"sol": arc(360, G - 12, 150, 128, 26),
		"tip": "The last egg-scape!",
	})

	return L
