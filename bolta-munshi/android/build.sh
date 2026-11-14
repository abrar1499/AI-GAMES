#!/usr/bin/env bash
# Builds smart-book.apk without Gradle: the app has no dependencies, so the
# SDK's own tools are enough. Needs a JDK plus the Android SDK "platforms;android-34"
# and "build-tools;35.0.0" packages, with ANDROID_HOME pointing at the SDK, and the
# Kotlin compiler (2.0.x), with KOTLIN_HOME pointing at it.
set -euo pipefail

SDK="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK folder}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"
KOTLIN="${KOTLIN_HOME:?Set KOTLIN_HOME to the Kotlin compiler folder (kotlinc)}"
KSTD="$KOTLIN/lib/kotlin-stdlib.jar"
VERSION_CODE=67
VERSION_NAME=8.1

cd "$(dirname "$0")"
rm -rf build && mkdir -p build/assets build/classes build/kclasses build/dex

# the home screen's picture (the brain animation, shared with the web page)
cp assets/home.html ../visual.js build/assets/

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unaligned.apk -I "$JAR" --manifest AndroidManifest.xml -A build/assets \
  --min-sdk-version 24 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" build/res.zip

# the whole app is Kotlin (legacy/ keeps the old app for reference; it is not built)
"$KOTLIN/bin/kotlinc" -nowarn -jvm-target 1.8 -cp "$JAR" -d build/kclasses $(find kotlin -name '*.kt') 2>&1 | grep -v "JAVA_TOOL_OPTIONS" || true
[ -f build/kclasses/com/boltamunshi/app/MainActivity.class ] || { echo "Kotlin compile failed"; exit 1; }
"$BT/d8" --lib "$JAR" --min-api 24 --output build/dex $(find build/classes build/kclasses -name '*.class') "$KSTD"
(cd build/dex && zip -q -j ../unaligned.apk classes*.dex)

"$BT/zipalign" -f 4 build/unaligned.apk build/aligned.apk
# debug.keystore is a throwaway signing key kept in the repo on purpose: every
# build must be signed with the same key or Android refuses to update the app.
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/smart-book.apk build/aligned.apk
"$BT/apksigner" verify build/smart-book.apk
echo "Built $(pwd)/build/smart-book.apk"
