# VOXYN — Technical Requirements Document (TRD)

Version: 1.0.19 (versionCode 20) / Status: Living / Owner: VOXYN Core

## 1. System overview

```
                     ┌──────────────────────────────────────────────┐
                     │                 VOXYN process                 │
                     │                                              │
  always-on          │  WakeWordService (Vosk wake grammar)        │
  ──────────────────▶│  notification: "VOXYN — say Hey VOXYN"       │
                     │       │ wake phrase                          │
                     │       ▼                                     │
                     │  activation TTS → start VoiceInputService   │
                     │                                              │
  command            │  VoiceInputService (foreground, mic)        │
  ──────────────────▶│  SpeechRecognizerEngine                     │
                     │    ├─ SherpaParakeetRecognizer  (primary)   │
                     │    └─ VoskCommandRecognizer      (fallback) │
                     │       │ transcript                           │
                     │       ▼                                     │
                     │  SpeechNormalizer → RequestRouter           │
                     │       ▼                                     │
                     │  VoxynEngine (pipeline)                     │
                     │    IntentRouter → ExecutionController       │
                     │    LocalLlmProvider / KnowledgeProvider     │
                     │    ConversationSession (30 s window)        │
                     │       │                                     │
                     │       ▼                                     │
                     │  TextToSpeechManager (never while mic open) │
                     │       │                                     │
                     │       ▼                                     │
                     │  Action executed + spoken result            │
                     └──────────────────────────────────────────────┘
```

## 2. Stack

- Kotlin 17, Android `minSdk 26`, `compileSdk/targetSdk 34`, `ABI arm64-v8a`.
- Jetpack Compose UI (BOM), single Activity (`MainActivity`), no fragments.
- **Koin** DI (no Hilt), no Firebase, no `println` (VoxynLogger only).
- Vosk (wake word), sherpa-onnx + NVIDIA Parakeet-TDT 0.6B v3 INT8 (command STT),
  MediaPipe GenAI `LlmInference` (local LLM), Android TTS.
- Preferences DataStore (`voxyn_memory`) backs `MemoryRepository` (trust scores
  + JSON notes); `ConversationSession` is in-memory.

## 3. Module map (app/src/main/java/com/voxyn)

| Package / file | Responsibility |
| --- | --- |
| `VoxynApplication` | Koin init, crash handler, auto-start wake service, background model restore (no heavy pre-load) |
| `MainActivity` | Permissions, overlay prompt, All-files access prompt, model restore trigger, hosts Compose app |
| `ui/` (`MainScreen`, `VoxynViewModel`) | Dashboard: header, phase bar, log panel, AI/STT panels, command input + mic button, storage dialog |
| `wake/WakeWordService` | Always-on Vosk wake detection (foreground, mic), activation phrase, starts `VoiceInputService`, resumes on `ACTION_RESUME` |
| `wake/VoskModelManager`, `voice/VoskModelProvider` | Vosk model extraction/load from assets `model-en-us/` |
| `voice/VoiceInputService` | Foreground listening service: 8 s silence timeout, 2 attempts, session window, notifications, wake resume |
| `voice/ConversationSession` | 30 s conversation window, turn history (last 3), `getContext()` |
| `voice/SherpaParakeetRecognizer` | Parakeet STT: AudioRecord 16 kHz → Silero VAD segments → OfflineRecognizer |
| `voice/VoskCommandRecognizer` | Vosk command fallback recognizer (same engine interface) |
| `voice/ParakeetModelManager` | ASR model presence/restore/download (MediaStore + raw path), cached `OfflineRecognizer` |
| `voice/TextToSpeechManager` | TTS with audio-focus protocol (release before TTS, regain after) |
| `engine/VoxynEngine` | Pipeline orchestrator, TTS personality, confirmation, context, memory learning |
| `intent/IntentRouter` | Regex intent table → `IntentResult(action, params, confidence)`; chain splitting ("and"/"then") |
| `router/RequestRouter` | Fast-command vs conversational classification |
| `perception/` | `ContextEngine`, `SpeechNormalizer` (rhotacism + app-name fixes) |
| `ai/` | `LocalLlmProvider`, `LocalKnowledgeProvider`, `KnowledgeProvider`, `WorldTimeProvider`, `ClaudeIntentResolver` (optional), `LocalModelManager` (LLM files + download) |
| `execution/ExecutionController` | Executes all action types; permission-gated fallback messages |
| `execution/Capability*`, `mode/ModeEngine`, `apps/AppCatalog` | Capability providers, mode engine, installed-app catalog (fuzzy open) |
| `memory/MemoryRepository` | Trust-score memory per action |
| `overlay/` (`OverlayService`, `DynamicIsland`) | Dynamic-island floating pill (top-center) |
| `perception/VoxynAccessibilityService` | Accessibility actions (scroll, like, send current message, notifications, remember) |
| `receiver/BootReceiver` | Restart services on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` |
| `utils/VoxynLogger` | Logcat + optional HTTP sink + `/sdcard/Download/voxyn-debug.log` |

## 4. Microphone ownership protocol

Exactly one mic owner at a time (single AudioRecord source):

1. **IDLE**: `WakeWordService` owns the mic (Vosk). On wake phrase:
   stop Vosk, release mic, speak activation, start `VoiceInputService`.
2. **LISTEN**: `VoiceInputService` owns the mic (Parakeet or Vosk command).
   TTS must NEVER speak while the mic is open.
3. **RESUME**: `VoiceInputService` sends `ACTION_RESUME` → `WakeWordService`
   restarts Vosk (mic returns to wake owner).

## 5. Conversation state machine

```
IDLE ──wake phrase──▶ WAKING (speak "Yes?" etc., pre-warm STT)
  ▲                         │
  │                     LISTEN (8 s silence timeout, 2 attempts)
  │                         │ transcript
  │                     PROCESS (filler TTS, engine pipeline ≤ 60 s)
  │                         │
  │                     ACT (execute + result TTS)
  │                         │
  │              ┌──────────┴───────────┐
  │      within 30 s              window expired /
  │      (no wake phrase)         2 failed listens
  │              │                    │
  │          LISTEN            "Standing by." TTS
  │                                    │
  └────── ACTION_RESUME ───────────────┘
```

- `ConversationSession.isActive=true` after first command; `isWithinWindow()` =
  active AND `now - lastInteractionTime < 30_000`.
- Follow-up inside window skips wake phrase; on success the window timestamp
  refreshes; 2 consecutive no-speech failures end the session.

## 6. Services, actions & notifications

| Service | Actions (intent extras / action strings) |
| --- | --- |
| `WakeWordService` | `ACTION_START`, `ACTION_STOP`, `ACTION_RESUME`, `ACTION_START_LISTENING` |
| `VoiceInputService` | `ACTION_START_LISTENING`, `ACTION_STOP_LISTENING` |

Notifications:
- Wake: "VOXYN — say Hey VOXYN" (idle) / "VOXYN — waking up..." (transient).
- Voice: "VOXYN — speak your command" → "VOXYN — listening..." → partial text →
  "Processing..." → "VOXYN — go on, I'm listening" → "VOXYN — say Hey VOXYN".

## 7. Intent → action table (IntentRouter)

| Intent | Sample triggers | Params |
| --- | --- | --- |
| `SEND_CURRENT_MESSAGE` | "send that" | — |
| `LIKE_CURRENT` / `SCROLL_UP` / `SCROLL_DOWN` / `GO_BACK` / `GO_HOME` | "like this", "scroll down", "go back" | — |
| `READ_NOTIFICATIONS` / `REMEMBER_THIS` | "read notifications", "remember this" | — |
| `MESSAGE_GROUP` / `SEND_UNSUPPORTED_CHANNEL` / `SEND_WHATSAPP_MESSAGE` / `SEND_MESSAGE` / `CONTACT_LOOKUP` | "message X saying …", "whatsapp …", "send text to …" | channel/recipient/body |
| `WIFI_ON/OFF`, `BLUETOOTH_ON/OFF` | "turn on wifi" | — |
| `VOLUME_UP/DOWN`, `BRIGHTNESS_UP/DOWN` | "volume up", "brighter" | — |
| `SILENT_MODE`, `VIBRATE_MODE`, `NORMAL_MODE` | "silent mode" | — |
| `FLASHLIGHT_ON/OFF` | "flashlight on" | — |
| `SCREENSHOT` | "take a screenshot" | — |
| `PLAY_MUSIC`, `PAUSE_MUSIC`, `NEXT_TRACK`, `PREV_TRACK` | "play music", "next track" | — |
| `SET_ALARM` / `SET_TIMER` | "set alarm 7 am" / "set 10 minute timer" | — |
| `BATTERY_SAVER_ON` / `BATTERY_STATUS` | "battery saver on" / "battery status" | — |
| `NAVIGATE` | "navigate to x" | destination |
| `MODE_ACTIVATE` | "driving mode" / "sleep mode" | mode_name |
| `LOCK_PHONE` | "lock the phone" | — |
| `OPEN_APP` | "open YouTube" / "launch insta" | app_name (fuzzy vs AppCatalog) |
| `CALL_CONTACT` | "call mom" | contact |
| `GREETING`, `THANKS`, `TIME`, `DATE`, `CAPABILITIES`, `SMALLTALK` | "hello", "what time is it", "what can you do", "how are you" | — |
| `CHAIN` | "open x and set timer" | JSON step list |
| `UNKNOWN` | fallback (context-aware via Local LLM) | — |

Unknown intents include `ConversationSession.getContext()` in the prompt so
follow-ups resolve in context.

## 8. TTS personality (VoxynEngine)

- Activation: "Yes?", "Listening.", "What do you need?", "Go ahead.", "I'm here."
- Filler before execute: "On it.", "Give me a sec.", "Right away.",
  "Let me handle that.", "Sure thing."
- Result per action: "WiFi's on.", "Flashlight on.", "Opening [app].",
  "Calling [contact] now.", "Alarm set.", "Timer's running.", etc.; default "Done."
- Unknown: "Didn't catch that. Try again?", "Not sure what you mean. Say it differently?",
  "I missed that. What do you need?"
- Window end: "Standing by." Confirmation: "Just to confirm — should I …? Say yes."

## 9. Model lifecycle (ASR + LLM)

Storage:
- App-private: `filesDir/models/asr/` (Parakeet), `filesDir/model/` (Vosk),
  `filesDir/` (LLM `.task`).
- Backup: `/sdcard/Download/VoxynAI/asr/` + `voxyn-llm.task` (raw path),
  mirror in MediaStore Downloads.
- Debug log: `/sdcard/Download/voxyn-debug.log`.

Restore order: private copy → MediaStore Downloads → raw `/sdcard/Download/VoxynAI/`
(needs All-files access) → background download (resumable, size-validated).
The `LargeHeap` flag is set; the 650 MB OfflineRecognizer loads on demand
(wake pre-warm or first listen), never during cold start.

## 10. Data & security

- Audio: never persisted, never streamed. VAD + inference in-process.
- `ConversationSession` context: in-memory only, cleared by `end()`.
- `MemoryRepository` persists command trust scores for learning.
- No analytics SDKs, no Firebase, no cloud storage of conversations.
- Sensitive actions (call, send) gated by permissions and/or confirmation.

## 11. Error handling & fallbacks

| Failure | Behavior |
| --- | --- |
| Parakeet model missing | Vosk command fallback (never silent) |
| First-listen silence | 2 attempts, then back to IDLE |
| Follow-up silence ×2 | "Standing by." → IDLE |
| Unknown command | Spoken retry prompt (randomized) |
| Missing permission | Logged WARN; wake not started; UI prompts |
| LLM load failure / offline | `KnowledgeProvider` → Wikipedia/web fallback, spoken "can't access" |
| App killed | `START_STICKY`; BootReceiver on reboot/update |
| Model download failure | Next mirror; partial `.part` preserved & resumed |

## 12. Build & release

- Build: `./gradlew --no-daemon assembleDebug` after setting
  `ANDROID_HOME`/`JAVA_HOME` (Termux proot).
- Tests: `./gradlew --no-daemon test` (unit tests — intent router, normalizer,
  session, resolver, catalog).
- Version rule: bump `versionCode` +1 per release.
- APK output: `app/build/outputs/apk/debug/app-debug.apk` →
  copied to `/sdcard/Download/voxyn-debug.apk`.
- Debug diagnostics: logcat + `/sdcard/Download/voxyn-debug.log` +
  optional local HTTP sink `127.0.0.1:19090`.
