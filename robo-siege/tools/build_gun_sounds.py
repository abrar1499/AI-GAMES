"""Puts the supplied weapon recordings into the game without altering them.

    python3 tools/build_gun_sounds.py <folder with the original .mp3 files> assets/sounds

The recordings are bursts of automatic fire. They are used as they are:
nothing is filtered, equalised, layered, re-pitched or levelled. Each of the
trooper's weapons plays its own recording while the trigger is held, and the
recording's own ending when it is let go. All this script does is

  - unpack each .mp3 to a .wav (the same sound, at the same sample rate and
    with the same channels; a .wav can be looped at a chosen point),
  - listen for where the shots are, and write down in
    assets/sounds/fire_marks.json:

        start      just before the first shot: where playing begins
        loop       from the second shot to the last: what repeats while the
                   trigger is held
        last_shot  the last shot: after it the recording dies away, which
                   is what is played when the trigger is let go
        period     the time from one shot to the next. The weapon fires at
                   exactly this rate, so each bullet lands on a shot.

For the robots' fire, one shot at a time is wanted rather than a burst, so
a single shot is cut out of a recording with its own dying away after it.
That is a cut, with a fade at the very end where the recording runs on;
the sound itself is not touched.

Needs numpy, and ffmpeg to unpack the .mp3 files.
"""

import json
import os
import subprocess
import sys
import wave

import numpy as np

## The trooper's weapons: which recording each one is.
WEAPONS = {
    "rifle": "dragon-studio-machine-gun-sfx-631486_1",
    "ak": "kave_msri-ak47-machine-gun-burst-sfx-332103_1",
    "carbine": "freesound_community-fnp90-burst-100583",
    "heavy": "freesound_community-069323_light-machine-gun-m249-39825",
}

## The robots' fire: single shots cut from the other recordings. Each entry
## is the recording and the most seconds to keep.
ENEMIES = {
    "enemy_scout": [
        ("freesound_community-machine-gun-71417", 0.9),
        ("freesound_community-smgshoot2-35768", 0.9),
    ],
    "enemy_gunner": [
        ("dennish18-machine-gun-129928", 1.0),
    ],
    "enemy_boss": [
        ("freesound_community-080979_heavy-machine-gun-50-caliber-39764", 1.3),
    ],
}

## The two reload recordings: a light one and a heavy one.
RELOADS = {
    "reload": "beetpro-load-gun-sound-effect-5-11003",
    "reload_heavy": "foisal72-gun-load-475276",
}

## Playing starts this long before the first shot. The engine fades every
## sound in over its first hundredth of a second or two; this lets that
## happen before the shot rather than during it.
LEAD = 0.014


def find(folder, name):
    """The upload, whatever was put in front of its name."""
    for entry in sorted(os.listdir(folder)):
        if entry.endswith(name + ".mp3"):
            return os.path.join(folder, entry)
    raise SystemExit("no file ending in %s.mp3 in %s" % (name, folder))


def unpack(mp3, wav):
    """The .mp3 as a .wav: the same samples, rate and channels."""
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", mp3, "-c:a", "pcm_s16le", wav], check=True)


def read(path):
    """The samples (one row per moment, one column per channel) and the rate."""
    w = wave.open(path)
    data = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768
    return data.reshape(-1, w.getnchannels()), w.getframerate()


def write(path, samples, rate):
    w = wave.open(path, "wb")
    w.setnchannels(samples.shape[1])
    w.setsampwidth(2)
    w.setframerate(rate)
    w.writeframes((np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes())
    w.close()


def onsets(samples, rate, gap=0.04):
    """Where each shot starts: the level jumps well above what came before."""
    mono = samples.mean(axis=1)
    span = max(8, int(0.0015 * rate))
    fast = np.convolve(np.abs(mono), np.ones(span) / span, "same")
    peak = fast.max()
    hold = int(gap * rate)
    back = int(0.009 * rate)
    found = []
    i = 0
    while i < len(mono):
        if fast[i] > 0.30 * peak:
            before = fast[max(0, i - back):max(1, i - back // 4)].mean() if i > back else 0.0
            if before < 0.6 * fast[i]:
                found.append(i)
                i += hold
                continue
        i += max(1, span // 4)
    # A bump in a dying tail can pass for a shot; real ones are all about as
    # loud as each other.
    reach = int(0.02 * rate)
    tops = [np.abs(mono[i:i + reach]).max() for i in found]
    if tops:
        typical = np.median(tops)
        found = [i for i, top in zip(found, tops) if top > 0.5 * typical]
    # The level crosses the line a little after the shot really begins; walk
    # back to where it first lifts off.
    exact = []
    for i in found:
        top = np.abs(mono[i:i + reach]).max()
        j = i
        while j > max(0, i - int(0.008 * rate)) and fast[j] > 0.08 * top:
            j -= 1
        exact.append(j)
    return exact


def bursts(starts, rate):
    """Shots grouped into bursts: a long silence starts a new one."""
    if len(starts) < 2:
        return [list(starts)]
    gaps = np.diff(starts)
    limit = max(0.25 * rate, 3 * np.median(gaps))
    groups = [[starts[0]]]
    for k, g in enumerate(gaps):
        if g > limit:
            groups.append([])
        groups[-1].append(starts[k + 1])
    return groups


def beat(samples, rate, first, last):
    """The time from one shot to the next, between two moments of a burst.

    Found from the rhythm of the whole stretch rather than from the shots
    one by one: the loudness is slid along itself until the shots line up
    again. (Listening for each shot's start is not enough. A machine gun's
    recording swings so far each shot that one shot can pass for two.)
    """
    level = np.abs(samples[first:last].mean(axis=1))
    span = max(4, int(0.004 * rate))
    level = np.convolve(level, np.ones(span) / span, "same")
    level -= level.mean()
    spectrum = np.fft.rfft(level, n=2 * len(level))
    match = np.fft.irfft(spectrum * np.conj(spectrum))[:len(level)]
    lo, hi = int(0.035 * rate), int(0.16 * rate)
    return lo + int(np.argmax(match[lo:hi]))


def shots_in(samples, rate):
    """Where every shot of the recording's longest burst starts, in samples.

    The first shot is easy to hear, coming out of silence. Each one after
    it is looked for a beat later, at the place where the sound matches the
    start of the second shot best, so that every mark is at the same point
    of a shot. A loop from one mark to another then joins without a bump.
    """
    mono = samples.mean(axis=1)
    run = max(bursts(onsets(samples, rate), rate), key=len)
    first = run[0]
    step = beat(samples, rate, first, run[-1])
    window = int(0.03 * rate)
    slack = int(0.010 * rate)

    def best_near(guess, like):
        scores = []
        for at in range(guess - slack, guess + slack + 1):
            piece = mono[at:at + window]
            if at < 0 or len(piece) < window:
                scores.append(-1.0)
                continue
            scores.append(float(np.dot(like, piece)) / (np.linalg.norm(like) * np.linalg.norm(piece) + 1e-12))
        return guess - slack + int(np.argmax(scores))

    # The second shot is the pattern: the first can differ, starting from rest.
    second = first + step
    pattern = mono[second:second + window]
    second = best_near(second, pattern)
    pattern = mono[second:second + window]
    marks = [first, second]
    loud = np.sqrt((mono[second:second + step] ** 2).mean())
    while True:
        nxt = best_near(marks[-1] + step, pattern)
        piece = mono[nxt:nxt + step]
        if len(piece) < step or np.sqrt((piece ** 2).mean()) < 0.45 * loud:
            break
        marks.append(nxt)
    return marks


def main():
    source, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    marks = {}

    print("%-8s %5s  %7s  %7s %7s %7s  %6s  %s" % ("weapon", "shots", "period", "start", "loop", "last", "rpm", "recording"))
    for name, recording in WEAPONS.items():
        target = os.path.join(out, "%s_fire.wav" % name)
        unpack(find(source, recording), target)
        samples, rate = read(target)
        run = shots_in(samples, rate)
        assert len(run) >= 4, "%s: too few shots found to loop" % name
        # The second shot to the last is what repeats: a whole number of
        # shots, so the rhythm carries on unbroken.
        loop_begin, loop_end = run[1], run[-1]
        shots_looped = len(run) - 2
        period = (loop_end - loop_begin) / shots_looped / rate
        m = {
            "start": round(max(0.0, run[0] / rate - LEAD), 5),
            "loop_begin": round(loop_begin / rate, 5),
            "loop_end": round(loop_end / rate, 5),
            "last_shot": round(run[-1] / rate, 5),
            "period": round(period, 5),
            "length": round(len(samples) / rate, 3),
        }
        marks["%s_fire" % name] = m
        print("%-8s %5d  %.4fs  %6.3fs %6.3fs %6.3fs  %6.0f  %s (%d Hz, %d ch)" % (
            name, shots_looped + 1, period, m["start"], m["loop_begin"], m["last_shot"], 60.0 / period,
            recording[:34], rate, samples.shape[1]))

    with open(os.path.join(out, "fire_marks.json"), "w") as f:
        json.dump(marks, f, indent="\t", sort_keys=True)
        f.write("\n")

    scratch = os.path.join(out, "_unpacked.wav")
    for name, takes in ENEMIES.items():
        for n, (recording, seconds) in enumerate(takes):
            unpack(find(source, recording), scratch)
            samples, rate = read(scratch)
            groups = bursts(onsets(samples, rate), rate)
            # Each burst's last shot dies away with nothing fired over it;
            # take the one with the most quiet after it.
            best = None
            for g, group in enumerate(groups):
                end = groups[g + 1][0] if g + 1 < len(groups) else len(samples)
                if best is None or end - group[-1] > best[1] - best[0]:
                    best = (group[-1], end)
            start = max(0, best[0] - int(0.003 * rate))
            end = min(best[1], best[0] + int(seconds * rate))
            piece = samples[start:end].copy()
            # Only where the recording runs on past the cut: fade its end.
            fade = min(len(piece) // 3, int(0.25 * rate))
            piece[-fade:] *= np.linspace(1.0, 0.0, fade)[:, None]
            write(os.path.join(out, "%s_shot%d.wav" % (name, n + 1)), piece, rate)
            print("%-13s take %d: %.2fs cut from %s" % (name, n + 1, len(piece) / rate, recording[:40]))
    os.remove(scratch)

    for name, recording in RELOADS.items():
        unpack(find(source, recording), scratch)
        samples, rate = read(scratch)
        level = np.abs(samples).max(axis=1)
        live = np.where(level > 0.01 * level.max())[0]
        # Only the silence before the first sound is left off.
        piece = samples[max(0, live[0] - int(0.003 * rate)):]
        write(os.path.join(out, name + ".wav"), piece, rate)
        print("%-13s %.2fs" % (name, len(piece) / rate))
    os.remove(scratch)


if __name__ == "__main__":
    main()
