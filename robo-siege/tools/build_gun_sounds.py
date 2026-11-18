"""Turns raw weapon recordings into the shot sounds the game plays.

The recordings are bursts: many shots a few hundredths of a second apart.
The game needs the opposite, ONE shot that rings out, which it then plays
once per bullet at the weapon's own rate. Playing several of those on top of
each other is what a burst is, so the result sounds like the recording.

A single shot is cut like this:

    |<- one shot's body ->|<------- the recording's own tail ------->|
    onset            next onset                              silence

The body is one shot from the middle of a burst, up to the moment the next
one starts. The tail is what the recording does after its LAST shot, which is
the only place a shot is heard dying away with nothing fired over it. The
two are joined at the same moment after the onset, so the join falls where
that shot would have carried on anyway.

Nothing is synthesised and nothing is distorted. The only shaping is:
  - rumble under 45 Hz removed (it is inaudible and wastes loudness),
  - very bass-heavy recordings rebalanced so a phone speaker, which plays
    nothing under about 200 Hz, still has a body to play,
  - the tail faded to true silence, which also takes the hiss off,
  - every weapon brought to the same loudness, with the sharpest peaks
    turned down (not clipped) where that is needed to get there.

    python3 tools/build_gun_sounds.py <folder of mono 44.1k wavs> assets/sounds

Needs numpy. ffmpeg converts the sources first:

    ffmpeg -i in.mp3 -ac 1 -ar 44100 -c:a pcm_s16le out.wav
"""

import os
import sys
import wave

import numpy as np

RATE = 44100

## For each sound: the recording, and how long the shot is left to ring.
## `pitch` under 1 plays it slower, which makes a gun sound bigger. `under`
## lays the low end of a second, fuller recording beneath a thin one, at
## `under_gain` times the shot's own level. `ring` makes a shot that hangs
## at one level die away instead (see `faded`). `level` makes it louder
## than the rest.
##
## The first four are the trooper's weapons. The other three are what the
## robots' fire sounds like, each kind its own, so every recording is heard
## somewhere in the fight.
WEAPONS = {
    "rifle": {"file": "dragon-studio-machine-gun-sfx-631486_1", "tail": 0.55},
    "ak": {"file": "kave_msri-ak47-machine-gun-burst-sfx-332103_1", "tail": 0.60,
           "under": "dragon-studio-machine-gun-sfx-631486_1", "under_gain": 0.55},
    "carbine": {"file": "freesound_community-fnp90-burst-100583", "tail": 0.40},
    "heavy": {"file": "freesound_community-069323_light-machine-gun-m249-39825", "tail": 0.50},
    "enemy_scout": {"file": "freesound_community-machine-gun-71417", "tail": 0.36},
    "enemy_gunner": {"file": "freesound_community-080979_heavy-machine-gun-50-caliber-39764", "tail": 0.70,
                     "under": "dennish18-machine-gun-129928", "under_gain": 1.0, "pitch": 0.92, "ring": 0.22},
    "enemy_boss": {"file": "freesound_community-smgshoot2-35768", "tail": 0.70, "pitch": 0.80, "ring": 0.20,
                   "under": "dennish18-machine-gun-129928", "under_gain": 0.9, "level": 1.3},
}

## The two reload recordings: a light one and a heavy one.
RELOADS = {
    "reload": "beetpro-load-gun-sound-effect-5-11003",
    "reload_heavy": "foisal72-gun-load-475276",
}

TAKES = 4
PRE = int(0.003 * RATE)      # kept before each onset, so the attack is whole
JOIN = int(0.004 * RATE)     # length of the crossfade between body and tail
GUARD = int(0.006 * RATE)    # how far short of the next shot a body stops
RUMBLE = 45.0                # nothing under this is kept: no speaker plays it
PEAK = 0.84                  # no sample goes above this
LOUDNESS = 0.105             # what every weapon is brought to (see `loudness`)


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


# ---------------------------------------------------------------- finding shots

def onsets(a, gap=0.045):
    """Where each shot starts: the level jumps well above what came before."""
    fast = np.convolve(np.abs(a), np.ones(64) / 64, "same")
    peak = fast.max()
    hold = int(gap * RATE)
    found = []
    i = 0
    while i < len(a):
        if fast[i] > 0.30 * peak:
            before = fast[max(0, i - 400):max(1, i - 100)].mean() if i > 400 else 0.0
            if before < 0.6 * fast[i]:
                found.append(i)
                i += hold
                continue
        i += 16
    # A bump in a dying tail can pass for a shot; real ones are all about as
    # loud as each other.
    reach = int(0.02 * RATE)
    tops = [np.abs(a[i:i + reach]).max() for i in found]
    if tops:
        typical = np.median(tops)
        found = [i for i, top in zip(found, tops) if top > 0.5 * typical]
    # The level crosses the line a little after the shot really begins; walk
    # back to where it first lifts off.
    exact = []
    for i in found:
        top = np.abs(a[i:i + reach]).max()
        j = i
        while j > max(0, i - int(0.008 * RATE)) and fast[j] > 0.08 * top:
            j -= 1
        exact.append(j)
    return exact


def bursts(starts):
    """Shots grouped into bursts: a long silence starts a new one."""
    if len(starts) < 2:
        return [list(starts)]
    gaps = np.diff(starts)
    limit = max(0.25 * RATE, 3 * np.median(gaps))
    groups = [[starts[0]]]
    for k, g in enumerate(gaps):
        if g > limit:
            groups.append([])
        groups[-1].append(starts[k + 1])
    return groups


def single_shots(a, tail_seconds, takes):
    """Cuts `takes` single shots out of a recording of bursts."""
    groups = bursts(onsets(a))
    want = int(tail_seconds * RATE)

    # The tail comes from the burst with the most quiet after its last shot.
    best = None
    for n, group in enumerate(groups):
        end = groups[n + 1][0] - PRE if n + 1 < len(groups) else len(a)
        room = end - group[-1]
        if best is None or room > best[1]:
            best = (group[-1], room)
    last, room = best
    length = min(want, room)

    out = [a[max(0, last - PRE): last + length].copy()]

    # Bodies: shots from the middle of bursts, never the first of one.
    middles = []
    for group in groups:
        for k in range(1, len(group) - 1):
            middles.append((group[k], group[k + 1] - group[k]))
    for take in range(1, takes):
        if not middles:
            break
        start, period = middles[(len(middles) * take) // takes]
        # Stop the body a little short, so none of the next shot is in it.
        period -= GUARD
        body = a[start - PRE: start + period - PRE].copy()
        rest = a[last + period - PRE - JOIN: last + length].copy()
        if len(rest) <= JOIN or len(body) <= JOIN:
            continue
        ramp = np.linspace(0.0, np.pi / 2, JOIN)
        body[-JOIN:] = body[-JOIN:] * np.cos(ramp) + rest[:JOIN] * np.sin(ramp)
        out.append(np.concatenate([body, rest[JOIN:]]))
    return out


# ---------------------------------------------------------------- shaping

def shaped(a, curve):
    """Applies a frequency curve with no phase shift."""
    pad = 4096
    padded = np.concatenate([a, np.zeros(pad)])
    spectrum = np.fft.rfft(padded)
    freqs = np.fft.rfftfreq(len(padded), 1.0 / RATE)
    return np.fft.irfft(spectrum * curve(freqs), n=len(padded))[:len(a)]


def above(cut, order=2):
    return lambda f: (f / cut) ** order / np.sqrt(1 + (f / cut) ** (2 * order))


def below(cut, order=2):
    return lambda f: 1 / np.sqrt(1 + (f / cut) ** (2 * order))


def low_shelf(cut, gain_db):
    gain = 10 ** (gain_db / 20)
    return lambda f: 1 + (gain - 1) / (1 + (f / cut) ** 2)


def shares(a):
    """How the shot's energy is split: deep bass, body, crack, and air."""
    power = np.abs(np.fft.rfft(a)) ** 2
    freqs = np.fft.rfftfreq(len(a), 1.0 / RATE)
    total = power.sum() or 1e-12
    edges = [(20, 150), (150, 500), (500, 2500), (2500, 20000)]
    return [power[(freqs >= lo) & (freqs < hi)].sum() / total for lo, hi in edges]


def rebalance(a):
    """Gives a bass-heavy shot a body a small speaker can play.

    Deep bass that is most of a recording is first eased back, then the
    overtones of that bass are added in the octaves above it, which is how
    the ear hears a low note on a speaker too small to move it.
    """
    deep, body, _, _ = shares(a)
    if deep > 0.40:
        cut = -min(8.0, 20 * np.log10(deep / 0.40) + 2.0)
        a = shaped(a, low_shelf(140.0, cut))
        deep, body, _, _ = shares(a)
    if body < 0.22 and deep > 0.15:
        low = shaped(a, below(150.0, 3))
        top = np.abs(low).max() or 1e-9
        overtones = shaped(np.tanh(3.0 * low / top) * top, lambda f: above(160.0, 3)(f) * below(560.0, 2)(f))
        have = np.sqrt((overtones ** 2).sum())
        need = np.sqrt(max(0.0, 0.22 - body) * (a ** 2).sum())
        a = a + overtones * min(1.6, need / max(have, 1e-9))
    return a


def faded(a, ring=0.0):
    """Lets the shot ring, then takes it down to true silence.

    `ring` is for recordings that stay at one level instead of dying away:
    the shot is made to fall to about a third every `ring` seconds.
    """
    n = len(a)
    if ring > 0.0:
        a = a * np.exp(-np.maximum(0.0, np.arange(n) / RATE - 0.03) / ring)
    start = int(n * 0.45)
    envelope = np.ones(n)
    envelope[start:] = 0.5 * (1 + np.cos(np.linspace(0, np.pi, n - start)))
    a = a * envelope
    lead = int(0.0012 * RATE)
    a[:lead] *= np.linspace(0, 1, lead)
    return a


def loudness(a):
    """How loud the shot is where every speaker can play it (250 Hz - 6 kHz)."""
    mid = shaped(a, lambda f: above(250.0, 2)(f) * below(6000.0, 2)(f))
    head = mid[:int(0.12 * RATE)]
    return float(np.sqrt((head ** 2).mean()))


def repitched(a, factor):
    """Plays the shot slower (deeper and bigger) or faster."""
    if abs(factor - 1.0) < 1e-3:
        return a
    at = np.arange(0, len(a) - 1, factor)
    return np.interp(at, np.arange(len(a)), a)


def limited(a, ceiling):
    """Holds the peaks under `ceiling` by turning the level down around them.

    The level is turned down just before a peak and let back up over the
    next few hundredths of a second, so the wave itself is never bent; this
    is a volume change, not distortion.
    """
    reach = int(0.0015 * RATE)
    level = np.abs(a)
    # The loudest sample within `reach` either side of each moment.
    padded = np.concatenate([np.zeros(reach), level, np.zeros(reach)])
    near = np.max(np.lib.stride_tricks.sliding_window_view(padded, 2 * reach + 1), axis=1)
    gain = np.minimum(1.0, ceiling / np.maximum(near, 1e-9))
    # Let the level come back slowly after a peak.
    release = np.exp(-1.0 / (0.035 * RATE))
    for i in range(1, len(gain)):
        gain[i] = min(gain[i], gain[i - 1] * release + (1 - release))
    smooth = np.convolve(gain, np.ones(reach) / reach, "same")
    return a * np.minimum(gain, smooth)


def levelled(a, level=1.0):
    """Brings the shot to the common loudness without ever passing PEAK."""
    gain = min(LOUDNESS * level / max(loudness(a), 1e-9), 2.5 * level)
    return limited(a * gain, PEAK)


# ---------------------------------------------------------------- building

def main():
    source, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    print("%-9s %5s  %-28s  %-28s  %s" % ("weapon", "takes", "before  deep/body/crack/air", "after", "loud  peak"))

    for name, spec in WEAPONS.items():
        recording = shaped(read(os.path.join(source, spec["file"] + ".wav")), above(RUMBLE, 3))
        shots = single_shots(recording, spec["tail"], TAKES)
        under = None
        if "under" in spec:
            donor = shaped(read(os.path.join(source, spec["under"] + ".wav")), above(RUMBLE, 3))
            under = shaped(single_shots(donor, spec["tail"], 1)[0], below(420.0, 2))
        for take, shot in enumerate(shots):
            before = shares(shot)
            if under is not None:
                # A thin crack gets the low boom of a heavier gun underneath.
                n = min(len(shot), len(under))
                weight = spec.get("under_gain", 0.75) * np.abs(shot).max() / max(np.abs(under).max(), 1e-9)
                shot = shot[:n] + under[:n] * weight
            shot = rebalance(shot)
            shot = repitched(shot, spec.get("pitch", 1.0))
            shot = levelled(faded(shot, spec.get("ring", 0.0)), spec.get("level", 1.0))
            write(os.path.join(out, "%s_shot%d.wav" % (name, take + 1)), shot)
            if take == 0:
                print("%-9s %5d  %-28s  %-28s  %.3f %.2f  %.2fs" % (
                    name, len(shots),
                    "/".join("%2.0f" % (100 * s) for s in before),
                    "/".join("%2.0f" % (100 * s) for s in shares(shot)),
                    loudness(shot), np.abs(shot).max(), len(shot) / RATE))
        # Remove takes left over from an earlier build with more of them.
        for extra in range(len(shots) + 1, 9):
            stale = os.path.join(out, "%s_shot%d.wav" % (name, extra))
            if os.path.exists(stale):
                os.remove(stale)

    for name, file in RELOADS.items():
        a = shaped(read(os.path.join(source, file + ".wav")), above(80.0, 2))
        level = np.abs(a)
        live = np.where(level > 0.02 * level.max())[0]
        a = a[max(0, live[0] - 300): min(len(a), live[-1] + int(0.06 * RATE))]
        lead = int(0.002 * RATE)
        a[:lead] *= np.linspace(0, 1, lead)
        end = int(0.04 * RATE)
        a[-end:] *= np.linspace(1, 0, end)
        write(os.path.join(out, name + ".wav"), a / np.abs(a).max() * 0.7)
        print("%-12s %.2fs" % (name, len(a) / RATE))


if __name__ == "__main__":
    main()
