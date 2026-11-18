"""Puts the supplied recordings into the game without altering them.

    python3 tools/build_supplied_sounds.py <folder with the original .mp3 files> assets/sounds

Seven of the weapon recordings are bursts of automatic fire. They are used
as they are: nothing is filtered, equalised, layered, re-pitched or
levelled. A weapon plays its recording while it fires, and the recording's
own ending when it stops. All this script does is

  - unpack each .mp3 to a .wav (the same sound, at the same sample rate and
    with the same channels; a .wav can be looped at a chosen point),
  - find where the shots are, and write down in assets/sounds/fire_marks.json:

        start      just before the first shot: where playing begins
        loop       from the second shot to the last: what repeats while the
                   weapon fires
        last_shot  the last shot: after it the recording dies away, which
                   is what is played when the firing stops
        tail_end   where that ending is over (the next burst on the
                   recording, if it has one)
        period     the time from one shot to the next. A weapon fires at
                   exactly this rate, so each bullet lands on a shot.

The eighth weapon recording is three separate heavy shots rather than a
burst, so its shots are cut out one by one (a cut; the sound is not
touched). The reloads have only the silence before them left off.

Needs numpy, and ffmpeg to unpack the .mp3 files.
"""

import json
import os
import subprocess
import sys
import wave

import numpy as np

## The recordings of automatic fire: a short name for each, the file it is
## (the end of its name), and what the armory calls it.
BURSTS = {
    "mg_a": ("dragon-studio-machine-gun-sfx-631486_1", "MACHINE GUN A"),
    "ak47": ("kave_msri-ak47-machine-gun-burst-sfx-332103_1", "AK-47"),
    "mg_b": ("freesound_community-machine-gun-71417", "MACHINE GUN B"),
    "smg": ("freesound_community-smgshoot2-35768", "SMG"),
    "m249": ("freesound_community-069323_light-machine-gun-m249-39825", "M249"),
    "p90": ("freesound_community-fnp90-burst-100583", "P90"),
    "mg_c": ("dennish18-machine-gun-129928", "MACHINE GUN C"),
}

## The recording that is separate shots: each is cut out as a sound of its own.
SINGLES = {
    "cal50": "freesound_community-080979_heavy-machine-gun-50-caliber-39764",
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

    print("%-6s %5s  %7s  %7s %7s %7s %7s  %5s  %s" % ("name", "shots", "period", "start", "loop", "last", "ends", "rpm", "recording"))
    for name, (recording, label) in BURSTS.items():
        target = os.path.join(out, "fire_%s.wav" % name)
        unpack(find(source, recording), target)
        samples, rate = read(target)
        run = shots_in(samples, rate)
        assert len(run) >= 4, "%s: too few shots found to loop" % name
        # The second shot to the last is what repeats: a whole number of
        # shots, so the rhythm carries on unbroken.
        loop_begin, loop_end = run[1], run[-1]
        period = (loop_end - loop_begin) / (len(run) - 2) / rate
        # The ending runs until the recording's next burst, if there is one.
        later = [o for o in onsets(samples, rate) if o > run[-1] + 0.25 * rate]
        tail_end = (later[0] / rate - 0.03) if later else len(samples) / rate
        m = {
            "label": label,
            "start": round(max(0.0, run[0] / rate - LEAD), 5),
            "loop_begin": round(loop_begin / rate, 5),
            "loop_end": round(loop_end / rate, 5),
            "last_shot": round(run[-1] / rate, 5),
            "tail_end": round(tail_end, 3),
            "period": round(period, 5),
        }
        marks[name] = m
        print("%-6s %5d  %.4fs  %6.3fs %6.3fs %6.3fs %6.3fs  %5.0f  %s (%d Hz, %d ch)" % (
            name, len(run), period, m["start"], m["loop_begin"], m["last_shot"], m["tail_end"], 60.0 / period,
            recording[:30], rate, samples.shape[1]))

    with open(os.path.join(out, "fire_marks.json"), "w") as f:
        json.dump(marks, f, indent="\t", sort_keys=True)
        f.write("\n")

    scratch = os.path.join(out, "_unpacked.wav")
    for name, recording in SINGLES.items():
        unpack(find(source, recording), scratch)
        samples, rate = read(scratch)
        starts = []
        for o in onsets(samples, rate):
            # Two marks close together are the two halves of one shot.
            if not starts or o - starts[-1] > 0.06 * rate:
                starts.append(o)
        take = 0
        for k, shot in enumerate(starts):
            end = starts[k + 1] - int(0.004 * rate) if k + 1 < len(starts) else len(samples)
            # A shot the next one comes straight on top of is no use alone.
            if end - shot < 0.25 * rate:
                continue
            piece = samples[max(0, shot - int(0.003 * rate)): end].copy()
            edge = int(0.004 * rate)
            piece[-edge:] *= np.linspace(1.0, 0.0, edge)[:, None]
            take += 1
            write(os.path.join(out, "%s_shot%d.wav" % (name, take)), piece, rate)
            print("%-6s shot %d: %.2fs, cut from %s" % (name, take, len(piece) / rate, recording[:40]))

    for name, recording in RELOADS.items():
        unpack(find(source, recording), scratch)
        samples, rate = read(scratch)
        level = np.abs(samples).max(axis=1)
        live = np.where(level > 0.01 * level.max())[0]
        # Only the silence before the first sound is left off.
        piece = samples[max(0, live[0] - int(0.003 * rate)):]
        write(os.path.join(out, name + ".wav"), piece, rate)
        print("%-12s %.2fs" % (name, len(piece) / rate))
    os.remove(scratch)


if __name__ == "__main__":
    main()
