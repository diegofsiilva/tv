#!/usr/bin/env bash
# Builds Canais.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK (17+) and the Android SDK (build-tools 34+, platform android-34).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ -d "$SDK/build-tools" ] || { echo "Android SDK not found. Set ANDROID_HOME."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

rm -rf out && mkdir -p out/gen out/classes
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml --java out/gen out/res.zip \
  --min-sdk-version 28 --target-sdk-version 33
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -d out/classes $(find src out/gen -name '*.java')
"$BT/d8" --release --min-api 28 --lib "$AJ" --output out $(find out/classes -name '*.class')
(cd out && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk

# Updates need the same key: keep canais.keystore.
if [ ! -f canais.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore canais.keystore -alias canais -keyalg RSA -keysize 2048 -validity 20000 \
    -storepass canais -keypass canais -dname "CN=Canais" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks canais.keystore --ks-pass pass:canais --key-pass pass:canais --out Canais.apk out/aligned.apk
echo "Built Canais.apk ($(du -h Canais.apk | cut -f1))"
