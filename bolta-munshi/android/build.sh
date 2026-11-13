#!/usr/bin/env bash
# Builds smart-book.apk without Gradle: the app has no dependencies, so the
# SDK's own tools are enough. Needs a JDK plus the Android SDK "platforms;android-34"
# and "build-tools;35.0.0" packages, with ANDROID_HOME pointing at the SDK, and the
# Kotlin compiler (2.0.x) for BolKhata's khata logic, with KOTLIN_HOME pointing at it.
set -euo pipefail

SDK="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK folder}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"
KOTLIN="${KOTLIN_HOME:?Set KOTLIN_HOME to the Kotlin compiler folder (kotlinc)}"
KSTD="$KOTLIN/lib/kotlin-stdlib.jar"
VERSION_CODE=46
VERSION_NAME=5.6

cd "$(dirname "$0")"
rm -rf build && mkdir -p build/assets build/classes build/kclasses build/dex

# the app and the web page share one brain
cp assets/brain.html ../brain.js ../visual.js ../data/bank.js build/assets/
# the voice that comes with the app: one sound file per word (see ../voice-script)
cp ../voice-model/sentences.txt build/assets/   # the sentences for training the offline voice
[ -d ../voice-script/clips ] && mkdir -p build/assets/voice && cp ../voice-script/clips/*.pcm build/assets/voice/

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unaligned.apk -I "$JAR" --manifest AndroidManifest.xml -A build/assets \
  --min-sdk-version 24 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" build/res.zip

# BolKhata's khata logic (bolkhata/src, copied by bolkhata/sync.sh) and the code that runs it by voice
"$KOTLIN/bin/kotlinc" -nowarn -jvm-target 1.8 -cp "$JAR" -d build/kclasses bolkhata/src/*.kt $(find kotlin -name '*.kt') 2>&1 | grep -v "JAVA_TOOL_OPTIONS" || true
[ -f build/kclasses/com/boltamunshi/app/VoiceKhata.class ] || { echo "Kotlin compile failed"; exit 1; }
javac -nowarn -source 8 -target 8 -cp "$JAR:build/kclasses:$KSTD" -d build/classes $(find src -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 24 --output build/dex $(find build/classes build/kclasses -name '*.class') "$KSTD"
(cd build/dex && zip -q -j ../unaligned.apk classes*.dex)

"$BT/zipalign" -f 4 build/unaligned.apk build/aligned.apk
# debug.keystore is a throwaway signing key kept in the repo on purpose: every
# build must be signed with the same key or Android refuses to update the app.
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/smart-book.apk build/aligned.apk
"$BT/apksigner" verify build/smart-book.apk
echo "Built $(pwd)/build/smart-book.apk"
