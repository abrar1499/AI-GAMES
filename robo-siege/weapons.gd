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
##   sound    "<sound>_fire.wav" in assets/sounds is its recording, used as it
##            was supplied
##   interval seconds between shots. This is the time between shots on the
##            weapon's recording, so each bullet lands on a shot; it is not
##            a number to tune. tools/build_gun_sounds.py prints it and
##            tools/sound_test.gd checks the two agree.
##   pellets  above 1 would make it a shotgun
##   auto     fires while the trigger is held
const LIST := [
	{
		"id": "rifle", "name": "VANGUARD", "class": "ASSAULT RIFLE",
		"blurb": "The outpost's standard issue. Steady, accurate, never a bad choice.",
		"model": "Rifle_Vanguard", "length": 0.94,
		"grip": Vector2(0.35, 0.20), "fore": Vector2(0.68, 0.40), "bore": 0.61,
		"sound": "rifle", "reload_sound": "reload",
		"damage": 18.0, "interval": 0.0907, "mag": 30, "reload": 1.4,
		"spread": 0.005, "pellets": 1, "auto": true, "kick": 0.012,
	},
	{
		"id": "ak", "name": "CYBER AK", "class": "BATTLE RIFLE",
		"blurb": "An old design with new teeth. Hits hardest, and kicks like it.",
		"model": "Rifle_CyberAK", "length": 0.96,
		"grip": Vector2(0.295, 0.36), "fore": Vector2(0.62, 0.62), "bore": 0.72,
		"sound": "ak", "reload_sound": "reload",
		"damage": 23.0, "interval": 0.0906, "mag": 30, "reload": 1.7,
		"spread": 0.011, "pellets": 1, "auto": true, "kick": 0.022,
	},
	{
		"id": "carbine", "name": "NANITE NS-11", "class": "CARBINE",
		"blurb": "Light and very fast. Empties a magazine before a robot can turn.",
		"model": "Rifle_Nanite", "length": 0.88,
		"grip": Vector2(0.265, 0.27), "fore": Vector2(0.61, 0.36), "bore": 0.66,
		"sound": "carbine", "reload_sound": "reload",
		"damage": 14.0, "interval": 0.0704, "mag": 40, "reload": 1.2,
		"spread": 0.012, "pellets": 1, "auto": true, "kick": 0.009,
	},
	{
		"id": "heavy", "name": "TITAN", "class": "MACHINE GUN",
		"blurb": "Seventy-five rounds of trouble, and slow to feed when they are gone.",
		"model": "Rifle_Titan", "length": 1.10,
		"grip": Vector2(0.265, 0.28), "fore": Vector2(0.56, 0.42), "bore": 0.585,
		"sound": "heavy", "reload_sound": "reload_heavy",
		"damage": 17.0, "interval": 0.0756, "mag": 75, "reload": 2.8,
		"spread": 0.016, "pellets": 1, "auto": true, "kick": 0.011,
	},
]


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


## Rounds a minute.
static func rpm(w: Dictionary) -> int:
	return int(round(60.0 / float(w.interval)))


## Damage a second with the trigger held, leaving reloading aside.
static func dps(w: Dictionary) -> float:
	return per_shot(w) / float(w.interval)


## Damage of one pull of the trigger.
static func per_shot(w: Dictionary) -> float:
	return float(w.damage) * int(w.pellets)


## What the armory shows for a weapon: each line has a name, the figure as
## it is written, and how full its bar is from 0 to 1.
static func stats(w: Dictionary) -> Array:
	return [
		{"name": "DAMAGE", "text": "%d" % per_shot(w) if int(w.pellets) == 1 else "%d x%d" % [w.damage, w.pellets],
			"value": clampf(per_shot(w) / 140.0, 0.04, 1.0)},
		{"name": "FIRE RATE", "text": "%d RPM" % rpm(w),
			"value": clampf(rpm(w) / 900.0, 0.04, 1.0)},
		{"name": "ACCURACY", "text": "%d%%" % int(round(100.0 * (1.0 - float(w.spread) / 0.05))),
			"value": clampf(1.0 - float(w.spread) / 0.05, 0.04, 1.0)},
		{"name": "MAGAZINE", "text": "%d" % w.mag,
			"value": clampf(int(w.mag) / 80.0, 0.04, 1.0)},
		{"name": "RELOAD", "text": "%.1f s" % w.reload,
			"value": clampf(1.0 - (float(w.reload) - 0.8) / 2.4, 0.04, 1.0)},
	]
