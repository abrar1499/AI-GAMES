#!/usr/bin/env bash
# Builds bolta-munshi.apk without Gradle: the app has no dependencies, so the
# SDK's own tools are enough. Needs a JDK plus the Android SDK "platforms;android-34"
# and "build-tools;35.0.0" packages, with ANDROID_HOME pointing at the SDK.
set -euo pipefail

SDK="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK folder}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"
VERSION_CODE=29
VERSION_NAME=3.8

cd "$(dirname "$0")"
rm -rf build && mkdir -p build/assets build/classes build/dex

# the app and the web page share one brain
cp assets/brain.html ../brain.js ../visual.js ../data/bank.js build/assets/

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unaligned.apk -I "$JAR" --manifest AndroidManifest.xml -A build/assets \
  --min-sdk-version 24 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" build/res.zip

javac -nowarn -source 8 -target 8 -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 24 --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -j ../unaligned.apk classes.dex)

"$BT/zipalign" -f 4 build/unaligned.apk build/aligned.apk
# debug.keystore is a throwaway signing key kept in the repo on purpose: every
# build must be signed with the same key or Android refuses to update the app.
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/bolta-munshi.apk build/aligned.apk
"$BT/apksigner" verify build/bolta-munshi.apk
echo "Built $(pwd)/build/bolta-munshi.apk"
