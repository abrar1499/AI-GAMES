"""Turns raw weapon recordings into the shot sounds the game plays.

Each recording is a burst or a long stretch of fire. This picks one clean
shot out of it, lifts the hiss off, gives it the weight a phone speaker can
actually play, and writes it at a steady level.

    python3 tools/build_gun_sounds.py <folder of mono 44.1k wavs> assets/sounds

Needs numpy. ffmpeg converts the sources first:

    ffmpeg -i in.mp3 -ac 1 -ar 44100 -c:a pcm_s16le out.wav
"""

import math
import os
import sys
import wave

import numpy as np

RATE = 44100

## For each weapon: the recording, how long one shot is, and how its voice is
## shaped. `body` lifts the low mids a small speaker can reproduce, `bite`
## the crack, and `knock` adds a short low thump underneath.
WEAPONS = {
    "rifle": {"file": "kave_msri-ak47-machine-gun-burst-sfx-332103_1", "seconds": 0.26,
              "body": 6.0, "bite": 5.0, "knock": 0.45},
    "lmg": {"file": "freesound_community-069323_light-machine-gun-m249-39825", "seconds": 0.22,
            "body": 5.0, "bite": 6.0, "knock": 0.4},
    "smg": {"file": "freesound_community-smgshoot2-35768", "seconds": 0.18,
            "body": 5.5, "bite": 5.5, "knock": 0.35},
    "bullpup": {"file": "freesound_community-fnp90-burst-100583", "seconds": 0.18,
                "body": 6.0, "bite": 5.0, "knock": 0.4},
    "sniper": {"file": "freesound_community-080979_heavy-machine-gun-50-caliber-39764", "seconds": 0.55,
               "body": 8.0, "bite": 4.0, "knock": 0.7},
    "shotgun": {"file": "dennish18-machine-gun-129928", "seconds": 0.45,
                "body": 8.0, "bite": 4.5, "knock": 0.75},
    "revolver": {"file": "dragon-studio-machine-gun-sfx-631486_1", "seconds": 0.35,
                 "body": 7.0, "bite": 5.0, "knock": 0.6},
    "pistol": {"file": "freesound_community-machine-gun-71417", "seconds": 0.2,
               "body": 5.0, "bite": 5.5, "knock": 0.3},
}

## The two reload recordings: a light one and a heavy one.
RELOADS = {
    "reload": {"file": "beetpro-load-gun-sound-effect-5-11003", "body": 4.0},
    "reload_heavy": {"file": "foisal72-gun-load-475276", "body": 7.0},
}

## How many takes of each shot to write, each a slightly different cut, so a
## burst is not one sample on repeat.
TAKES = 3


def read(path):
    w = wave.open(path)
    assert w.getframerate() == RATE and w.getnchannels() == 1, path
    return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def write(path, samples):
    samples = np.clip(samples, -1, 1)
    w = wave.open(path, "wb")
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes((samples * 32767).astype("<i2").tobytes())
    w.close()
    return len(samples)


def biquad(a, b0, b1, b2, a1, a2):
    out = np.zeros_like(a)
    x1 = x2 = y1 = y2 = 0.0
    for i, x in enumerate(a):
        y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, x, y1, y
        out[i] = y
    return out


def highpass(a, freq, q=0.707):
    w = 2 * math.pi * freq / RATE
    alpha = math.sin(w) / (2 * q)
    c = math.cos(w)
    a0 = 1 + alpha
    return biquad(a, (1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)


def boost(a, freq, gain_db, q=1.0):
    """A bell-shaped lift (or cut) round one frequency."""
    amp = 10 ** (gain_db / 40)
    w = 2 * math.pi * freq / RATE
    alpha = math.sin(w) / (2 * q)
    c = math.cos(w)
    a0 = 1 + alpha / amp
    return biquad(a, (1 + alpha * amp) / a0, -2 * c / a0, (1 - alpha * amp) / a0, -2 * c / a0, (1 - alpha / amp) / a0)


def denoise(a, strength=1.6):
    """Lifts the steady hiss off a recording.

    The quietest moments of a recording are its noise; measuring the hiss
    there and taking that much off every moment leaves the shot and drops
    the background.
    """
    size, hop = 1024, 256
    window = np.hanning(size)
    frames = max(1, 1 + (len(a) - size) // hop)
    spectra = np.stack([np.fft.rfft(a[i * hop: i * hop + size] * window, n=size)
                        for i in range(frames)
                        if i * hop + size <= len(a)])
    magnitude = np.abs(spectra)
    # The noise is what is there even in the quietest frames.
    noise = np.percentile(magnitude, 12, axis=0) * strength
    cleaned = np.maximum(magnitude - noise, magnitude * 0.08)
    spectra = spectra * (cleaned / np.maximum(magnitude, 1e-9))
    out = np.zeros(len(a))
    weight = np.zeros(len(a))
    for i in range(spectra.shape[0]):
        piece = np.fft.irfft(spectra[i], n=size) * window
        out[i * hop: i * hop + size] += piece
        weight[i * hop: i * hop + size] += window ** 2
    return out / np.maximum(weight, 1e-6)


def shot_starts(a, gap=0.06):
    """Where each shot begins in a recording of several."""
    env = np.abs(a)
    peak = env.max() or 1e-9
    found = []
    for i in np.where(env > 0.45 * peak)[0]:
        if not found or i - found[-1] > gap * RATE:
            found.append(int(i))
    return found


def shape(a, body, bite, knock, seed):
    """Gives one shot its weight and crack, then pushes it into a soft limit."""
    a = highpass(a, 75)
    a = boost(a, 170, body, 0.8)        # the thump a phone can still play
    a = boost(a, 900, 2.5, 1.0)         # the body of the report
    a = boost(a, 3200, bite, 1.0)       # the crack
    a = a / max(abs(a).max(), 1e-6)
    if knock > 0.0:
        t = np.arange(len(a)) / RATE
        freq = 140 + 300 * np.exp(-t / 0.018)
        low = np.sin(2 * math.pi * np.cumsum(freq) / RATE) * np.exp(-t / 0.045)
        a = a + knock * low
    # Pushing it in makes the whole shot loud, not just its first spike.
    a = np.tanh(2.6 * a / max(abs(a).max(), 1e-6))
    return a


def envelope(a, seconds, tail=0.07):
    t = np.arange(len(a)) / RATE
    a = a * np.where(t < seconds * 0.25, 1.0, np.exp(-(t - seconds * 0.25) / tail))
    n = int(0.0015 * RATE)
    a[:n] *= np.linspace(0, 1, n)
    m = int(0.012 * RATE)
    a[-m:] *= np.linspace(1, 0, m)
    return a


def level(a, peak):
    return a / max(abs(a).max(), 1e-6) * peak


def main():
    source, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)

    for name, spec in WEAPONS.items():
        recording = denoise(read(os.path.join(source, spec["file"] + ".wav")))
        starts = shot_starts(recording)
        if not starts:
            starts = [0]
        written = 0
        for take in range(TAKES):
            start = starts[(take * 3 + 1) % len(starts)]
            piece = recording[max(0, start - int(0.004 * RATE)): start + int(spec["seconds"] * RATE)]
            if len(piece) < int(0.05 * RATE):
                continue
            piece = shape(piece.copy(), spec["body"], spec["bite"], spec["knock"], take)
            piece = envelope(piece, spec["seconds"])
            # A touch below full, so a burst never rattles the speaker.
            written += write(os.path.join(out, "%s_shot%d.wav" % (name, take + 1)), level(piece, 0.82))
        print("%-9s %d takes, %.2fs each, %3d KB" % (name, TAKES, spec["seconds"], written * 2 // 1024))

    for name, spec in RELOADS.items():
        a = denoise(read(os.path.join(source, spec["file"] + ".wav")))
        env = np.abs(a)
        live = np.where(env > 0.02 * env.max())[0]
        a = a[max(0, live[0] - 300): min(len(a), live[-1] + int(0.05 * RATE))]
        a = highpass(a, 110)
        a = boost(a, 220, spec["body"], 0.8)
        a = boost(a, 2800, 2.0, 1.0)
        n = int(0.002 * RATE)
        a[:n] *= np.linspace(0, 1, n)
        m = int(0.03 * RATE)
        a[-m:] *= np.linspace(1, 0, m)
        print("%-9s %.2fs, %3d KB" % (name, len(a) / RATE, write(os.path.join(out, name + ".wav"), level(a, 0.7)) * 2 // 1024))


if __name__ == "__main__":
    main()
