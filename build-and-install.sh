#!/bin/sh
# Builds the launcher with Gradle and installs it on the TV (TV_HOST must be set).
# ./build-and-install.sh --no-install   just builds launcher/build/outputs/apk/release/launcher-release.apk
set -e
cd "$(dirname "$0")"
[ -f local.properties ] || echo "sdk.dir=${ANDROID_HOME:-$HOME/Library/Android/sdk}" > local.properties
./gradlew -q :launcher:assembleRelease
APK=launcher/build/outputs/apk/release/launcher-release.apk
[ "$1" = "--no-install" ] || .venv/bin/python tools/tv.py --install "$APK"
