extends Node
## Sound effects made in code, so the project needs no audio files.

const RATE := 22050

var enabled := true

var _bank := {}
var _players: Array[AudioStreamPlayer] = []
var _next := 0


func _ready() -> void:
	for i in 5:
		var p := AudioStreamPlayer.new()
		add_child(p)
		_players.append(p)
	_bank = {
		"click": _notes([[660, 0.05]], 30.0),
		"pop": _notes([[320, 0.05], [520, 0.08]], 18.0),
		"hurt": _notes([[1250, 0.05], [900, 0.07], [600, 0.16]], 9.0),
		"boom": _noise(0.55, 6.0, 0.12),
		"thud": _noise(0.16, 22.0, 0.08),
		"star": _notes([[880, 0.07], [1320, 0.16]], 12.0),
		"coin": _notes([[1175, 0.06], [1568, 0.14]], 14.0),
		"win": _notes([[523, 0.11], [659, 0.11], [784, 0.11], [1047, 0.3]], 7.0),
		"lose": _notes([[392, 0.14], [311, 0.14], [233, 0.32]], 6.0),
	}


func play(sound_name: String, pitch := 1.0) -> void:
	if not enabled or not _bank.has(sound_name):
		return
	var p := _players[_next]
	_next = (_next + 1) % _players.size()
	p.stream = _bank[sound_name]
	p.pitch_scale = pitch
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


## A short run of notes, each [frequency, seconds], fading at `decay`.
func _notes(notes: Array, decay: float) -> AudioStreamWAV:
	var out := PackedFloat32Array()
	for note: Array in notes:
		var n := int(float(note[1]) * RATE)
		var phase := 0.0
		for i in n:
			var t := float(i) / RATE
			phase += TAU * float(note[0]) / RATE
			var attack := minf(1.0, t * 200.0)
			out.append((sin(phase) + 0.3 * sin(phase * 2.0)) * 0.45 * attack * exp(-decay * t))
	return _wav(out)


## Filtered noise: a low `tone` rumbles, a high one crackles.
func _noise(seconds: float, decay: float, tone: float) -> AudioStreamWAV:
	var rng := RandomNumberGenerator.new()
	rng.seed = 7
	var out := PackedFloat32Array()
	var last := 0.0
	for i in int(seconds * RATE):
		var t := float(i) / RATE
		last = lerpf(last, rng.randf_range(-1.0, 1.0), tone)
		out.append(last * 0.9 * exp(-decay * t))
	return _wav(out)
