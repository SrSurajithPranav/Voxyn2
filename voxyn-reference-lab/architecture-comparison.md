# Architecture Comparison: 15 Core Subsystems

This document provides a side-by-side technical comparison across all 15 core subsystems required for building a robust, rootless, voice-first Android AI assistant. Every finding is grounded in verified source code and contrasts VOXYN's current architecture against the five reference projects.

---

## 1. Wake-Word Detection & Foreground Microphone Services

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Engine** | Vosk (`vosk-android:0.3.75`) | None (Push-to-Talk) | Vosk (`vosk-android:0.3.47` + JNA) | Push-to-Talk / VAD | Vosk native bridge | None (PTT / live mic) |
| **Foreground Service** | `VoiceInputService` (`microphone`) | `OverlayService` (`specialUse`) | `FridayWakeWordService` (`microphone`) | `ScreenCaptureService` (`mediaProjection|mic`) | Native Foreground Service | Background Audio Session |
| **Lifecycle & Audio Routing** | Always-on AudioRecord loop in IO scope | Manual trigger via Compose UI / HUD | Multi-token settle margins (`MIC_SETTLE_MS = 200ms`, `FOCUS_SETTLE_MS = 800ms`) | `AudioHygieneProcessor` routes to `VOICE_COMMUNICATION` / `MODE_IN_COMMUNICATION` | Headless service restart on Android reboot | `audio_session` management in Dart |
| **Call & Media Interruption** | Basic AudioFocus listener | None | `CallWatcher` actively unhooks mic on ringing / phone call / VoIP; re-arms on call end | Basic AudioFocus | Intercepts call events | Audio focus ducking |

### Key Source Evidence & Citations:
- **VOXYN:** [`VoiceInputService.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/voice/VoiceInputService.kt#L100-L160) runs a foreground microphone service with `VoskModelProvider`.
- **Friday Mobile:** [`FridayWakeWordService.kt:L66-70`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/FridayWakeWordService.kt#L66-L70) defines `MIC_SETTLE_MS = 200L` and `FOCUS_SETTLE_MS = 800L`. [`CallWatcher.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/voice/CallWatcher.kt) monitors telephony and VoIP state.
- **AI Assistant Android:** [`AudioHygieneProcessor.kt:L53-58`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/speech/AudioHygieneProcessor.kt#L53-L58) explicitly configures `MediaRecorder.AudioSource.VOICE_COMMUNICATION`.

---

## 2. Speech-to-Text (STT), Voice Activity Detection (VAD) & Text-to-Speech (TTS)

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Primary STT** | On-device Sherpa-ONNX (NVIDIA Parakeet-TDT 0.6B v3 INT8) | Android System `SpeechRecognizer` | Cloud Groq Whisper API | Sherpa-ONNX 1.13.2 embedded `.aar` | Native Android `SpeechRecognizer` | Offline STT plugin / Whisper API |
| **VAD Implementation** | Vosk partial energy thresholding | Android built-in speech detector | Vosk sentence endpointing (`endpointModel`) | Silero VAD / `VadIntelligenceProcessor` | Android SpeechRecognizer silence timeouts | Silero VAD plugin |
| **TTS Engine** | Android `TextToSpeech` (5 preset voices: Jarvis, Friday, Cadence, Expressive, Default) | Android `TextToSpeech` | Microsoft Edge-TTS with prefetching (`EdgeTtsSpeaker`) | Microsoft Edge-TTS WebSocket client | Android `TextToSpeech` | Local Piper Neural TTS (ONNX) + Android TTS |
| **Audio Hygiene** | Basic short-to-float normalization | None | KaldiFbank mean subtraction | Hardware `AcousticEchoCanceler`, `NoiseSuppressor`, `AutomaticGainControl` | None | Basic stream normalization |

### Key Source Evidence & Citations:
- **VOXYN:** [`SherpaParakeetRecognizer.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/voice/SherpaParakeetRecognizer.kt) streams 16kHz audio to an offline CTC greedy recognizer.
- **AI Assistant Android:** [`AudioHygieneProcessor.kt:L137-179`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/speech/AudioHygieneProcessor.kt#L137-L179) hooks hardware AEC and NoiseSuppressor directly onto the audio session ID.
- **Friday Mobile:** [`EdgeTtsSpeaker.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/voice/EdgeTtsSpeaker.kt) prefetches common acknowledgements for instantaneous playback without cloud lag.
- **AI-Buddy:** [`piper_tts_service.dart`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-buddy/lib/services/piper_tts_service.dart) loads on-device ONNX neural voices for natural, zero-latency offline synthesis.

---

## 3. Speaker Verification & Voice-Session State Machines

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Speaker Model** | Custom synthetic tone / acoustic feature extraction | None | WeSpeaker `voxceleb_resnet34_LM` (float16 ONNX, 256-d embedding) | None | None | None |
| **Inference Runtime** | Pure JVM mathematical transforms | N/A | Microsoft ONNX Runtime Android (`1.20.0`) | N/A | N/A | N/A |
| **Gate Policy** | `CommandVerificationGate` checks cosine similarity against enrolled profile | None | `SpeakerGate` checks text-independent embeddings; guards sensitive lock-screen commands | None | None | None |
| **Session State Machine** | `VoiceSessionCoordinator` + `ConversationSession` | Simple `VoiceState` (`Idle, Recording, Transcribing, Result, Error`) | `VoiceConversation` + `VoiceSession` | Explicit `VoiceStateMachine` (`IDLE, LISTENING, PROCESSING, BOT_SPEAKING`) | Event-driven React Native bridge | RxDart stream state |
| **Barge-in / Echo Bleed** | Mic paused while speaking | No interruption logic | Mic paused during TTS, resumes after settle margin | 300ms post-TTS echo bleed window, immediate TTS cancellation on user voice | No echo cancellation | Manual interruption |

### Key Source Evidence & Citations:
- **Friday Mobile:** [`SpeakerEmbedder.kt:L1-108`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/SpeakerEmbedder.kt#L1-L108) computes Kaldi mel-filterbanks, normalizes mean, and executes ResNet34 ONNX inference in a single-threaded session.
- **AI Assistant Android:** [`VoiceStateMachine.kt:L44-65`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/speech/VoiceStateMachine.kt#L44-L65) implements echo bleed suppression (`elapsed < 300ms`) and instant barge-in (`callback.stopTtsPlayback()`).

---

## 4. Intent Classification & Command Routing

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Routing Architecture** | Deterministic 2-stage: `RequestRouter` (RouteType) $\rightarrow$ `IntentRouter` (Regex + Phonetic Map) | Graph-based UI matching + LLM prompt routing | Rule-based phrase matching $\rightarrow$ `FridayAgent` LLM tool calling | MediaPipe `TextClassifierHelper` $\rightarrow$ Groq LLM | LLM-first with JSON system prompts | Local pattern matching $\rightarrow$ LLM Tool routing |
| **Phonetic Normalization** | `SpeechNormalizer` with rhotacism & app name resolution | Regex cleansing | Russian inflected noun/verb stemmer | Basic string trim | None | Multi-lingual heuristic cleaner |
| **Fast Path Latency** | $<5\text{ms}$ local regex matching | N/A (Requires LLM API call) | $<2\text{ms}$ local regex/matcher | $<10\text{ms}$ MediaPipe local embedding | N/A (Cloud LLM) | $<5\text{ms}$ pattern matcher |
| **Smart Chaining** | Dedicated `CHAIN` command parsing sequential actions | LLM multi-step plan generation | Multi-command regex splitter | Single-step execution | Multi-step agent loop | Plan graph execution |

### Key Source Evidence & Citations:
- **VOXYN:** [`IntentRouter.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/intent/IntentRouter.kt) handles over 40 native Android actions deterministically without touching an LLM.
- **VOXYN:** [`VoxynEngine.kt:L144-160`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/engine/VoxynEngine.kt#L144-L160) evaluates `RequestRouter` for `MEMORY_SAVE`, `WEATHER`, `DISTANCE`, `CURRENT_INFORMATION`, and `CONVERSATIONAL` routes.

---

## 5. LLM Provider Abstractions, Model Selection & Fallback

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Local LLM** | MediaPipe GenAI Tasks (`com.google.mediapipe:tasks-genai`) | Downloadable GGUF models (`DownloadService`) | None (relies on Groq cloud) | None | None | Ollama local bridge |
| **Remote Providers** | Anthropic Claude (`ClaudeIntentResolver`, `ClaudeGeneralProvider`) | Groq, Gemini, OpenRouter, Anthropic, OpenAI | Groq (Llama 3.3 70B Versatile, Llama 3.1 8B Instant) | Groq API | OpenAI, Groq, Anthropic, DeepSeek | Anthropic, Ollama, OpenAI |
| **Fallback Strategy** | Local LLM $\rightarrow$ Web (DuckDuckGo/Wikipedia) $\rightarrow$ Cloud Claude | `ProviderFallbackChain` with cooldown map for 429 & 401 | Rate limit wait ($\le 2500\text{ms}$) $\rightarrow$ backup fast model | None | Single provider with retry | Exponential backoff retry |
| **Context Protocol** | Plain string context window (`ConversationSession`) | Model Context Protocol (`MCPClient`, `MCPManager`) | JSON chat history with sliding window | SQLite message history | JSON chat history | Markdown journal & JSON context |

### Key Source Evidence & Citations:
- **Open Jarvis:** [`ProviderFallbackChain.kt:L21-56`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/ProviderFallbackChain.kt#L21-L56) iterates configured providers, detects HTTP 429 (sets 60s cooldown) and 401 (sets 1-hour cooldown).
- **Friday Mobile:** [`FridayAgent.kt:L87-93`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/agent/FridayAgent.kt#L87-L93) and `L184-196`: if Groq rate limit retry-after is $\le 2500\text{ms}$, it sleeps; otherwise seamlessly switches to `backupModel`.

---

## 6. Agent Planning, Tool Calling & Multi-Step Execution

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Tool Calling Mechanism** | Hardcoded capabilities via `CapabilityRegistry` & `ActionStep` | LLM JSON function calling | Native Groq tool calling (`ToolDefinition`, `ToolCall`) | Hardcoded intent mapping | OpenAI function calling via React Native | OpenAI/Anthropic function calling |
| **Progressive Tool Disclosure** | Static registration | Full tool prompt injection | `ToolKit`: starts with light kit, escalates to `FULL` if model asks | Static single actions | Full tool prompt injection | Dynamic tool registry by category |
| **Hallucination Prevention** | Verifiable post-condition checks in capabilities | `LLMResponseValidator` | `SAYS_ITSELF` policy + `MessageGrounding.isOwnWords()` | None | Basic JSON schema validation | Strict JSON parser & action policy |
| **Multi-turn Planning** | Sequential `ExecutionPlan` (`steps`, `currentStep`) | `ActionPlan` with dynamic step resolution | `MAX_ROUNDS = 4` turn loop with intermediate tool results | Single turn | Sub-agent recursion loop | Multi-phase plan graph |

### Key Source Evidence & Citations:
- **Friday Mobile:** [`FridayAgent.kt:L60`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/agent/FridayAgent.kt#L60) defines `SAYS_ITSELF = setOf("send_message", "call", "reply_message")`—critical safety rule preventing the LLM from summarizing or hallucinating transmission results.
- **Friday Mobile:** [`FridayAgent.kt:L227-236`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/agent/FridayAgent.kt#L227-L236) validates `MessageGrounding.isOwnWords()` so message bodies must exist in the user's spoken words.

---

## 7. Android AccessibilityService & App Interaction

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Service Class** | `VoxynAccessibilityService` | `JarvisAccessibilityService` | None (Relies on Intents & Knobs) | None | `SannaAccessibilityService` | None |
| **UI Tree Parsing** | Scans visible root node for clickable text & input fields | `ScreenReader.extractAllText()` | N/A | N/A | `buildAccessibilityTree()` with node mapping & indexing | N/A |
| **Action Execution** | `ACTION_CLICK`, `ACTION_SCROLL_FORWARD`, text typing | `tapByText()`, `typeText()`, `pressBack()` | N/A | N/A | `performAction(ACTION_CLICK)` with `dispatchGesture()` fallback | N/A |
| **Overlay Node Filter** | Overlay drawn outside accessibility flags | Basic check | N/A | N/A | Filters out own package name (`com.sannabot`) from inspection tree | N/A |
| **Rendering Tolerance** | Single-pass evaluation | Single-pass evaluation | N/A | N/A | Progressive retries (50ms, 100ms, 200ms, 300ms, 500ms) for cold launches | N/A |

### Key Source Evidence & Citations:
- **Sanna:** [`SannaAccessibilityService.kt:L97-100`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/sannabotapp/android/app/src/main/java/com/sannabot/native/SannaAccessibilityService.kt#L97-L100) implements multi-tier retry delays for rendering tolerance.
- **Sanna:** [`SannaAccessibilityService.kt:L30-34`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/sannabotapp/android/app/src/main/java/com/sannabot/native/SannaAccessibilityService.kt#L30-L34) falls back to `GestureDescription` strokes when native view clicks are consumed or disabled.

---

## 8. Risk Assessment, Confirmation & Permission Handling

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Risk Scoring** | High-risk set (`HIGH_RISK = 0.9f`, others `0.1f`) $\rightarrow$ `AwaitingConfirmation` | `RiskyActionConfirmation` (LOW, MEDIUM, HIGH) by package & action type | `SmsConfirmation` (spoken voice "да" confirmation required before sending) | None | Explicit user confirmation dialogs | `ActionPolicy` with permission matrices |
| **Voice Confirmation** | Spoken "yes/no" variants (`isYesVariant`) | Screen overlay countdown (8s auto-proceed) | Spoken confirmation loop (`SmsConfirmation.kt`) | None | UI prompt | UI prompt |
| **Contact Resolution** | `ContactResolver` (`Unique`, `Ambiguous`, `NotFound`) with voice clarification | Direct string search | `PeopleDirectory` + `libphonenumber` for SMS vs WhatsApp | Direct query | Native contact picker | Direct query |
| **Lock Screen Security** | In-app only | System alert window | `LockPolicy` checks keyguard state; restricts private data unless voice verified | Transparent activity over lockscreen | Unlocks screen | None |

### Key Source Evidence & Citations:
- **VOXYN:** [`VoxynEngine.kt:L876-886`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/engine/VoxynEngine.kt#L876-L886) checks `confidence < 0.25f` $\rightarrow$ Clarification; `risk > 0.8f` $\rightarrow$ Confirmation.
- **Open Jarvis:** [`RiskyActionConfirmation.kt:L19-51`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/RiskyActionConfirmation.kt#L19-L51) categorizes financial packages as `HIGH` and messaging packages as `MEDIUM`.

---

## 9. Local Model Inference & Offline Functionality

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Offline STT** | Sherpa-ONNX Parakeet-TDT 0.6B + Vosk | None | Vosk (wake word only) | Sherpa-ONNX 1.13.2 | Vosk bridge | PocketSphinx / Whisper local |
| **Offline LLM** | MediaPipe GenAI Tasks (Gemma / Qwen models) | GGUF / Llama.cpp via background download | None | None | None | Ollama local server |
| **Offline Voice Synthesis** | Android native TTS engines | Android native TTS | None (Edge-TTS cloud) | None (Edge-TTS cloud) | Android native TTS | Piper on-device ONNX neural voices |
| **Offline Knowledge & Time** | `LocalKnowledgeProvider` + `WorldTimeProvider` | None | Stored mode definitions (`ModeStore`) | None | None | Offline Markdown & SQLite |

### Key Source Evidence & Citations:
- **VOXYN:** [`VoxynApplication.kt:L49-88`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/VoxynApplication.kt#L49-L88) restores Parakeet ASR and MediaPipe LLM from Downloads backup on startup.
- **VOXYN:** [`LocalKnowledgeProvider.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/ai/LocalKnowledgeProvider.kt) answers basic facts completely offline.

---

## 10. Memory Extraction, Storage, Retrieval & Learning

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Extraction Strategy** | Intent regex (`REMEMBER_THIS`, `MEMORY_SAVE`) | Graph analysis | Conversational summarizer (`SessionSummarizer`) | Message history logging | Vector embeddings | Local heuristic pattern matching (`ContextualMemoryExtractor`) |
| **Memory Tiers** | Flat note list in DataStore/Preferences | SQLite action graph (`GraphifyDB`) | Room `MemoryDao` (pinned, context, interaction) | SQLite Room table | Local JSON storage | Multi-tiered (1–10 score, core, personal, preferences) |
| **Adaptive Learning** | `recordOutcome(action, success)` updates trust score $0..1$ | Logs provider failure rates | `LearnedCommands.learn(said, tool, args)` bypasses LLM on repeat | None | Learns UI paths over time | Persona evolution & tool usage weight |
| **Retrieval Speed** | $<2\text{ms}$ (in-memory flow) | SQL graph query | SQL indexed query | Simple SQL SELECT | Vector similarity | Tier-sorted local list |

### Key Source Evidence & Citations:
- **VOXYN:** [`VoxynEngine.kt:L971-976`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/engine/VoxynEngine.kt#L971-L976) records execution success/failure into `MemoryRepository` to dynamically influence confidence scoring.
- **Friday Mobile:** [`LearnedCommands.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/agent/LearnedCommands.kt) remembers the exact tool call parameters for repeated user phrases.
- **AI-Buddy:** [`contextual_memory_extractor.dart:L38-82`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-buddy/lib/services/contextual_memory_extractor.dart#L38-L82) automatically scores conversation facts without generating LLM token overhead.

---

## 11. Background Tasks, Scheduling & Proactive Suggestions

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Background Scheduler** | AlarmManager + BroadcastReceiver (`BootReceiver`) | AndroidX WorkManager (`work-runtime-ktx`) | AndroidX WorkManager (`MorningBriefWorker`, `ErrandWatchWorker`) + AlarmManager | None | Headless Services + AlarmManager | Flutter Workmanager |
| **Proactive Intelligence** | None (reactive to voice input only) | None | `ProactiveBriefService` (morning summary of agenda, weather, notifications) | None | Headless sub-agent scheduled runs | `ProactiveEngine` reacting to time, location, weather, and POIs |
| **Geofenced Reminders** | None | None | `ErrandWatchWorker` (matches coarse location against errand list) | None | None | `_Poi` geofence distance calculation |

### Key Source Evidence & Citations:
- **Friday Mobile:** [`MorningBriefWorker.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/MorningBriefWorker.kt) schedules a daily morning brief using WorkManager.
- **Friday Mobile:** [`ErrandWatchWorker.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/ErrandWatchWorker.kt) wakes up periodically, checks `NearbyPlacesService`, and suggests errands.

---

## 12. Conversation History & Encrypted Local Storage

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Storage Engine** | AndroidX DataStore Preferences | Room 2.6.1 SQLite Database | Room 2.6.1 SQLite Database | Room SQLite Database | React Native AsyncStorage | SQLite + Atomic JSON Store |
| **Encryption at Rest** | Android Keystore used for Speaker Profile | `EncryptedSharedPreferences` (AES256_GCM + SIV) | `KeystoreCipher` (Android Keystore AES256-GCM sealed DAOs) | `androidx.security.crypto` | `react-native-keychain` | `flutter_secure_storage` |
| **Session Tracking** | In-memory `ConversationSession` (turns & context) | Room `GraphifyDB` | Room `SessionSummaryDao` + `ChatMessageDao` | Room message history | App state memory | `MemoryLedger` with write leases |
| **Zero-Leak Logging** | `VoxynLogger` with file sink & crash handler | Logcat | `FridayAgent.toolLog()` omits arguments & personal text | Logcat | File logger | Masked logger |

### Key Source Evidence & Citations:
- **Friday Mobile:** [`KeystoreCipher.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/data/local/secure/KeystoreCipher.kt) encrypts all user preferences and API credentials with Android Keystore before touching Room SQLite.
- **VOXYN:** [`VoxynLogger.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/utils/VoxynLogger.kt) encapsulates structured logging and provides crash capture.

---

## 13. Error Recovery, Action Verification & Retries

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Execution Verification** | Explicit `verify(step, result)` $\rightarrow$ `VerificationOutcome` (`Success, Partial, Failure, NeedsRollback`) | `LLMResponseValidator` | `CommandExecutor.isFailure(outcome)` check | None | Visual verification via Accessibility tree check | Action policy status |
| **Self-Healing Loop** | 2-attempt recovery with `canFallback` check | `SelfHealingExecutor` captures screen, sends error prompt to LLM for alternative | Groq tool format retries + cooldown model swap | None | Re-scans accessibility tree up to 5 times | Retry with fallback parameters |
| **Rollback Handling** | `VerificationOutcome.NeedsRollback` flag | None | State restoration where supported | None | None | Data transaction rollback |
| **Provenance Tracking** | `PipelineEvent.AnswerProvenance` (source, URL, epoch) | None | None | None | None | None |

### Key Source Evidence & Citations:
- **VOXYN:** [`VoxynEngine.kt:L638-656`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/engine/VoxynEngine.kt#L638-L656) implements `executeWithRecovery(plan)` checking `firstFailure.canFallback` and `retrySafe`.
- **Open Jarvis:** [`SelfHealingExecutor.kt:L18-52`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/SelfHealingExecutor.kt#L18-L52) extracts screen text upon failure and generates a healing prompt asking the LLM for a revised action.

---

## 14. Jetpack Compose UI, Animations, Overlays & Design Systems

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Design Language** | Dynamic Island (`DynamicIsland.kt`) + Glassmorphism Theme | Floating HUD overlay bubble (`FloatingOverlayWidget.kt`) | Custom Canvas Iron Man Arc Reactor orb | Transparent Bottom Sheet overlay | Fullscreen React Native UI | Flutter Material Design 3 |
| **Overlay Architecture** | `OverlayService` (`SYSTEM_ALERT_WINDOW`) with live state | `OverlayService` (`specialUse`) | `LockScreenActivity` (`showWhenLocked`) | `MainActivity` (`Theme.TransparentActivity`) | System Alert Window | Flutter overlay |
| **Visual Feedback States** | `IDLE`, `LISTENING`, `THINKING`, `EXECUTING`, `CONFIRMATION`, `SUCCESS`, `ERROR`, `SPEAKING` | `Idle`, `Recording`, `Thinking`, `Action` | Idle pulse, listening reactor spin, speaking waves | Waveform visualizer | Speech bar | Audio wave animation |
| **Separation of Shell** | Explicitly decoupled: Assistant vs VOXYN Shell | Mixed in HUD | Single app | Single app | Single app | Single app |

### Key Source Evidence & Citations:
- **VOXYN:** [`DynamicIsland.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/overlay/DynamicIsland.kt) renders animated pill expansions, waveforms, and execution indicators.
- **Open Jarvis:** [`FloatingOverlayWidget.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/ui/overlay/FloatingOverlayWidget.kt) renders a movable overlay bubble with step-by-step progress.

---

## 15. Automated Tests, Build Reliability & Battery Consumption

| Metric / Detail | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Unit Test Coverage** | 25 actionable Gradle test tasks; extensive pipeline verification | 2 test suites (`bridge`, `graphify`) | Comprehensive JUnit4 + MockK + Turbine + Robolectric | Basic Android test runner | Jest unit tests | Flutter test suite |
| **Static Analysis** | Standard compiler lints | KSP compiler | Detekt 1.23.8 + custom baseline XML | Kapt compiler | ESLint + Prettier | Flutter analyze |
| **Minification Safety** | Custom ProGuard keep rules | Standard ProGuard | Custom Gradle task `verifyReleaseKeepRules` checks JNI keep rules | Basic ProGuard | Standard | Flutter obfuscation |
| **Battery Protection** | AudioRecord pauses when inactive | Idle when not triggered | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, selective wake word sleep | Battery optimization request | Headless wake lock | WorkManager battery constraints |

### Key Source Evidence & Citations:
- **VOXYN:** [`CorePipelineVerificationTest.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/test/java/com/voxyn/engine/CorePipelineVerificationTest.kt) and [`VoiceCommandPlanVerificationTest.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/test/java/com/voxyn/engine/VoiceCommandPlanVerificationTest.kt) verify deterministic pipeline execution.
- **Friday Mobile:** [`build.gradle.kts:L261-293`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/build.gradle.kts#L261-L293) registers `verifyReleaseKeepRules` checking R8 output mapping against required JNI native classes.
