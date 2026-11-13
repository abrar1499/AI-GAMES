#!/usr/bin/env python3
"""Copy named top-level Kotlin declarations out of BolKhata's screen files into one helpers file."""
import re, sys, os
src, out, names = sys.argv[1], sys.argv[2], sys.argv[3:]
files = ['Ui.kt', 'Ledger.kt', 'KhataTabs.kt', 'EntryForm.kt', 'BusinessCard.kt', 'Screens.kt', 'MainActivity.kt', 'AppParts.kt', 'AccountView.kt', 'StockScreen.kt', 'Pdf.kt', 'Eleven.kt', 'Flash.kt']
decl = re.compile(r'^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|internal|public|inline|data|enum|sealed|operator|infix|tailrec|const)\s+)*(fun|val|var|class|object|interface|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w<>?, ]*\.)?([A-Za-z_]\w*)')
found, missing = [], []
for name in names:
    hit = None
    for f in files:
        p = os.path.join(src, f)
        if not os.path.exists(p): continue
        lines = open(p, encoding='utf-8').read().split('\n')
        for i, line in enumerate(lines):
            m = decl.match(line)
            if not m or m.group(2) != name: continue
            j = i + 1
            # a top-level item ends where the next one starts at column 0 (a closing brace belongs to it)
            while j < len(lines) and not (lines[j][:1] not in ('', ' ', '\t', '}', ')') ):
                j += 1
            block = '\n'.join(lines[i:j]).rstrip()
            block = re.sub(r'^private ', 'internal ', block)
            hit = (files.index(f), i, f, block); break
        if hit: break
    if hit and hit not in found: found.append(hit)
    else: missing.append(name)
with open(out, 'w', encoding='utf-8') as fh:
    fh.write('// Helpers copied from BolKhata\'s screen files (see tools/extract.py). Do not edit by hand.\n')
    fh.write('package com.bolkhata.app\n\nimport java.text.SimpleDateFormat\nimport java.util.Calendar\nimport java.util.Date\nimport java.util.Locale\nimport kotlin.math.abs\nimport kotlin.math.roundToLong\n\n')
    # keep the source order: a top-level value must come after the values it is built from
    fh.write('\n\n'.join('// from ' + h[2] + '\n' + h[3] for h in sorted(found)) + '\n')
print('copied:', len(found), 'not found:', ' '.join(missing))
