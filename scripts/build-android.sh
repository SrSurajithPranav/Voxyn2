#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.cache/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

bash scripts/setup-android-sdk.sh
./gradlew :app:assembleDebug --no-daemon
echo "Debug APK: app/build/outputs/apk/debug/app-debug.apk"