#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.cache/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

tools="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$tools" ]]; then
  archive="$(mktemp --suffix=.zip)"
  unpack="$(mktemp -d)"
  trap 'rm -f "$archive"; rm -rf "$unpack"' EXIT
  curl -fL --retry 3 -o "$archive" \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$archive" -d "$unpack"
  mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
  cp -a "$unpack/cmdline-tools/." "$ANDROID_HOME/cmdline-tools/latest/"
fi

if [[ ! -d "$ANDROID_HOME/platforms/android-34" ||
      ! -d "$ANDROID_HOME/build-tools/34.0.0" ||
      ! -d "$ANDROID_HOME/platform-tools" ]]; then
  echo "Installing Android SDK 34. If prompted for a license, review and accept it to continue."
  "$tools" 'platforms;android-34' 'build-tools;34.0.0' 'platform-tools'
fi

echo "Android SDK ready at $ANDROID_HOME"