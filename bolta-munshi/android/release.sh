#!/usr/bin/env bash
# Publishes the built APK for updates from inside the app:
#   ./release.sh "What's new in one short line"
# Copies build/smart-book.apk to ../releases/ and writes ../releases/latest.json, which
# the app reads (Updater.kt). Commit and push both; installed apps then show the update card.
set -euo pipefail
cd "$(dirname "$0")"
CODE=$(grep -m1 '^VERSION_CODE=' build.sh | cut -d= -f2)
NAME=$(grep -m1 '^VERSION_NAME=' build.sh | cut -d= -f2)
NOTES="${1:-Improvements and fixes}"
mkdir -p ../releases
cp build/smart-book.apk ../releases/smart-book.apk
python3 - "$CODE" "$NAME" "$NOTES" <<'PY'
import json, sys
code, name, notes = int(sys.argv[1]), sys.argv[2], sys.argv[3]
json.dump({"versionCode": code, "versionName": name, "notes": notes,
           "apk": "https://raw.githubusercontent.com/abrar1499/AI-GAMES/claude/new-session-fb21rr/bolta-munshi/releases/smart-book.apk"},
          open("../releases/latest.json", "w"), indent=1)
PY
echo "Released $NAME ($CODE): $NOTES"
