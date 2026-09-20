#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ ! -f "./gradlew" ]]; then
  echo "Gradle wrapper not found; cannot run post-merge setup." >&2
  exit 1
fi

# Confirm the configured Java/Gradle toolchain is usable without requiring an
# Android SDK during lightweight workspace merges.
./gradlew --version >/dev/null

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_dir" && -f "local.properties" ]]; then
  sdk_dir="$(sed -n 's/^sdk.dir=//p' local.properties | head -n 1)"
fi

if [[ -n "$sdk_dir" && -d "$sdk_dir" ]]; then
  ./gradlew testDebugUnitTest --no-daemon
else
  echo "Android SDK not configured; skipping SDK-dependent Gradle tests."
fi