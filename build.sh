#!/bin/sh
# Builds out/glass.apk with the Android SDK's own tools (no Gradle), in a few seconds.
# Needs Java 17+ and the Android SDK with platforms;android-34 and build-tools;35.0.0.
#   macOS:   brew install openjdk@17 && brew install --cask android-commandlinetools
#            sdkmanager "platforms;android-34" "build-tools;35.0.0"
# Build approach from FireFrame / TV Detox (MIT).
set -e
cd "$(dirname "$0")"
[ -n "$JAVA_HOME" ] || for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64; do [ -d "$j" ] && JAVA_HOME=$j && break; done
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || for d in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [ -d "$d" ] && SDK=$d && break; done
# The newest build-tools wins: d8 34.0.0 crashes on classes compiled by JDK 21.
BT=$(ls -d "$SDK"/build-tools/3[4-9]* 2>/dev/null | sort -V | tail -1); JAR=$SDK/platforms/android-34/android.jar
[ -f "$JAR" ] && [ -n "$BT" ] || { echo "Android SDK not found. Set ANDROID_HOME, and install platforms;android-34 and build-tools;35.0.0." >&2; exit 1; }

rm -rf out; mkdir -p out/classes out/dex
"$BT/aapt2" compile --dir app/res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest app/AndroidManifest.xml -A app/assets \
  --min-sdk-version 22 --target-sdk-version 34 out/res.zip
# shellcheck disable=SC2046
javac -nowarn -Xlint:-options -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" \
  -d out/classes $(find app/src -name "*.java")
# shellcheck disable=SC2046
"$BT/d8" --min-api 22 --release --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
cp out/base.apk out/unsigned.apk; (cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
# A signing key is made on first build. Keep key.jks: updates must be signed with the same key.
KS=${KEYSTORE:-key.jks}; PASS=${KEYSTORE_PASS:-glasshome}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias glass \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Glass" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --out out/glass.apk out/aligned.apk
echo "Built out/glass.apk ($(du -k out/glass.apk | cut -f1) KB)"
