#!/usr/bin/env bash
# Copies BolKhata's khata logic into Bolta Munshi.
#
#   bolta-munshi/android/bolkhata/sync.sh /path/to/BolKhata
#
# Takes the logic files that have no screens in them, leaves out the ones that talk to
# AI services (Bolta Munshi has its own teacher), and copies the few small helpers the
# logic borrows from the screen files into src/Helpers.kt. Run it again whenever
# BolKhata changes, then build and run the khata tests.
set -euo pipefail
BK="${1:?Give the path to a BolKhata checkout}"
SRC="$BK/app/src/main/java/com/bolkhata/app"
HERE="$(cd "$(dirname "$0")" && pwd)"
rm -f "$HERE"/src/*.kt
for f in "$SRC"/*.kt; do
  name="$(basename "$f")"
  case "$name" in Ai.kt|AiAgent.kt|Groq.kt|Eleven.kt|Ask.kt) continue ;; esac
  # no Android screens or services, except Model.kt whose one Android import is only for its own storage
  if [ "$name" = Model.kt ] || ! grep -q '^import android\|^import androidx' "$f"; then cp "$f" "$HERE/src/"; fi
done
rm -f "$HERE/src/ChatLog.kt"     # uploads chat logs; not used here
python3 "$HERE/tools/extract.py" "$SRC" "$HERE/src/Helpers.kt" $(cat "$HERE/tools/helpers.txt")
python3 "$HERE/tools/item_words.py" "$SRC/Parser.kt" "$HERE/../kotlin/com/boltamunshi/app/ItemWords.kt"
( cd "$BK" && git rev-parse HEAD 2>/dev/null ) > "$HERE/BOLKHATA_VERSION" || true
echo "Copied $(ls "$HERE"/src/*.kt | wc -l) files from BolKhata $(cat "$HERE/BOLKHATA_VERSION")"
