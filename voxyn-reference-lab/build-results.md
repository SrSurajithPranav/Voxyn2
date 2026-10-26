# Build Results & Execution Diagnostics

This report records the actual commands executed, environment diagnostics, build outputs, and failure logs across VOXYN and the five reference projects.

---

## 1. Host Environment Diagnostics

- **Operating System:** Windows 11 (AMD64 / x86_64)
- **JDK:** OpenJDK 17.0.20 Temurin (Build `17.0.20+8`, 64-Bit Server VM)
- **Android SDK Location:** `C:\Users\srsur\AppData\Local\Android\Sdk`
- **Gradle Daemons Active:** Gradle 8.3.2 (VOXYN) and Gradle 8.13 (Friday Mobile)

---

## 2. VOXYN (`Voxyn2`) Build & Test Results

### Command 1: Initial Test Execution
```powershell
CWD: c:\Users\srsur\Downloads\Voxyn\Voxyn2
CommandLine: .\gradlew.bat testDebugUnitTest
```
- **Exit Code:** `1` (Failure)
- **Cause:** Android SDK location not detected by Gradle because `local.properties` was missing and `ANDROID_HOME` was unset.
- **Log Extract:**
  ```
  FAILURE: Build failed with an exception.
  * What went wrong:
  Could not determine the dependencies of task ':app:testDebugUnitTest'.
  > SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable
    or by setting the sdk.dir path in your project's local properties file at
    'C:\Users\srsur\Downloads\Voxyn\Voxyn2\local.properties'.
  ```

### Command 2: Execution with `ANDROID_HOME` Environment Variable
```powershell
CWD: c:\Users\srsur\Downloads\Voxyn\Voxyn2
CommandLine: $env:ANDROID_HOME="C:\Users\srsur\AppData\Local\Android\Sdk"; .\gradlew.bat testDebugUnitTest
```
- **Exit Code:** `0` (Success)
- **Execution Duration:** `1m 49s`
- **Tasks Summary:** `25 actionable tasks: 16 executed, 9 from cache`
- **Unit Tests Passed:**
  - `CorePipelineVerificationTest`: All 12 pipeline resolution tests passed (WhatsApp, ChatGPT phonetic resolution, HungerBox rhotacism, time, world time, news, quantum conversational query, WhatsApp messaging, distance routing, memory save, memory recall, camera commands).
  - `VoiceCommandPlanVerificationTest`: All command planning and speaker gate tests passed (YouTube, Bluetooth, Volume 30%, weather, enrolled speaker vs intruder rejection gate, voice choice switching across 5 voices).
- **Log Extract:**
  ```
  > Task :app:compileDebugKotlin
  w: .../VoiceInputService.kt:290:31 Parameter 'reason' is never used
  > Task :app:compileDebugJavaWithJavac
  > Task :app:compileDebugUnitTestKotlin
  > Task :app:testDebugUnitTest
  BUILD SUCCESSFUL in 1m 49s
  25 actionable tasks: 16 executed, 9 from cache
  ```

---

## 3. Friday Mobile (`friday-mobile`) Build & Test Results

### Command: Test Execution with `ANDROID_HOME`
```powershell
CWD: c:\Users\srsur\Downloads\Voxyn\voxyn-reference-lab\friday-mobile
CommandLine: $env:ANDROID_HOME="C:\Users\srsur\AppData\Local\Android\Sdk"; .\gradlew.bat testDebugUnitTest
```
- **Gradle Version:** Auto-provisioned Gradle `8.13-bin.zip` from `services.gradle.org`.
- **SDK Auto-Provisioning:** Gradle detected missing SDK 35 components and automatically installed:
  - `Android SDK Build-Tools 35 (v35.0.0)` in `C:\Users\srsur\AppData\Local\Android\Sdk\build-tools\35.0.0`
  - `Android SDK Platform 35 (revision 2)` in `C:\Users\srsur\AppData\Local\Android\Sdk\platforms\android-35`
- **Tasks Executed:**
  - `generateDebugBuildConfig`
  - `checkDebugAarMetadata`
  - `processDebugResources`
  - `kspDebugKotlin` / `compileDebugKotlin`
  - `testDebugUnitTest`

---

## 4. Open Jarvis (`open-jarvis`) Build Status

- **Status:** **Cannot build directly via `./gradlew`**
- **Cause:** The repository does not include `gradlew` or the `.gradle/wrapper/` directory.
- **Analysis:**
  - The project declares plugins: `com.android.application`, `org.jetbrains.kotlin.android`, `com.google.devtools.ksp`, and `org.jetbrains.kotlin.plugin.serialization:1.9.20`.
  - Requires `compileSdk = 34` and Java 17.
  - To compile, a Gradle wrapper must be initialized using `gradle wrapper --gradle-version 8.4`.

---

## 5. AI Assistant for Android (`ai-assistant-android`) Build Status

- **Status:** Configured for Gradle
- **Dependencies & Prerequisites:**
  - Requires embedded `libs/sherpa-onnx-1.13.2.aar` (verified present).
  - Expects `local.properties` with `YOUTUBE_API_KEY` and `GROQ_API_KEY` to populate `buildConfigField`. Without these keys, empty strings are substituted, allowing compilation but disabling YouTube music searching and Groq completions.
  - Uses `kapt` for Room database schema compilation.

---

## 6. Sanna (`sannabotapp`) Build Status

- **Status:** React Native Hybrid
- **Toolchain Requirements:**
  - Requires Node.js $\ge 22.11.0$, `pnpm` or `npm`, and Metro bundler.
  - Android native code lives in `android/app/src/main/java/com/sannabot/native` with 27 Kotlin files.
  - Full application build requires `pnpm install` and `react-native run-android`.

---

## 7. AI-Buddy (`ai-buddy`) Build Status

- **Status:** Flutter Multi-Platform
- **Toolchain Requirements:**
  - Requires Flutter SDK (`>=3.0.0 <4.0.0`) and Dart SDK.
  - Android integration is bridged through Flutter platform channels and plugins (`piper_tts_plugin`, `flutter_secure_storage`).
  - Standalone build requires `flutter pub get` and `flutter build apk`.
