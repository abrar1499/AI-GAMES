"""Cuts single footsteps out of a recording of someone walking.

    python3 tools/build_step_sounds.py <footsteps.wav> assets/sounds

The recording is a long, steady walk on concrete: well over a hundred steps,
some soft, some hard. The soft ones become the walk and the hard ones the
run, because that is the real difference between the two: a running foot
comes down harder and is off the ground again sooner.

    step_walk1..6.wav   an ordinary step, heel then sole, left to ring
    step_run1..6.wav    a hard step, cut shorter and played louder

The sound of a step is not altered: no filter, nothing added. Each is cut
out, faded to silence at its very end, and set to a level (the walk's
quieter than the run's).

Needs numpy. The source is mono, 44.1 kHz:

    ffmpeg -i in.mp3 -ac 1 -ar 44100 -c:a pcm_s16le footsteps.wav
"""

import os
import sys
import wave

import numpy as np

RATE = 44100
TAKES = 6


def read(path):
    w = wave.open(path)
    assert w.getframerate() == RATE and w.getnchannels() == 1, path
    return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def write(path, samples):
    w = wave.open(path, "wb")
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
    w.close()


def step_starts(a):
    """Where each foot lands: the level jumps after a quiet moment."""
    level = np.convolve(np.abs(a), np.ones(256) / 256, "same")
    peak = level.max()
    found = []
    i = 0
    while i < len(a):
        quiet_before = level[max(0, i - 600):max(1, i - 200)].mean()
        if level[i] > 0.12 * peak and quiet_before < 0.5 * level[i]:
            found.append(i)
            i += int(0.2 * RATE)
            continue
        i += 32
    return found


def cut(a, start, seconds, peak):
    lead = int(0.005 * RATE)
    piece = a[max(0, start - lead): start + int(seconds * RATE)].copy()
    n = len(piece)
    piece[:lead // 2] *= np.linspace(0, 1, lead // 2)
    # Ring for the first half, then down to nothing.
    half = n // 2
    piece[half:] *= 0.5 * (1 + np.cos(np.linspace(0, np.pi, n - half)))
    return piece / max(np.abs(piece).max(), 1e-9) * peak


def main():
    source, out = sys.argv[1], sys.argv[2]
    a = read(source)
    starts = step_starts(a)
    # Leave out the first and last few, where the walker is starting and stopping.
    starts = starts[3:-3]
    reach = int(0.12 * RATE)
    hardness = [np.abs(a[s:s + reach]).max() for s in starts]
    order = np.argsort(hardness)

    # Walk: steps from around the middle of the range, the ordinary ones.
    middle = len(order) // 2
    walk = [starts[i] for i in order[middle - TAKES // 2: middle + (TAKES + 1) // 2]]
    # Run: the hardest landings on the recording.
    run = [starts[i] for i in order[-TAKES:]]

    for n, start in enumerate(sorted(walk)):
        write(os.path.join(out, "step_walk%d.wav" % (n + 1)), cut(a, start, 0.30, 0.50))
    for n, start in enumerate(sorted(run)):
        write(os.path.join(out, "step_run%d.wav" % (n + 1)), cut(a, start, 0.20, 0.80))
    print("%d steps found; walk from ones peaking %.2f-%.2f, run from %.2f-%.2f" % (
        len(starts),
        min(hardness[i] for i in order[middle - TAKES // 2: middle + (TAKES + 1) // 2]),
        max(hardness[i] for i in order[middle - TAKES // 2: middle + (TAKES + 1) // 2]),
        min(hardness[i] for i in order[-TAKES:]), max(hardness[i] for i in order[-TAKES:])))


if __name__ == "__main__":
    main()
