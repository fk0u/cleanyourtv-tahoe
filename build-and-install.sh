#!/bin/sh
# Builds launcher/ into build/home.apk with the plain Android SDK (no Gradle) and installs it on the TV.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)
JAR=$SDK/platforms/android-34/android.jar
rm -rf build && mkdir -p build/gen build/classes
"$BT/aapt2" compile --dir launcher/res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest launcher/AndroidManifest.xml -A launcher/assets --java build/gen \
  --min-sdk-version 26 --target-sdk-version 34 -o build/unsigned.apk build/res.zip
javac -nowarn -Xlint:none --release 8 -classpath "$JAR" -d build/classes $(find launcher/src build/gen -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output build $(find build/classes -name '*.class')
(cd build && zip -qj unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
KS=$HOME/.android/debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
  -dname "CN=Android Debug" -keyalg RSA -validity 10000 >/dev/null
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out build/home.apk build/aligned.apk
[ "$1" = "--no-install" ] || .venv/bin/python tools/tv.py --install build/home.apk
