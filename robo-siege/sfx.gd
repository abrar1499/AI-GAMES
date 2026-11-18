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
##
## The player's weapon is not played shot by shot. Each weapon has a
## recording of a burst, used exactly as it was supplied: it plays while the
## weapon is firing, round and round the part between its second shot and
## its last, and when the firing stops the recording's own ending is played
## from the same point of a shot. `fire()` is called for every bullet and
## `fire_end()` when the burst is over. Where the shots are in each
## recording is in assets/sounds/fire_marks.json, written by
## tools/build_gun_sounds.py.

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

## True while a weapon's recording is playing round.
var firing := false
## How long, in seconds, each weapon's recording has been played.
var fire_time := {}

var _bank := {}
var _recorded := {}
## For each weapon's fire: the recording set to go round ("loop"), the same
## recording left to run to its end ("tail"), and its marks.
var _fire := {}
var _fire_name := ""
var _fire_voice: AudioStreamPlayer
var _fire_watch := 0.0
var _tail_voices: Array[AudioStreamPlayer] = []
var _next_tail := 0
var _voices: Array[AudioStreamPlayer] = []
var _world_voices: Array[AudioStreamPlayer3D] = []
var _next := 0
var _next_world := 0


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	_make_buses()
	# The player's weapon: one voice for the recording going round, and a
	# few for the endings, which can still be ringing when the next begins.
	_fire_voice = AudioStreamPlayer.new()
	_fire_voice.bus = "Guns"
	add_child(_fire_voice)
	for i in 4:
		var p := AudioStreamPlayer.new()
		p.bus = "Guns"
		add_child(p)
		_tail_voices.append(p)
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
	_use_fire_recordings()


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


## The weapons' own recordings, with the marks that say where their shots
## are. Each is set to go round between its second shot and its last.
func _use_fire_recordings() -> void:
	var marks_path := "res://assets/sounds/fire_marks.json"
	if not FileAccess.file_exists(marks_path):
		return
	var marks: Dictionary = JSON.parse_string(FileAccess.get_file_as_string(marks_path))
	for fire_name: String in marks:
		var path := "res://assets/sounds/%s.wav" % fire_name
		if not ResourceLoader.exists(path):
			continue
		var loop: AudioStreamWAV = load(path)
		var m: Dictionary = marks[fire_name]
		# The ending is the same recording, left to run out.
		var tail: AudioStreamWAV = loop.duplicate()
		tail.loop_mode = AudioStreamWAV.LOOP_DISABLED
		loop.loop_mode = AudioStreamWAV.LOOP_FORWARD
		loop.loop_begin = int(round(float(m.loop_begin) * loop.mix_rate))
		loop.loop_end = int(round(float(m.loop_end) * loop.mix_rate))
		_fire[fire_name] = {"loop": loop, "tail": tail, "marks": m}


## The name of a weapon's fire.
static func fire_name(weapon: Dictionary) -> String:
	return "%s_fire" % weapon.sound


## The time from one shot to the next in a weapon's recording, or 0 if it
## has none. The weapon must fire at this rate for bullets to land on shots.
func fire_period(sound_name: String) -> float:
	return float(_fire[sound_name].marks.period) if _fire.has(sound_name) else 0.0


## A bullet has been fired. Starts the weapon's recording if it is not
## already playing, and keeps it going.
func fire(sound_name: String, volume_db := 0.0) -> void:
	if not _fire.has(sound_name):
		if not missing.has(sound_name):
			missing[sound_name] = true
			push_warning("No weapon recording called '%s'" % sound_name)
		return
	played[sound_name] = int(played.get(sound_name, 0)) + 1
	var entry: Dictionary = _fire[sound_name]
	if not firing or _fire_name != sound_name:
		if firing:
			fire_end()
		firing = true
		_fire_name = sound_name
		if enabled:
			_fire_voice.stream = entry.loop
			_fire_voice.volume_db = volume_db
			_fire_voice.pitch_scale = 1.0
			_fire_voice.play(float(entry.marks.start))
	# If nothing says the burst is over (a pause, say), stop by ourselves.
	_fire_watch = float(entry.marks.period) * 2.0 + 0.2


## The burst is over. The recording going round is stopped, and its own
## ending is played from the same point of a shot, so the last shot dies
## away as it does on the recording.
func fire_end() -> void:
	if not firing:
		return
	firing = false
	if not _fire.has(_fire_name) or not enabled or not _fire_voice.playing:
		_fire_voice.stop()
		return
	var entry: Dictionary = _fire[_fire_name]
	var m: Dictionary = entry.marks
	var period := float(m.period)
	# How far into a shot the recording is at this moment.
	var at := _fire_voice.get_playback_position() + AudioServer.get_time_since_last_mix()
	var into := fposmod(at - float(m.loop_begin), period)
	if at < float(m.loop_begin):
		into = maxf(0.0, at - (float(m.loop_begin) - period))
	var voice := _tail_voices[_next_tail]
	_next_tail = (_next_tail + 1) % _tail_voices.size()
	voice.stream = entry.tail
	voice.volume_db = _fire_voice.volume_db
	voice.pitch_scale = 1.0
	voice.play(minf(float(m.last_shot) + into, float(m.length) - 0.02))
	_fire_voice.stop()


func _process(dt: float) -> void:
	if not firing:
		return
	fire_time[_fire_name] = float(fire_time.get(_fire_name, 0.0)) + dt
	_fire_watch -= dt
	if _fire_watch <= 0.0:
		fire_end()


## Whether `sound_name` is something this can play.
func has(sound_name: String) -> bool:
	return _recorded.has(sound_name) or _bank.has(sound_name) or _fire.has(sound_name)


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


## Plays a sound that has no place in the world: footsteps, reloading, the
## interface.
func play(sound_name: String, pitch := 1.0, volume_db := 0.0) -> void:
	var stream := _stream(sound_name)
	if stream == null or not enabled:
		return
	var p := _free_voice()
	if sound_name in UI_SOUNDS:
		p.bus = "UI"
	elif sound_name in STEP_SOUNDS:
		p.bus = "Steps"
	elif sound_name.begins_with("reload"):
		p.bus = "Guns"
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
