"""Prepares the everyday sounds in assets/sounds from downloaded sound packs:
hits, explosions, grunts, swishes, alarms and the clicks of the menus.

All sources are CC0 packs from OpenGameArt (listed in the README). Each one
is first converted to mono 44.1 kHz WAV with ffmpeg into one folder, named
<pack>__<file>.wav; this script then picks the sounds the game uses, trims
the silence, sets the loudness and writes them to assets/sounds.

    python3 tools/build_sounds.py <folder of converted packs> assets/sounds

The weapons are made by tools/build_gun_sounds.py and the footsteps by
tools/build_step_sounds.py, from their own recordings.

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


def main():
    packs, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)

    def pack(name):
        return read(os.path.join(packs, name + ".wav"))

    def plain(target, source, peak, max_seconds=None, fade_out=0.03):
        a = trim(pack(source))
        if max_seconds:
            a = a[: int(max_seconds * RATE)]
        write(os.path.join(out, target + ".wav"), level(fade(a, 0.002, fade_out), peak))

    # Bullets hitting a robot.
    for i, n in enumerate(["rd100__hit_01", "rd100__hit_02", "k_scifi__impactMetal_004"]):
        plain("hit%d" % (i + 1), n, 0.8)
    # A robot falling apart.
    plain("kill1", "k_scifi__explosionCrunch_000", 0.9, 0.8, 0.25)
    plain("kill2", "k_scifi__explosionCrunch_002", 0.9, 0.8, 0.25)
    # The trooper taking a hit.
    for i, n in enumerate(["slightscream-11", "slightscream-14", "slightscream-03"]):
        plain("hurt%d" % (i + 1), "scream__" + n, 0.85)
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
