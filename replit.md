# VOXYN on Replit

This is a Kotlin/Gradle Android app, not a web server. There is no browser preview or long-running run workflow; builds are one-off shell commands. Keep the existing Android project structure.

## Build

JDK 17 is installed through `.replit`. Run `bash scripts/build-android.sh` in the Shell. The script downloads Android command-line tools and SDK 34 into the ignored `.cache/android-sdk` directory if needed, then runs `:app:assembleDebug`. Review and accept Android SDK licenses if the installer prompts. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

For tests after SDK setup: `ANDROID_HOME="$PWD/.cache/android-sdk" ./gradlew :app:testDebugUnitTest --no-daemon`. An Android device or emulator is required to actually run the app; Replit's web preview cannot display it.

The APK build does not need an API key. On-device speech and language models are downloaded by the app at runtime, not bundled in the build; see `docs/MODEL_SETUP.md`.