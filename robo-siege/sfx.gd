extends Node
## Sound effects made in code, so the project needs no audio files.

const RATE := 22050

var enabled := true

var _bank := {}
var _players: Array[AudioStreamPlayer] = []
var _next := 0


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	for i in 10:
		var p := AudioStreamPlayer.new()
		add_child(p)
		_players.append(p)
	_bank = {
		"shot": _noise(0.09, 38.0, 0.55, 0.8, 900.0),
		"enemy_shot": _sweep(700.0, 220.0, 0.16, 12.0, 0.4),
		"hit": _noise(0.05, 60.0, 0.9, 0.5),
		"kill": _sweep(300.0, 60.0, 0.3, 9.0, 0.8),
		"boom": _noise(0.7, 5.0, 0.1, 1.0),
		"hurt": _sweep(220.0, 110.0, 0.22, 10.0, 0.8),
		"reload": _notes([[500, 0.04], [0, 0.12], [750, 0.05]], 40.0),
		"empty": _notes([[1200, 0.03]], 80.0),
		"dash": _noise(0.2, 12.0, 0.25, 0.5),
		"throw": _sweep(300.0, 500.0, 0.12, 14.0, 0.4),
		"pickup": _notes([[880, 0.06], [1320, 0.12]], 14.0),
		"beep": _notes([[1500, 0.05]], 30.0),
		"wave": _notes([[392, 0.12], [523, 0.12], [659, 0.22]], 7.0),
		"clear": _notes([[523, 0.1], [659, 0.1], [784, 0.1], [1047, 0.3]], 6.0),
		"upgrade": _notes([[659, 0.07], [988, 0.18]], 10.0),
		"click": _notes([[660, 0.05]], 30.0),
		"over": _notes([[392, 0.18], [311, 0.18], [233, 0.18], [175, 0.45]], 4.0),
	}


func play(sound_name: String, pitch := 1.0, volume_db := 0.0) -> void:
	if not enabled or not _bank.has(sound_name):
		return
	var p := _players[_next]
	_next = (_next + 1) % _players.size()
	p.stream = _bank[sound_name]
	p.pitch_scale = pitch
	p.volume_db = volume_db
	p.play()


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
		last = lerpf(last, rng.randf_range(-1.0, 1.0), tone)
		var v := last * 0.9
		if thump > 0.0:
			phase += TAU * (thump * exp(-t * 40.0) + 60.0) / RATE
			v = v * 0.6 + sin(phase) * 0.6
		out.append(v * gain * exp(-decay * t))
	return _wav(out)
