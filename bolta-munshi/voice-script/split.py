#!/usr/bin/env python3
"""
Cuts the ElevenLabs audio (one file per part of the word list) into one sound per word.

The words come out of ElevenLabs joined together, so silence alone cannot tell them
apart. A speech recogniser (faster-whisper, run separately: see README) gives the time
of every word it heard; those words are lined up letter by letter with the list, each
list entry gets the time of the letters it matched, and the cut is moved to the
quietest moment nearby. Each piece is then trimmed and brought to the same loudness
the way the app does it with a recording.

  split.py <raw dir with 01.pcm..> <asr.json> <out dir>

Input: 16 kHz mono 16-bit sound. Output: <key>.pcm per word, and report.txt with how
sure each cut is.
"""
import difflib, json, os, sys, unicodedata
import numpy as np

R = 16000
F = R // 100                     # 10 ms frames
HERE = os.path.dirname(os.path.abspath(__file__))
PART = 30


def norm(text):
    """Letters only, with the marks that a recogniser writes either way taken out."""
    t = unicodedata.normalize("NFD", text)
    out = []
    for ch in t:
        if ch in "़ँं्":          # nukta, chandrabindu, anusvara, virama
            continue
        if unicodedata.category(ch)[0] in "LM":
            out.append(ch)
    return "".join(out)


def levels(x):
    n = len(x) // F
    return np.sqrt((x[: n * F].astype(np.float64).reshape(n, F) ** 2).mean(1))


def quietest(lv, t, span=0.12):
    """The quietest 10 ms frame within span seconds of t, as a time."""
    a, b = max(0, int((t - span) * 100)), min(len(lv) - 1, int((t + span) * 100))
    if b <= a:
        return t
    return (a + int(np.argmin(lv[a:b + 1]))) / 100 + 0.005


def tidy(seg):
    """Trim the quiet ends, the same loudness for every word, soft edges (as MyVoice.clean/even do)."""
    lv = levels(seg)
    if len(lv) == 0 or lv.max() < 50:
        return seg[:0]
    gate = lv.max() * 0.06
    on = np.where(lv > gate)[0]
    a, b = max(0, (on[0] - 1) * F), min(len(seg), (on[-1] + 3) * F)
    seg = seg[a:b].astype(np.float64)
    lv = levels(seg.astype(np.int16))
    voiced = lv[lv > lv.max() * 0.25]
    gain = min(4000 / voiced.mean(), 30000 / max(1, np.abs(seg).max()), 6.0)
    seg *= gain
    fade = min(R // 200, len(seg) // 2)
    if fade:
        ramp = np.linspace(0, 1, fade)
        seg[:fade] *= ramp
        seg[-fade:] *= ramp[::-1]
    return np.clip(seg, -32767, 32767).astype("<i2")


def main(raw_dir, asr_path, out_dir):
    rows = [l.rstrip("\n").split("\t") for l in open(os.path.join(HERE, "phrases.tsv"), encoding="utf-8") if l.strip()]
    asr = json.load(open(asr_path, encoding="utf-8"))
    os.makedirs(out_dir, exist_ok=True)
    report = []
    for p in range(1, (len(rows) + PART - 1) // PART + 1):
        n = f"{p:02d}"
        items = rows[(p - 1) * PART: p * PART]
        x = np.fromfile(os.path.join(raw_dir, n + ".pcm"), dtype="<i2")
        lv = levels(x)
        words = [w for w in asr[n] if norm(w[0])]
        # letters of the list and of what was heard, each letter knowing where it came from
        want, want_of = "", []
        for i, (_, hi) in enumerate(items):
            s = norm(hi)
            want += s
            want_of += [i] * len(s)
        heard, heard_time = "", []
        for w, a, b, _ in words:
            s = norm(w)
            for k in range(len(s)):                  # spread a word's time over its letters
                heard_time.append(a + (b - a) * k / len(s))
            heard += s
        heard_time.append(words[-1][2] if words else len(x) / R)
        sm = difflib.SequenceMatcher(None, want, heard, autojunk=False)
        start = [None] * len(items)
        end = [None] * len(items)
        matched = [0] * len(items)
        for a, b, size in sm.get_matching_blocks():
            for k in range(size):
                i = want_of[a + k]
                t0, t1 = heard_time[b + k], heard_time[b + k + 1]
                start[i] = t0 if start[i] is None else min(start[i], t0)
                end[i] = t1 if end[i] is None else max(end[i], t1)
                matched[i] += 1
        # entries the recogniser missed: between their neighbours
        total = len(x) / R
        for i in range(len(items)):
            if start[i] is None:
                prev = end[i - 1] if i > 0 and end[i - 1] is not None else 0.0
                nxt = next((start[j] for j in range(i + 1, len(items)) if start[j] is not None), total)
                start[i], end[i] = prev, nxt
        # each cut at the quietest moment between one entry and the next
        cuts = [0.0]
        for i in range(len(items) - 1):
            mid = (end[i] + start[i + 1]) / 2 if start[i + 1] >= end[i] else (end[i] + start[i + 1]) / 2
            cuts.append(quietest(lv, mid))
        cuts.append(total)
        for i, (ro, hi) in enumerate(items):
            seg = x[int(cuts[i] * R): int(cuts[i + 1] * R)]
            clip = tidy(seg)
            sure = matched[i] / max(1, len(norm(hi)))
            clip.tofile(os.path.join(out_dir, ro.replace(" ", "_") + ".pcm"))
            report.append(f"{n} {i + 1:2d} {sure:4.0%} {cuts[i]:6.2f}-{cuts[i + 1]:6.2f} {len(clip) / R:4.2f}s  {hi}  ({ro})")
        heard_text = " ".join(w[0] for w in words)
        report.append(f"   heard: {heard_text}")
    open(os.path.join(out_dir, "..", "report.txt"), "w", encoding="utf-8").write("\n".join(report) + "\n")
    print("\n".join(report))


if __name__ == "__main__":
    main(*sys.argv[1:4])
