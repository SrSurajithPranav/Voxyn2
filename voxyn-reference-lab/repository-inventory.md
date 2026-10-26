# Repository Inventory: Open-Source Reference Projects

This inventory provides a comprehensive breakdown of the five open-source AI assistant reference repositories, detailing their codebase structure, technologies, licenses, dependencies, and build characteristics.

---

## 1. Project 1: Open Jarvis

- **Repository:** `https://github.com/tokenarc/open-jarvis.git`
- **License:** MIT License (Copyright © 2024 TokenArc)
- **Primary Language:** Kotlin (100% JVM/Android)
- **UI Framework:** Jetpack Compose (BOM 2023.10.01) + Material 3 + Custom Floating Overlay
- **Dependency Injection:** Manual constructor injection / Service singletons
- **SDK Targets:** `minSdk = 26`, `compileSdk = 34`, `targetSdk = 34`
- **Build System:** Gradle (KSP, Kotlin Serialization, Room compiler)

### Directory Layout
```
open-jarvis/
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/openjarvis/
│       ├── accessibility/     # JarvisAccessibilityService, ScreenReader, ActionExecutor
│       ├── agent/             # AgentCore, ActionPlan, RiskyActionConfirmation, SelfHealingExecutor, ProviderFallbackChain
│       ├── automation/        # UI automation tasks and recipes
│       ├── bridge/            # System service bridges
│       ├── builder/           # Dynamic prompt and plan builders
│       ├── graphify/          # GraphifyDB (Room), UI hierarchy to graph analysis
│       ├── intelligence/      # Contextual awareness engines
│       ├── llm/               # UniversalAdapter, GroqProvider, GeminiProvider, LlamaProvider
│       ├── local/             # DownloadService, ModelManager (GGUF / on-device LLM downloads)
│       ├── mcp/               # Model Context Protocol (MCPClient, MCPManager)
│       ├── skills/            # Modular skill engines
│       ├── ui/                # Compose UI, MainActivity, SettingsActivity, FloatingOverlayWidget
│       ├── vision/            # ML Kit text recognition (OCR) and screenshot analysis
│       ├── voice/             # AndroidSTTEngine, AndroidTTSEngine, VoiceManager
│       └── watch/             # WearOS / companion connectivity
```

### Notable Dependencies & Third-Party Components
- `androidx.room:room-runtime:2.6.1`: Local database for graphified UI and action history.
- `androidx.security:security-crypto:1.1.0-alpha06`: `EncryptedSharedPreferences` with `MasterKey.KeyScheme.AES256_GCM` for secure storage of API keys and risky confirmation preferences.
- `com.squareup.okhttp3:okhttp:4.12.0` & `okhttp-sse:4.12.0`: Streaming Server-Sent Events (SSE) for LLM streaming.
- `androidx.work:work-runtime-ktx:2.9.0`: Background scheduling.
- `com.google.mlkit:text-recognition:16.0.0`: On-device visual OCR for inspecting non-accessible UI nodes.

### Build & Operational Findings
- **Missing Gradle Wrapper:** The repository does not include `gradlew` or the `.gradle/wrapper/` folder in git. Building requires generating wrapper files via `gradle wrapper` or using an installed Gradle binary.
- **Rootless Operation:** 100% rootless; relies strictly on Android Accessibility Service (`JarvisAccessibilityService`) and System Alert Window (`SYSTEM_ALERT_WINDOW`).

---

## 2. Project 2: Friday Mobile

- **Repository:** `https://github.com/n1dlee/friday-mobile.git`
- **License:** MIT License (Copyright © 2026 n1dlee) + CC-BY-4.0 (WeSpeaker VoxCeleb ResNet34 ONNX Model)
- **Primary Language:** Kotlin (100% JVM/Android)
- **UI Framework:** Jetpack Compose (BOM 2024.12.01) + Material 3 + Custom Canvas Reactor Orb
- **Dependency Injection:** **Koin 4.0.0** (`koin-android`, `koin-androidx-compose`)
- **SDK Targets:** `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`
- **Build System:** Gradle 8.13 (KSP, Detekt, Room schema validation, custom R8 verification)

### Directory Layout
```
friday-mobile/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   └── speaker_embedding.onnx   # WeSpeaker ResNet34 float16 speaker verification model (~20MB)
│   └── java/com/friday/ai/
│       ├── agent/            # FridayAgent (multi-turn tool loop), AgentTools, LearnedCommands
│       ├── command/          # CommandExecutor, PhoneActions, PlannerActions, InfoActions, LockPolicy
│       ├── core/             # VoiceProfile, KaldiFbank, DeviceContext, AlarmSetter, ModeStore
│       ├── data/             # FridayDatabase (Room), Migrations, KeystoreCipher, SettingsTransfer
│       ├── di/               # AppModule.kt (pure Koin DSL)
│       ├── domain/           # AssistantRepository, UseCases, Models
│       ├── service/          # FridayWakeWordService, SpeakerEmbedder, WhisperTranscriber, ModeEngine, LockScreen
│       └── ui/               # Compose screens: ChatScreen, Dashboard, Diagnostics, Settings, Modes
```

### Notable Dependencies & Third-Party Components
- `io.insert-koin:koin-android:4.0.0` & `koin-androidx-compose:4.0.0`: Native Kotlin DI matching VOXYN's design.
- `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`: On-device inference for `speaker_embedding.onnx`.
- `com.alphacephei:vosk-android:0.3.47` & `net.java.dev.jna:jna:5.13.0@aar`: Offline wake-word detection.
- `com.googlecode.libphonenumber:libphonenumber:8.13.52`: Offline international phone number formatting and routing.
- `androidx.room:room-runtime:2.6.1`: Strict schema-validated SQLite database with manual migration tracking.
- `com.google.android.gms:play-services-auth:21.6.0`: OAuth authentication for Gmail.

### Build & Operational Findings
- **High Build Discipline:** Includes custom Gradle tasks (`verifyReleaseKeepRules`, `ownerRelease`), Detekt static analysis rules with strict baseline XML, and R8 name preservation verification.
- **Tested Execution:** Uses Robolectric 4.14.1, Turbine 1.2.0 for Flow testing, and MockK 1.13.13.

---

## 3. Project 3: AI Assistant for Android

- **Repository:** `https://github.com/souravanand001/ai-assistant-android.git`
- **License:** MIT License (Copyright © 2025 Sourav Anand)
- **Primary Language:** Kotlin (100% JVM/Android)
- **UI Framework:** Jetpack Compose (BOM) + Material 3 Adaptive Navigation Suite
- **Dependency Injection:** Manual ViewModelFactory & Service composition
- **SDK Targets:** `minSdk = 24`, `compileSdk = 35`, `targetSdk = 35`
- **Build System:** Gradle (Kapt, Kotlin Serialization)

### Directory Layout
```
ai-assistant-android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/app/assistant/
│       ├── AssistActivity.kt         # Assistant entry point (ASSIST, VOICE_COMMAND)
│       ├── MainActivity.kt           # Primary transparent UI host
│       ├── camera/                   # CameraX integration, ScreenCaptureService
│       ├── classifier/               # TextClassifierHelper (MediaPipe Tasks Text)
│       ├── config/                   # Audio and model configurations
│       ├── db/                       # Room database for message history
│       ├── llm/                      # LLM clients (Groq API)
│       ├── speech/                   # AudioHygieneProcessor, VoiceStateMachine, VadIntelligenceProcessor, Sherpa-ONNX
│       ├── translation/              # ML Kit Translate integration
│       ├── tts/                      # Edge-TTS WebSocket client & Android TextToSpeech fallback
│       └── ui/                       # Compose screens, widgets, and dynamic feedback
```

### Notable Dependencies & Third-Party Components
- `libs/sherpa-onnx-1.13.2.aar`: Embedded on-device Sherpa-ONNX ASR engine (exact match with VOXYN's `sherpa-onnx`).
- `com.google.mediapipe:tasks-text`: On-device text classification and intent routing via MediaPipe.
- `androidx.camera:camera-core:1.3.x`: Live visual inspection and QR/barcode scanning via CameraX.
- `com.google.mlkit:translate`: Offline text translation.

### Build & Operational Findings
- **Hardware Audio Effects:** Directly applies `android.media.audiofx.AcousticEchoCanceler`, `NoiseSuppressor`, and `AutomaticGainControl` on the `AudioRecord` session ID.
- **Echo Bleed Protection:** Incorporates a 300ms post-TTS echo suppression window to avoid self-triggering barge-in.

---

## 4. Project 4: Sanna

- **Repository:** `https://github.com/sannabotdev/sannabotapp.git`
- **License:** MIT License (SannaBot Dev)
- **Primary Language:** React Native (TypeScript) + Native Android Kotlin (27 native modules)
- **UI Framework:** React Native 0.84 / NativeWind (Tailwind CSS) / React 19
- **Dependency Injection:** React Native NativeModules registry & Android Service bindings
- **SDK Targets:** Android SDK 34 / 35 (via React Native Gradle plugin)
- **Build System:** React Native CLI, Metro bundler, Gradle

### Directory Layout
```
sannabotapp/
├── android/app/src/main/java/com/sannabot/
│   ├── MainActivity.kt
│   ├── MainApplication.kt
│   └── native/
│       ├── SannaAccessibilityService.kt      # Advanced Accessibility engine (525 lines)
│       ├── WakeWordModule.kt                 # Wake word bridge
│       ├── SpeechModule.kt                   # Native speech recognition bridge
│       ├── TTSModule.kt                      # Native speech synthesis bridge
│       ├── NotificationListenerService.kt    # Intercepts incoming notifications
│       ├── NotificationHeadlessService.kt    # Background headless JS runner
│       ├── SchedulerModule.kt                # Alarms and scheduled background tasks
│       ├── SchedulerHeadlessService.kt       # Background scheduled task runner
│       ├── DeviceQueryModule.kt              # App info, volume, battery status
│       └── BluetoothModule.kt                # Bluetooth connection and state query
├── src/
│   ├── agent/        # Agent loop, planning, and task execution logic
│   ├── audio/        # Audio playback and recording management
│   ├── native/       # TypeScript bindings for all 27 native Kotlin modules
│   └── tools/        # Tool definitions: WhatsApp, Gmail, Calendar, System Knobs
```

### Notable Dependencies & Third-Party Components
- Custom Native Kotlin Modules (27 modules): Pure native Android APIs without external framework bloat for device control.
- `react-native-keychain`: Hardware-backed keystore integration for API credentials.
- `react-native-permissions`: Granular runtime permission orchestration.

### Build & Operational Findings
- **Accessibility Hardening:** `SannaAccessibilityService.kt` features the most complete Android Accessibility implementation examined: node indexing, progressive retries for slow-loading views, gesture fallback (`dispatchGesture`), and automatic filtering of assistant UI elements to prevent recursive perception loops.

---

## 5. Project 5: AI-Buddy

- **Repository:** `https://github.com/Weltenwanderer1/ai-buddy.git`
- **License:** MIT License (Copyright © 2026 Günther Schuch)
- **Primary Language:** Flutter (Dart 3.x) + Android C++/Kotlin plugins
- **UI Framework:** Flutter Material Design 3
- **Dependency Injection:** Provider pattern (`provider: ^6.1.0`)
- **SDK Targets:** Android SDK 34+
- **Build System:** Flutter CLI, Pub, Gradle

### Directory Layout
```
ai-buddy/
├── lib/
│   ├── main.dart
│   ├── models/           # Data models: MemoryRecord, Persona, Tools
│   ├── screens/          # Flutter UI screens: Chat, Settings, Memory Ledger, Proactive Log
│   ├── services/
│   │   ├── contextual_memory_extractor.dart   # Heuristic memory extractor (tiered 1–10)
│   │   ├── memory_ledger.dart                 # Ledger for memory writes and audits
│   │   ├── memory_service.dart                # Long-term, short-term, and core memory
│   │   ├── proactive_engine.dart              # Multi-trigger engine (time, weather, POI, routine)
│   │   ├── proactive_notification_service.dart
│   │   ├── action_policy.dart                 # Security policies for tool execution
│   │   ├── tool_learning_service.dart         # Adaptive tool usage tracking
│   │   ├── piper_tts_service.dart             # Local on-device Piper neural TTS
│   │   ├── offline_stt_service.dart           # Offline speech recognition
│   │   ├── secure_config_service.dart         # Encrypted storage (flutter_secure_storage)
│   │   └── atomic_store.dart                  # Thread-safe write leases and atomic transactions
│   └── tools/            # Concrete tool implementations (calendar, maps, timers, system)
```

### Notable Dependencies & Third-Party Components
- `piper_tts_plugin`: On-device neural text-to-speech engine using Piper (ONNX-based phoneme synthesis).
- `flutter_secure_storage`: Keystore-backed encrypted key-value store.
- `workmanager: ^0.9.0`: Periodic background tasks and proactive triggers.
- `geolocator: ^13.0.2` & `latlong2`: Geofence calculations and proximity-based suggestions.

### Build & Operational Findings
- **Memory & Proactivity Architecture:** Although implemented in Flutter, the architectural patterns in `contextual_memory_extractor.dart` (local pattern matching without LLM tokens) and `proactive_engine.dart` (location/time/weather triggers) represent state-of-the-art designs for autonomous assistant behavior.
