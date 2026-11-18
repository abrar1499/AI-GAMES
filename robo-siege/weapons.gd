extends RefCounted
## Every weapon in the game: what it looks like, where it is held, what it
## sounds like, and how it shoots. The armory reads this list, the trooper
## holds the model, and player.gd takes its numbers. The robots carry
## weapons from the same list.

## What each entry says:
##
##   model    a file in assets/guns, lying along X with its muzzle at +X and
##            its sights up (tools/import_rifles.py makes them so)
##   length   how long the weapon is in the trooper's hands, in metres
##   grip     where the trigger hand holds it: how far along from the butt
##            (0) to the muzzle (1), and how far up from the model's lowest
##            point (0) to its highest (1)
##   fore     the same for the other hand, under the barrel
##   bore     how far up the barrel is, for where bullets leave
##   sound    the recording it plays when the trooper fires it: one of the
##            supplied recordings of fire heard from close by. The player
##            can choose another in the armory.
##   far      the recording it plays when a robot fires it: one of those
##            heard from a distance
##   dps      damage a second with the trigger held
##
## A weapon has no rate of fire of its own. It fires in time with its
## recording, so each bullet lands on a shot, and each bullet does as much
## damage as keeps `dps` what it says. `tuned()` works both out.
const LIST := [
	{
		"id": "rifle", "name": "VANGUARD", "class": "ASSAULT RIFLE",
		"blurb": "The outpost's standard issue. Steady, accurate, never a bad choice.",
		"model": "Rifle_Vanguard", "length": 0.94,
		"grip": Vector2(0.35, 0.20), "fore": Vector2(0.68, 0.40), "bore": 0.61,
		"sound": "mg_a", "far": "mg_c", "reload_sound": "reload",
		"dps": 200.0, "mag": 30, "reload": 1.4, "spread": 0.005, "kick": 0.012,
	},
	{
		"id": "ak", "name": "CYBER AK", "class": "BATTLE RIFLE",
		"blurb": "An old design with new teeth. Hits hardest, and kicks like it.",
		"model": "Rifle_CyberAK", "length": 0.96,
		"grip": Vector2(0.295, 0.36), "fore": Vector2(0.62, 0.62), "bore": 0.72,
		"sound": "ak47", "far": "m249", "reload_sound": "reload",
		"dps": 250.0, "mag": 30, "reload": 1.7, "spread": 0.011, "kick": 0.022,
	},
	{
		"id": "carbine", "name": "NANITE NS-11", "class": "CARBINE",
		"blurb": "Light and very fast. Empties a magazine before a robot can turn.",
		"model": "Rifle_Nanite", "length": 0.88,
		"grip": Vector2(0.265, 0.27), "fore": Vector2(0.61, 0.36), "bore": 0.66,
		"sound": "mg_b", "far": "p90", "reload_sound": "reload",
		"dps": 200.0, "mag": 40, "reload": 1.2, "spread": 0.012, "kick": 0.009,
	},
	{
		"id": "heavy", "name": "TITAN", "class": "MACHINE GUN",
		"blurb": "Seventy-five rounds of trouble, and slow to feed when they are gone.",
		"model": "Rifle_Titan", "length": 1.10,
		"grip": Vector2(0.265, 0.28), "fore": Vector2(0.56, 0.42), "bore": 0.585,
		"sound": "smg", "far": "cal50", "reload_sound": "reload_heavy",
		"dps": 225.0, "mag": 75, "reload": 2.8, "spread": 0.016, "kick": 0.011,
	},
]

## How long before the next shot is due a burst is called over, if no shot
## is coming. The recording is stopped then, before it gets as far as a
## shot that was never fired.
const RELEASE_AHEAD := 0.03

## The recording the player has chosen for each weapon in the armory, by
## the weapon's id. A weapon not in here plays its own `sound`.
static var chosen := {}

static var _marks := {}


## Where the shots are in each recording of automatic fire, by its short
## name: what tools/build_supplied_sounds.py wrote down.
static func marks() -> Dictionary:
	if _marks.is_empty():
		var path := "res://assets/sounds/fire_marks.json"
		if FileAccess.file_exists(path):
			_marks = JSON.parse_string(FileAccess.get_file_as_string(path))
	return _marks


## The short names of the recordings a weapon can be given, in a fixed order.
static func recordings() -> Array:
	var names: Array = marks().keys()
	names.sort()
	return names


## What the armory calls a recording.
static func label(recording: String) -> String:
	return str(marks()[recording].label) if marks().has(recording) else recording.to_upper()


## Whether a recording is one of automatic fire (played round while firing)
## rather than separate shots.
static func is_burst(recording: String) -> bool:
	return marks().has(recording)


## The time from one shot to the next on a recording.
static func period(recording: String) -> float:
	return float(marks()[recording].period) if marks().has(recording) else 0.1


## The recording a weapon plays when the trooper fires it.
static func sound_of(w: Dictionary) -> String:
	var picked := str(chosen.get(w.id, w.sound))
	return picked if marks().has(picked) else str(w.sound)


## The weapon with its recording settled: `sound` is the one in use,
## `interval` the time between its shots and `damage` what each bullet does.
static func tuned(w: Dictionary) -> Dictionary:
	var out := w.duplicate()
	out.sound = sound_of(w)
	out.interval = period(out.sound)
	out.damage = float(w.dps) * float(out.interval)
	return out


static func by_id(id: String) -> Dictionary:
	for w: Dictionary in LIST:
		if w.id == id:
			return w
	return LIST[0]


static func exists(id: String) -> bool:
	for w: Dictionary in LIST:
		if w.id == id:
			return true
	return false


static func index_of(id: String) -> int:
	for i in LIST.size():
		if LIST[i].id == id:
			return i
	return 0


## Rounds a minute. `w` is a tuned weapon.
static func rpm(w: Dictionary) -> int:
	return int(round(60.0 / float(w.interval)))


## What the armory shows for a tuned weapon: each line has a name, the
## figure as it is written, and how full its bar is from 0 to 1.
static func stats(w: Dictionary) -> Array:
	return [
		{"name": "DAMAGE", "text": "%d" % int(round(float(w.damage))),
			"value": clampf(float(w.damage) / 28.0, 0.04, 1.0)},
		{"name": "FIRE RATE", "text": "%d RPM" % rpm(w),
			"value": clampf(rpm(w) / 1300.0, 0.04, 1.0)},
		{"name": "ACCURACY", "text": "%d%%" % int(round(100.0 * (1.0 - float(w.spread) / 0.05))),
			"value": clampf(1.0 - float(w.spread) / 0.05, 0.04, 1.0)},
		{"name": "MAGAZINE", "text": "%d" % w.mag,
			"value": clampf(int(w.mag) / 80.0, 0.04, 1.0)},
		{"name": "RELOAD", "text": "%.1f s" % w.reload,
			"value": clampf(1.0 - (float(w.reload) - 0.8) / 2.4, 0.04, 1.0)},
	]
