#!/usr/bin/env bash
# Runs the app's tests on the computer (no phone needed):
#   KOTLIN_HOME=... ANDROID_HOME=... bolta-munshi/android/tests/run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$HERE/.."
KOTLIN="${KOTLIN_HOME:?Set KOTLIN_HOME}"; JAR="${ANDROID_HOME:?Set ANDROID_HOME}/platforms/android-34/android.jar"
LIB="$HERE/.lib"; mkdir -p "$LIB"
for u in junit/junit/4.13.2/junit-4.13.2.jar org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar org/json/json/20231013/json-20231013.jar; do
  [ -f "$LIB/$(basename $u)" ] || curl -sSf -o "$LIB/$(basename $u)" "https://repo1.maven.org/maven2/$u"
done
OUT="$(mktemp -d)"; TESTS="$HERE/MemoryTest.kt $HERE/SmartBookTest.kt $HERE/RoundTwoTest.kt $HERE/MyVoiceTest.kt"; CLASSES="com.boltamunshi.app.MemoryTest com.boltamunshi.app.SmartBookTest com.boltamunshi.app.RoundTwoTest com.boltamunshi.app.MyVoiceTest"
"$KOTLIN/bin/kotlinc" -nowarn -cp "$JAR:$LIB/junit-4.13.2.jar" -d "$OUT" $(find "$APP/kotlin" -name '*.kt') $TESTS 2>&1 | grep -E "error:" && exit 1 || true
# the real org.json first: android.jar only has empty stand-ins
java -cp "$OUT:$KOTLIN/lib/kotlin-stdlib.jar:$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar:$LIB/json-20231013.jar:$JAR" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v JAVA_TOOL
