extends RefCounted
## Every weapon the player can carry: what it looks like, what it sounds
## like, and how it shoots. The weapons menu reads this list, the trooper
## holds the model, and player.gd takes its numbers.

## `model` is a file in assets/guns, `length` how long it should be in the
## trooper's hands, and `tint` the colour painted on the gun's "Main" parts.
## `pellets` above 1 makes it a shotgun. `auto` guns fire while the trigger
## is held; the others fire once per tap.
const LIST := [
	{
		"id": "rifle", "name": "Assault Rifle", "blurb": "Steady and dependable",
		"model": "AssaultRifle_1", "length": 0.95, "tint": Color("6f7a86"),
		"sound": "rifle", "reload_sound": "reload",
		"damage": 22.0, "interval": 0.11, "mag": 30, "reload": 1.4,
		"spread": 0.005, "pellets": 1, "auto": true, "kick": 0.012,
	},
	{
		"id": "smg", "name": "Submachine Gun", "blurb": "Fast, light, sprays",
		"model": "SubmachineGun_2", "length": 0.72, "tint": Color("5f6a74"),
		"sound": "smg", "reload_sound": "reload",
		"damage": 14.0, "interval": 0.07, "mag": 34, "reload": 1.25,
		"spread": 0.013, "pellets": 1, "auto": true, "kick": 0.009,
	},
	{
		"id": "bullpup", "name": "Bullpup", "blurb": "Deep magazine, quick hands",
		"model": "Bullpup_1", "length": 0.9, "tint": Color("3f5a49"),
		"sound": "bullpup", "reload_sound": "reload",
		"damage": 17.0, "interval": 0.068, "mag": 42, "reload": 1.6,
		"spread": 0.009, "pellets": 1, "auto": true, "kick": 0.010,
	},
	{
		"id": "lmg", "name": "Machine Gun", "blurb": "Sixty rounds, slow to feed",
		"model": "AssaultRifle2_1", "length": 1.15, "tint": Color("4a5240"),
		"sound": "lmg", "reload_sound": "reload_heavy",
		"damage": 20.0, "interval": 0.075, "mag": 60, "reload": 2.7,
		"spread": 0.017, "pellets": 1, "auto": true, "kick": 0.011,
	},
	{
		"id": "shotgun", "name": "Shotgun", "blurb": "Six pellets a shell, close in",
		"model": "Shotgun_1", "length": 0.95, "tint": Color("6b4a32"),
		"sound": "shotgun", "reload_sound": "reload_heavy",
		"damage": 17.0, "interval": 0.8, "mag": 6, "reload": 2.2,
		"spread": 0.045, "pellets": 6, "auto": false, "kick": 0.03,
	},
	{
		"id": "sniper", "name": "Sniper Rifle", "blurb": "One shot, one machine",
		"model": "SniperRifle_1", "length": 1.3, "tint": Color("3c4634"),
		"sound": "sniper", "reload_sound": "reload_heavy",
		"damage": 130.0, "interval": 1.0, "mag": 5, "reload": 2.1,
		"spread": 0.0006, "pellets": 1, "auto": false, "kick": 0.05,
	},
	{
		"id": "revolver", "name": "Revolver", "blurb": "Heavy hand, six rounds",
		"model": "Revolver_1", "length": 0.42, "tint": Color("7a6a55"),
		"sound": "revolver", "reload_sound": "reload",
		"damage": 58.0, "interval": 0.38, "mag": 6, "reload": 1.7,
		"spread": 0.005, "pellets": 1, "auto": false, "kick": 0.03,
	},
	{
		"id": "pistol", "name": "Sidearm", "blurb": "Always there, never empty for long",
		"model": "Pistol_1", "length": 0.38, "tint": Color("50565e"),
		"sound": "pistol", "reload_sound": "reload",
		"damage": 26.0, "interval": 0.22, "mag": 14, "reload": 1.05,
		"spread": 0.006, "pellets": 1, "auto": false, "kick": 0.016,
	},
]


static func by_id(id: String) -> Dictionary:
	for w: Dictionary in LIST:
		if w.id == id:
			return w
	return LIST[0]


static func index_of(id: String) -> int:
	for i in LIST.size():
		if LIST[i].id == id:
			return i
	return 0


## A rough 0..1 reading of each number, for the bars in the weapons menu.
static func bars(w: Dictionary) -> Array:
	var per_shot: float = float(w.damage) * int(w.pellets)
	return [
		{"name": "Damage", "value": clampf(per_shot / 160.0, 0.05, 1.0)},
		{"name": "Rate", "value": clampf(0.07 / maxf(float(w.interval), 0.01), 0.05, 1.0)},
		{"name": "Ammo", "value": clampf(int(w.mag) / 60.0, 0.05, 1.0)},
		{"name": "Aim", "value": clampf(1.0 - float(w.spread) / 0.045, 0.05, 1.0)},
	]
