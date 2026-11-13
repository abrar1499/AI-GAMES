#!/usr/bin/env bash
# Runs the khata tests on the computer (no phone needed):
#   KOTLIN_HOME=... ANDROID_HOME=... bolta-munshi/android/tests/run.sh [/path/to/BolKhata]
# With a BolKhata checkout given, BolKhata's own tests also run against the logic copied here,
# which shows the copy behaves exactly as BolKhata does.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$HERE/.."
KOTLIN="${KOTLIN_HOME:?Set KOTLIN_HOME}"; JAR="${ANDROID_HOME:?Set ANDROID_HOME}/platforms/android-34/android.jar"
LIB="$HERE/.lib"; mkdir -p "$LIB"
for u in junit/junit/4.13.2/junit-4.13.2.jar org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar org/json/json/20231013/json-20231013.jar; do
  [ -f "$LIB/$(basename $u)" ] || curl -sSf -o "$LIB/$(basename $u)" "https://repo1.maven.org/maven2/$u"
done
OUT="$(mktemp -d)"; TESTS="$HERE/VoiceKhataTest.kt"; CLASSES="com.boltamunshi.app.VoiceKhataTest"
if [ "${1:-}" != "" ]; then
  # BolKhata's tests that need no Android screens or AI services
  for t in AssistantSafetyTest BooksTest CashBookTest DayDeleteTest GoodsTest MoveHistoryTest NativeMemoryTest ParserBankFingerprintTest PartOfDayTest PersonAccountsTest QueryTest RecatTest ReceiptParserTest ReportTest ShortSpokenTest SidesTest SplitTest UnderstandTest UniqueIdsTest NativeChatEngineTest; do
    f="$1/app/src/test/java/com/bolkhata/app/$t.kt"; [ -f "$f" ] && { TESTS="$TESTS $f"; CLASSES="$CLASSES com.bolkhata.app.$t"; }
  done
fi
"$KOTLIN/bin/kotlinc" -nowarn -cp "$JAR:$LIB/junit-4.13.2.jar" -d "$OUT" "$APP"/bolkhata/src/*.kt $(find "$APP/kotlin" -name '*.kt') $TESTS 2>&1 | grep -E "error:" && exit 1 || true
# the real org.json first: android.jar only has empty stand-ins
java -cp "$OUT:$KOTLIN/lib/kotlin-stdlib.jar:$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar:$LIB/json-20231013.jar:$JAR" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v JAVA_TOOL
