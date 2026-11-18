"""Prepares the recordings in assets/sounds from the downloaded sound packs.

All sources are CC0 packs from OpenGameArt (listed in the README). Each one
is first converted to mono 44.1 kHz WAV with ffmpeg into one folder, named
<pack>__<file>.wav; this script then picks the sounds the game uses, trims
the silence, sets the loudness and writes them to assets/sounds.

    python3 tools/build_sounds.py <folder of converted packs> <folder of gun recordings> assets/sounds

Needs numpy.
"""

import math
import os
import sys
import wave

import numpy as np

RATE = 44100


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
    rms = math.sqrt(float((samples ** 2).mean()))
    print("%-16s %.2fs  peak %.2f  rms %.3f  %3d KB" % (
        os.path.basename(path), len(samples) / RATE, abs(samples).max(), rms, len(samples) * 2 // 1024))


def trim(a, floor=0.03, lead=0.002, tail=0.03):
    """Cuts the silence off both ends."""
    env = np.abs(a)
    live = np.where(env > floor * env.max())[0]
    start = max(0, live[0] - int(lead * RATE))
    end = min(len(a), live[-1] + int(tail * RATE))
    return a[start:end]


def fade(a, fade_in=0.002, fade_out=0.03):
    a = a.copy()
    n = min(len(a) // 2, int(fade_in * RATE))
    a[:n] *= np.linspace(0, 1, n)
    m = min(len(a) // 2, int(fade_out * RATE))
    a[-m:] *= np.linspace(1, 0, m) ** 2
    return a


def level(a, peak):
    return a / abs(a).max() * peak


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
    """A bell-shaped boost (or cut) round one frequency."""
    amp = 10 ** (gain_db / 40)
    w = 2 * math.pi * freq / RATE
    alpha = math.sin(w) / (2 * q)
    c = math.cos(w)
    a0 = 1 + alpha / amp
    return biquad(a, (1 + alpha * amp) / a0, -2 * c / a0, (1 - alpha * amp) / a0, -2 * c / a0, (1 - alpha / amp) / a0)


def onsets(a, gap=0.25):
    """Where each loud event starts in a recording of several."""
    env = np.abs(a)
    found = []
    for i in np.where(env > 0.5 * env.max())[0]:
        if not found or i - found[-1] > gap * RATE:
            found.append(int(i))
    return found


def cut(a, start, seconds, tail=0.06):
    """One shot from a recording, trimmed and faded so a burst stays clear."""
    s = a[start - int(0.004 * RATE): start + int(seconds * RATE)].copy()
    s = s / max(abs(s).max(), 1e-6)
    t = np.arange(len(s)) / RATE
    s *= np.where(t < seconds * 0.2, 1.0, np.exp(-(t - seconds * 0.2) / tail))
    n = int(0.0015 * RATE)
    s[:n] *= np.linspace(0, 1, n)
    m = int(0.01 * RATE)
    s[-m:] *= np.linspace(1, 0, m)
    return s


def punch(s, knock=0.4, crack=0.5, drive=3.0, seed=0):
    """Adds the weight and snap that a phone speaker can actually play.

    A raw gunshot is one huge spike and a long quiet tail: thin on a small
    speaker, and mush at nine shots a second. Lifting the low mids and the
    bite, adding a short knock and crack, and driving it into a soft limit
    makes every shot land.
    """
    s = highpass(s, 120)
    s = boost(s, 450, 6.0, 0.8)
    s = boost(s, 1500, 4.0, 0.9)
    s = boost(s, 3400, 7.0, 1.0)
    t = np.arange(len(s)) / RATE
    freq = 150 + 370 * np.exp(-t / 0.016)
    low = np.sin(2 * math.pi * np.cumsum(freq) / RATE) * np.exp(-t / 0.035)
    rng = np.random.default_rng(40 + seed)
    hiss = highpass(rng.uniform(-1, 1, len(s)), 3000)
    snap = hiss * (1.0 * np.exp(-t / 0.004) + 0.3 * np.exp(-t / 0.03))
    s = s / abs(s).max() + knock * low + crack * snap
    return np.tanh(drive * s / abs(s).max()) * 0.95


def main():
    packs, guns, out = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)

    def pack(name):
        return read(os.path.join(packs, name + ".wav"))

    def plain(target, source, peak, max_seconds=None, fade_out=0.03):
        a = trim(pack(source))
        if max_seconds:
            a = a[: int(max_seconds * RATE)]
        write(os.path.join(out, target + ".wav"), level(fade(a, 0.002, fade_out), peak))

    # The player's own weapons are built by tools/build_gun_sounds.py from
    # the recordings of each gun.

    # Robot guns: real recordings too, each kind with its own weapon, taken
    # from the mid-distance takes so they sit behind the player's own gun.
    def enemy_gun(target, path, seconds, tail, gain, count=3):
        recording = read(path)
        for i, start in enumerate(onsets(recording, 0.2)[:count]):
            shot = cut(recording, start, seconds, tail)
            shot = highpass(shot, 150)
            shot = boost(shot, 900, 4.0, 0.9)
            shot = boost(shot, 2600, 3.0, 1.0)
            # Lighter than the player's own gun, but still with a body, or a
            # shot from across the arena is lost under everything else.
            shot = np.tanh(2.4 * shot / abs(shot).max())
            write(os.path.join(out, '%s%d.wav' % (target, i + 1)), level(shot, gain))

    # Grunt: a carbine, quick and sharp.
    enemy_gun('enemy_shot', os.path.join(guns, 'AR-15__D_24P.wav'), 0.34, 0.1, 0.8, 2)
    # Heavy: a heavy rifle with a deep crack.
    enemy_gun('enemy_heavy', os.path.join(guns, 'Mosin_Nagant__M_26P.wav'), 0.5, 0.18, 0.9, 3)
    # Boss: a shotgun's thump.
    enemy_gun('enemy_boss', os.path.join(guns, 'Mossberg__N_26P.wav'), 0.6, 0.22, 0.95, 3)

    # Bullets hitting a robot.
    for i, n in enumerate(["rd100__hit_01", "rd100__hit_02", "k_scifi__impactMetal_004"]):
        plain("hit%d" % (i + 1), n, 0.8)
    # A robot falling apart.
    plain("kill1", "k_scifi__explosionCrunch_000", 0.9, 0.8, 0.25)
    plain("kill2", "k_scifi__explosionCrunch_002", 0.9, 0.8, 0.25)
    # The trooper taking a hit.
    for i, n in enumerate(["slightscream-11", "slightscream-14", "slightscream-03"]):
        plain("hurt%d" % (i + 1), "scream__" + n, 0.85)
    # A mech's footfalls: metal on metal.
    for i, n in enumerate(["metal_01", "metal_04", "metal_06", "metal_09", "metal_11", "metal_12"]):
        plain("step%d" % (i + 1), "rd100__" + n, 0.6, 0.5)
    # Dash and throw.
    plain("dash1", "swish__swish-7", 0.9)
    plain("dash2", "swish__swish-9", 0.9)
    plain("throw1", "swish__swish-1", 0.8)
    plain("throw2", "swish__swish-6", 0.8)
    # Signals and menus.
    plain("wave", "alarm__short", 0.75, None, 0.1)
    plain("clear", "wob__Upper01", 0.85, None, 0.15)
    plain("over", "wob__Downer01", 0.85, None, 0.1)
    plain("upgrade", "wob__Rise03", 0.85, None, 0.1)
    plain("pickup", "wob__Coin01", 0.85, None, 0.1)
    plain("beep", "rdscifi__beep_01", 0.7)
    plain("click", "k_ui__click_001", 0.7)
    plain("empty", "k_ui__switch_001", 0.7)
    plain("swap", "k_ui__switch_004", 0.7)


if __name__ == "__main__":
    main()
