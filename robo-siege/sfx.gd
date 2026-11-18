extends Node
## Every sound in the game goes through here.
##
## Sounds are asked for by name. A name is either a recording in
## assets/sounds or, failing that, one of the simple sounds made in code
## below. Asking for a name that is neither is a bug, and `missing` records
## it so the tests can catch it: a gun must never fall silent because of a
## misspelt name.
##
## There are three sets of voices, so one kind of sound cannot cut another
## off: the player's own weapon, everything heard in the world at a place
## (enemy fire, explosions), and the rest (interface, footsteps).

const RATE := 44100

## Which mixer channel each kind of sound plays on, and how loud that
## channel is. The weapon is the loudest thing; footsteps sit well under it.
const BUSES := {"Guns": 0.0, "World": -2.0, "Steps": -3.0, "UI": -4.0}
## The engine fades every sound in over the first hundredth of a second or
## two (one block of its mixer). That is exactly where the crack of a shot
## and the tap of a heel are, so without help every sharp sound comes out
## soft. This much silence is put in front of each sound, so the fade is
## over before the sound itself begins. It is too short a wait to notice.
const LEAD := 0.022

const UI_SOUNDS := ["click", "upgrade", "wave", "clear", "over", "pickup", "beep", "empty", "swap"]
const STEP_SOUNDS := ["step_walk", "step_run", "dash"]

var enabled := true
## Names that were asked for and could not be played. Empty in a working game.
var missing := {}
## How many times each name has been played.
var played := {}

var _bank := {}
var _recorded := {}
var _gun_voices: Array[AudioStreamPlayer] = []
var _voices: Array[AudioStreamPlayer] = []
var _world_voices: Array[AudioStreamPlayer3D] = []
var _next_gun := 0
var _next := 0
var _next_world := 0


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	_make_buses()
	# A fast weapon fires again long before the last shot has died away, so
	# it needs enough voices for every shot still ringing.
	for i in 12:
		var p := AudioStreamPlayer.new()
		p.bus = "Guns"
		add_child(p)
		_gun_voices.append(p)
	for i in 10:
		var p := AudioStreamPlayer.new()
		add_child(p)
		_voices.append(p)
	for i in 12:
		var p := AudioStreamPlayer3D.new()
		p.bus = "World"
		# Full volume within about ten metres, fading with distance beyond.
		p.unit_size = 10.0
		p.max_distance = 120.0
		p.attenuation_filter_cutoff_hz = 9000.0
		p.attenuation_filter_db = -12.0
		p.panning_strength = 0.8
		add_child(p)
		_world_voices.append(p)
	_bank = {
		"hit": _noise(0.05, 60.0, 0.9, 0.5),
		"kill": _sweep(300.0, 60.0, 0.3, 9.0, 0.8),
		"boom": _noise(0.7, 5.0, 0.1, 1.0),
		"hurt": _sweep(220.0, 110.0, 0.22, 10.0, 0.8),
		"empty": _notes([[1200, 0.03]], 80.0),
		"dash": _noise(0.2, 12.0, 0.25, 0.5),
		"throw": _sweep(300.0, 500.0, 0.12, 14.0, 0.4),
		"pickup": _notes([[880, 0.06], [1320, 0.12]], 14.0),
		"beep": _notes([[1500, 0.05]], 30.0),
		"wave": _notes([[392, 0.12], [523, 0.12], [659, 0.22]], 7.0),
		"clear": _notes([[523, 0.1], [659, 0.1], [784, 0.1], [1047, 0.3]], 6.0),
		"upgrade": _notes([[659, 0.07], [988, 0.18]], 10.0),
		"click": _notes([[660, 0.05]], 30.0),
		"swap": _notes([[420, 0.04], [0, 0.03], [640, 0.05]], 40.0),
		"over": _notes([[392, 0.18], [311, 0.18], [233, 0.18], [175, 0.45]], 4.0),
	}
	_use_recordings()


## The mixer: one channel per kind of sound, all feeding the master, which
## has a limiter so that many loud sounds at once get turned down together
## instead of crackling.
func _make_buses() -> void:
	for bus_name: String in BUSES:
		if AudioServer.get_bus_index(bus_name) != -1:
			continue
		AudioServer.add_bus()
		var index := AudioServer.bus_count - 1
		AudioServer.set_bus_name(index, bus_name)
		AudioServer.set_bus_send(index, "Master")
		AudioServer.set_bus_volume_db(index, float(BUSES[bus_name]))
	if AudioServer.get_bus_effect_count(0) == 0:
		var limiter := AudioEffectHardLimiter.new()
		limiter.ceiling_db = -0.6
		limiter.pre_gain_db = 0.0
		limiter.release = 0.12
		AudioServer.add_bus_effect(0, limiter)


## Every recording in assets/sounds is picked up: `name.wav`, or
## `name1.wav`, `name2.wav`, ... for several takes, one of which is chosen
## at random each time so a burst is never one sample on repeat.
func _use_recordings() -> void:
	var names: Array = _bank.keys()
	names.append_array(["step_walk", "step_run", "reload", "reload_heavy"])
	names.append_array(["enemy_scout_shot", "enemy_gunner_shot", "enemy_boss_shot"])
	for w: Dictionary in load("res://weapons.gd").LIST:
		names.append(shot_name(w))
	for sound_name: String in names:
		if _recorded.has(sound_name):
			continue
		var takes: Array[AudioStream] = []
		var single := "res://assets/sounds/%s.wav" % sound_name
		if ResourceLoader.exists(single):
			takes.append(load(single))
		for i in range(1, 9):
			var path := "res://assets/sounds/%s%d.wav" % [sound_name, i]
			if ResourceLoader.exists(path):
				takes.append(load(path))
		for take in takes:
			_give_lead(take)
		if not takes.is_empty():
			_recorded[sound_name] = takes
	for sound_name: String in _bank:
		_give_lead(_bank[sound_name])


## Puts LEAD seconds of silence in front of a sound, once.
static func _give_lead(stream: AudioStream) -> void:
	var wav := stream as AudioStreamWAV
	if wav == null or wav.has_meta("lead"):
		return
	var bytes_per_frame := 0
	if wav.format == AudioStreamWAV.FORMAT_16_BITS:
		bytes_per_frame = 2
	elif wav.format == AudioStreamWAV.FORMAT_8_BITS:
		bytes_per_frame = 1
	if bytes_per_frame == 0:
		return
	if wav.stereo:
		bytes_per_frame *= 2
	var quiet := PackedByteArray()
	quiet.resize(int(LEAD * wav.mix_rate) * bytes_per_frame)
	quiet.append_array(wav.data)
	wav.data = quiet
	wav.set_meta("lead", true)


## The name of a weapon's shot sound.
static func shot_name(weapon: Dictionary) -> String:
	return "%s_shot" % weapon.sound


## Whether `sound_name` is something this can play.
func has(sound_name: String) -> bool:
	return _recorded.has(sound_name) or _bank.has(sound_name)


## How many recorded takes there are of a sound; 0 if it is made in code.
func takes_of(sound_name: String) -> int:
	return (_recorded[sound_name] as Array).size() if _recorded.has(sound_name) else 0


func _stream(sound_name: String) -> AudioStream:
	played[sound_name] = int(played.get(sound_name, 0)) + 1
	if _recorded.has(sound_name):
		var takes: Array = _recorded[sound_name]
		return takes[randi() % takes.size()]
	if _bank.has(sound_name):
		return _bank[sound_name]
	if not missing.has(sound_name):
		missing[sound_name] = true
		push_warning("No sound called '%s'" % sound_name)
	return null


## Plays a sound that has no place in the world: the player's own weapon,
## footsteps, the interface.
func play(sound_name: String, pitch := 1.0, volume_db := 0.0) -> void:
	var stream := _stream(sound_name)
	if stream == null or not enabled:
		return
	var p: AudioStreamPlayer
	if sound_name.ends_with("_shot"):
		p = _gun_voices[_next_gun]
		_next_gun = (_next_gun + 1) % _gun_voices.size()
	else:
		p = _free_voice()
		if sound_name in UI_SOUNDS:
			p.bus = "UI"
		elif sound_name in STEP_SOUNDS:
			p.bus = "Steps"
		else:
			p.bus = "World"
	p.stream = stream
	p.pitch_scale = pitch
	p.volume_db = volume_db
	p.play()


## Plays a sound at a place in the world, so it is quieter from far away
## and comes from the side it is on.
func play_at(sound_name: String, at: Vector3, pitch := 1.0, volume_db := 0.0) -> void:
	var stream := _stream(sound_name)
	if stream == null or not enabled:
		return
	var p := _world_voices[_next_world]
	for i in _world_voices.size():
		var candidate := _world_voices[(_next_world + i) % _world_voices.size()]
		if not candidate.playing:
			p = candidate
			break
	_next_world = (_world_voices.find(p) + 1) % _world_voices.size()
	p.stream = stream
	p.pitch_scale = pitch
	p.volume_db = volume_db
	p.global_position = at
	p.play()


## A voice that is not busy, or failing that the one that started longest ago.
func _free_voice() -> AudioStreamPlayer:
	for i in _voices.size():
		var candidate := _voices[(_next + i) % _voices.size()]
		if not candidate.playing:
			_next = (_next + i + 1) % _voices.size()
			return candidate
	var p := _voices[_next]
	_next = (_next + 1) % _voices.size()
	return p


func _wav(samples: PackedFloat32Array) -> AudioStreamWAV:
	var bytes := PackedByteArray()
	bytes.resize(samples.size() * 2)
	for i in samples.size():
		bytes.encode_s16(i * 2, int(clampf(samples[i], -1.0, 1.0) * 32000.0))
	var wav := AudioStreamWAV.new()
	wav.format = AudioStreamWAV.FORMAT_16_BITS
	wav.mix_rate = RATE
	wav.stereo = false
	wav.data = bytes
	return wav


## A short run of notes, each [frequency, seconds]; frequency 0 is a pause.
func _notes(notes: Array, decay: float) -> AudioStreamWAV:
	var out := PackedFloat32Array()
	for note: Array in notes:
		var n := int(float(note[1]) * RATE)
		var phase := 0.0
		for i in n:
			var t := float(i) / RATE
			phase += TAU * float(note[0]) / RATE
			var attack := minf(1.0, t * 200.0)
			var v := 0.0 if float(note[0]) <= 0.0 else (sin(phase) + 0.3 * sin(phase * 2.0)) * 0.4
			out.append(v * attack * exp(-decay * t))
	return _wav(out)


## A tone that slides from one pitch to another: lasers, groans, whooshes.
func _sweep(from: float, to: float, seconds: float, decay: float, gain: float) -> AudioStreamWAV:
	var out := PackedFloat32Array()
	var phase := 0.0
	var n := int(seconds * RATE)
	for i in n:
		var t := float(i) / RATE
		phase += TAU * lerpf(from, to, float(i) / n) / RATE
		var square := 1.0 if sin(phase) > 0.0 else -1.0
		out.append((sin(phase) * 0.7 + square * 0.3) * gain * 0.5 * exp(-decay * t))
	return _wav(out)


## Filtered noise: a low `tone` rumbles, a high one cracks. `thump` adds a
## falling bass note underneath, for gunshots.
func _noise(seconds: float, decay: float, tone: float, gain: float, thump := 0.0) -> AudioStreamWAV:
	var rng := RandomNumberGenerator.new()
	rng.seed = 7
	var out := PackedFloat32Array()
	var last := 0.0
	var phase := 0.0
	for i in int(seconds * RATE):
		var t := float(i) / RATE
		# `tone` was tuned at 22050 samples a second; keep the same pitch at any rate.
		last = lerpf(last, rng.randf_range(-1.0, 1.0), tone * 22050.0 / RATE)
		var v := last * 0.9
		if thump > 0.0:
			phase += TAU * (thump * exp(-t * 40.0) + 60.0) / RATE
			v = v * 0.6 + sin(phase) * 0.6
		out.append(v * exp(-decay * t))
	# Filtering changes how loud the noise is; bring every sound to `gain`.
	var peak := 0.001
	for v in out:
		peak = maxf(peak, absf(v))
	for i in out.size():
		out[i] *= gain * 0.9 / peak
	return _wav(out)
