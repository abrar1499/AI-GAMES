#!/usr/bin/env python3
"""
Cuts ElevenLabs audio made with a pause after every word (elevenlabs-1-second.txt)
into one sound per word.

The pauses split the audio into pieces. Usually one piece is one word, but now and
then ElevenLabs skips a pause (several words in one piece) or adds a sound. Each
piece's words, as a speech recogniser heard them, are matched to the list in order
(the best overall match, so one odd piece cannot shift all the rest), and a piece
holding several words is cut inside at the quietest moments, guided by the
recogniser's word times.

  split_pauses.py <dir with raw/01.pcm, segs.json, asr.json> <out dir>
"""
import difflib, json, os, sys
import numpy as np
from split import R, F, levels, norm, words_of, tidy, place, quietest

HERE = os.path.dirname(os.path.abspath(__file__))


def sim(a, b):
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return difflib.SequenceMatcher(None, a, b, autojunk=False).ratio()


def inner_pauses(lv, a, b, count):
    """The middles of the count longest quiet stretches (at least 120 ms) inside a piece, or None."""
    fa, fb = int(a * 100), int(b * 100)
    w = lv[fa:fb]
    if len(w) == 0:
        return None
    # ElevenLabs sometimes fills a pause with a low hiss instead of silence: quiet is measured against this piece's loudest sound
    quiet = max(np.percentile(lv, 95) * 0.02, 20, w.max() * 0.06)
    runs, i = [], 0
    while i < len(w):
        if w[i] <= quiet:
            j = i
            while j < len(w) and w[j] <= quiet:
                j += 1
            runs.append((j - i, (fa + (i + j) / 2) / 100))
            i = j
        else:
            i += 1
    runs = sorted([r for r in runs if r[0] >= 20], reverse=True)[:count]
    return sorted(t for _, t in runs) if len(runs) == count else None


def main(d, out_dir):
    rows = [l.rstrip("\n").split("\t") for l in open(os.path.join(HERE, "phrases.tsv"), encoding="utf-8") if l.strip()]
    x = np.fromfile(os.path.join(d, "raw", "01.pcm"), dtype="<i2")
    lv = levels(x)
    segs = [(a / 100, b / 100) for a, b in json.load(open(os.path.join(d, "segs.json")))]
    words = [w for w in words_of(json.load(open(os.path.join(d, "asr.json"), encoding="utf-8"))["01"]) if norm(w[0])]
    seg_words = [[w for w in words if a - 0.05 <= (w[1] + w[2]) / 2 <= b + 0.05] for a, b in segs]
    seg_text = [norm("".join(w[0] for w in ws)) for ws in seg_words]
    item_text = [norm(hi) for _, hi in rows]

    # best match of pieces to list entries: a piece is one or more entries in a row, or nothing
    n, m, K = len(rows), len(segs), 10
    INF = 1e9
    cost = np.full((n + 1, m + 1), INF)
    back = {}
    cost[0][0] = 0
    for i in range(n + 1):
        for j in range(m + 1):
            c = cost[i][j]
            if c >= INF:
                continue
            if j < m:                                            # a piece that is no entry
                nc = c + (0.9 if seg_text[j] else 0.2)
                if nc < cost[i][j + 1]:
                    cost[i][j + 1] = nc; back[(i, j + 1)] = (i, j, 0)
            if i < n:                                            # an entry ElevenLabs left out
                nc = c + 1.0
                if nc < cost[i + 1][j]:
                    cost[i + 1][j] = nc; back[(i + 1, j)] = (i, j, -1)
            if j < m:
                for k in range(1, K + 1):
                    if i + k > n:
                        break
                    s = sim("".join(item_text[i:i + k]), seg_text[j])
                    nc = c + (1 - s) * k + 0.15 * (k - 1)
                    # several words in a piece too short to hold them: more likely a word ElevenLabs left out
                    if k > 1 and (segs[j][1] - segs[j][0]) / k < 0.4:
                        nc += 1.0 * (k - 1)
                    if nc < cost[i + k][j + 1]:
                        cost[i + k][j + 1] = nc; back[(i + k, j + 1)] = (i, j, k)
    # walk back
    plan, i, j = [], n, m
    while (i, j) != (0, 0):
        pi, pj, k = back[(i, j)]
        plan.append((pi, pj, k))
        i, j = pi, pj
    plan.reverse()

    os.makedirs(out_dir, exist_ok=True)
    report, missing = [], []
    for i, j, k in plan:
        if k == -1:
            missing.append(i)
            report.append(f"{i + 1:3d}  MISSING   {rows[i][1]}  ({rows[i][0]})")
            continue
        if k == 0:
            continue
        a, b = segs[j]
        items = rows[i:i + k]
        if k > 1:
            # an entry of a joined piece of which the recogniser heard nothing was most likely not said at all
            heard_any = place(items, seg_words[j], b)[2]
            keep = [q for q in range(k) if heard_any[q] > 0]
            for q in range(k):
                if q not in keep:
                    missing.append(i + q)
                    report.append(f"{i + q + 1:3d}  MISSING   {items[q][1]}  ({items[q][0]})")
            if keep:
                first = keep[0]
                items = [items[q] for q in keep]
                i, k = i + first, len(items)   # numbering below is only for the report
        if k == 0:
            continue
        if k == 1:
            cuts = [a - 0.03, b + 0.06]
        elif inner_pauses(lv, a, b, k - 1):                     # several entries in one piece, with short pauses between them
            cuts = [a - 0.03] + inner_pauses(lv, a, b, k - 1) + [b + 0.06]
        else:                                                    # several entries run together: cut where the recogniser heard them
            st, en, matched, _ = place(items, seg_words[j], b)
            for q in range(k):
                if st[q] is None:
                    st[q] = a + (b - a) * q / k; en[q] = a + (b - a) * (q + 1) / k
            cuts = [a - 0.03] + [quietest(lv, (en[q] + st[q + 1]) / 2, 0.15) for q in range(k - 1)] + [b + 0.06]
        for q, (ro, hi) in enumerate(items):
            seg = x[max(0, int(cuts[q] * R)): int(cuts[q + 1] * R)]
            clip = tidy(seg)
            clip.tofile(os.path.join(out_dir, ro.replace(" ", "_") + ".pcm"))
            heard = "".join(w[0] for w in seg_words[j]) if k == 1 else "(piece of %d)" % k
            s = sim(norm(hi), seg_text[j]) if k == 1 else sim("".join(item_text[i:i + k]), seg_text[j])
            report.append(f"{i + q + 1:3d} {s:4.0%} {cuts[q]:7.2f}-{cuts[q + 1]:7.2f} {len(clip) / R:4.2f}s  {hi}  ({ro})  heard: {heard}")
    open(os.path.join(out_dir, "..", "report.txt"), "w", encoding="utf-8").write("\n".join(report) + "\n")
    print("\n".join(report))


if __name__ == "__main__":
    main(*sys.argv[1:3])
