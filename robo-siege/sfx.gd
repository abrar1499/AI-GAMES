extends Node
## Sound effects made in code, so the project needs no audio files.

const RATE := 44100

var enabled := true

var _bank := {}
var _players: Array[AudioStreamPlayer] = []
var _next := 0
var _shots: Array[AudioStreamWAV] = []


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	for i in 10:
		var p := AudioStreamPlayer.new()
		add_child(p)
		_players.append(p)
	# Three slightly different rifle shots, so a burst does not sound like
	# one sample on repeat.
	for i in 3:
		_shots.append(_gunshot(i))
	_bank = {
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
	if not enabled:
		return
	var stream: AudioStreamWAV
	if sound_name == "shot":
		stream = _shots[randi() % _shots.size()]
	elif _bank.has(sound_name):
		stream = _bank[sound_name]
	else:
		return
	var p := _players[_next]
	_next = (_next + 1) % _players.size()
	p.stream = stream
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


## A rifle shot, built from the four things you hear in a real one:
## a sharp crack, the bark of the muzzle, a thump in the chest, and the
## echo that rolls away afterwards.
func _gunshot(variant: int) -> AudioStreamWAV:
	var rng := RandomNumberGenerator.new()
	rng.seed = 100 + variant
	var seconds := 0.36
	var bark_cut := 0.34 + 0.05 * variant      # brightness of the muzzle bark
	var thump_from := 190.0 - 14.0 * variant   # where the chest thump starts, in Hz
	var out := PackedFloat32Array()
	var bark := 0.0
	var bark_low := 0.0
	var echo := 0.0
	var phase := 0.0
	var knock_phase := 0.0
	for i in int(seconds * RATE):
		var t := float(i) / RATE
		var white := rng.randf_range(-1.0, 1.0)
		# Crack: raw noise for the first few thousandths of a second.
		var crack := white * exp(-t * 700.0)
		# Bark: noise with the very top and the bottom taken off.
		bark = lerpf(bark, white, bark_cut)
		bark_low = lerpf(bark_low, bark, 0.02)
		var muzzle := (bark - bark_low) * exp(-t * 34.0) * 1.5
		# Knock: a mid note that drops fast. Small phone speakers cannot play
		# deep bass, so this is what gives the shot its punch there.
		knock_phase += TAU * (230.0 + 900.0 * exp(-t * 70.0)) / RATE
		var knock := sin(knock_phase) * exp(-t * 48.0) * 0.8
		# Thump: a deep note for headphones and bigger speakers.
		phase += TAU * (48.0 + thump_from * exp(-t * 42.0)) / RATE
		var thump := sin(phase) * exp(-t * 20.0) * 0.6
		# Echo: dull noise that fades slowly.
		echo = lerpf(echo, white, 0.09)
		var tail := echo * exp(-t * 8.0) * 1.7 * minf(1.0, t * 60.0)
		# Push it all hard into a soft limit, like a loud sound into a microphone.
		out.append(tanh((crack * 0.9 + muzzle + knock + thump + tail) * 1.7) * 0.92)
	# Fade the last few milliseconds so the end does not click.
	var fade := int(0.02 * RATE)
	for i in fade:
		out[out.size() - 1 - i] *= float(i) / fade
	return _wav(out)


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
