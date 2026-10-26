# Source Code Map: Classes, Interfaces & File Paths

This document provides a verified, concrete mapping of the primary classes, interfaces, services, and packages across VOXYN and the five reference repositories.

---

## 1. VOXYN (`Voxyn2`) Source Code Map

All paths relative to `Voxyn2/app/src/main/java/com/voxyn/`:

| Subsystem / Purpose | Class / Interface | Verified File Path | Description |
| :--- | :--- | :--- | :--- |
| **Pipeline Core** | `VoxynEngine` | [`engine/VoxynEngine.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/engine/VoxynEngine.kt) | Primary orchestrator of the 10-phase immutable pipeline. |
| **Pipeline Contracts** | `PipelinePhase`, `IntentResult`, `DecisionState`, `VerificationOutcome`, `ExecutionPlan`, `ActionStep` | [`core/Contracts.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/core/Contracts.kt) | Core sealed classes and immutable state models. |
| **Pipeline Results** | `PhaseResult` | [`core/PhaseResult.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/core/PhaseResult.kt) | Monadic `Success(value)` / `Failure(error, canFallback)`. |
| **Pipeline Events** | `PipelineEvent` | [`core/PipelineEvent.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/core/PipelineEvent.kt) | Flow event stream emitted to UI and overlays. |
| **Dependency Injection** | `appModule` | [`di/AppModule.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/di/AppModule.kt) | Pure Koin DSL defining singletons and ViewModel bindings. |
| **Application Lifecycle**| `VoxynApplication` | [`VoxynApplication.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/VoxynApplication.kt) | Bootstraps Koin, crash handler, logger, and model preloading. |
| **Foreground Mic** | `VoiceInputService` | [`voice/VoiceInputService.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/voice/VoiceInputService.kt) | Foreground service (`microphone`) running Vosk / Sherpa loop. |
| **Sherpa-ONNX ASR** | `SherpaParakeetRecognizer` | [`voice/SherpaParakeetRecognizer.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/voice/SherpaParakeetRecognizer.kt) | Offline streaming ASR using NVIDIA Parakeet-TDT 0.6B INT8. |
| **Vosk ASR** | `VoskCommandRecognizer` | [`voice/VoskCommandRecognizer.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/voice/VoskCommandRecognizer.kt) | Offline wake-word and low-power command recognizer. |
| **TTS Synthesis** | `TextToSpeechManager` | [`voice/TextToSpeechManager.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/voice/TextToSpeechManager.kt) | Native Android TTS wrapper with 5 switchable voices. |
| **Voice State** | `ConversationSession` | [`voice/ConversationSession.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/voice/ConversationSession.kt) | Multi-turn conversational turn and context cache. |
| **Speaker Security** | `SpeakerVerifier` | [`security/SpeakerVerifier.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/security/SpeakerVerifier.kt) | Voiceprint extraction and similarity comparison. |
| **Command Gate** | `CommandVerificationGate` | [`security/CommandVerificationGate.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/security/CommandVerificationGate.kt) | Gates sensitive commands based on speaker identity. |
| **Perception** | `SpeechNormalizer` | [`perception/SpeechNormalizer.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/perception/SpeechNormalizer.kt) | Normalizes spoken terms and handles phonetic app distortions. |
| **Accessibility** | `VoxynAccessibilityService`| [`perception/VoxynAccessibilityService.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/perception/VoxynAccessibilityService.kt) | Rootless UI tree navigation and action clicks. |
| **Intent Routing** | `IntentRouter` | [`intent/IntentRouter.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/intent/IntentRouter.kt) | Deterministic pattern matching for device capabilities. |
| **Request Router** | `RequestRouter` | [`router/RequestRouter.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/router/RequestRouter.kt) | Classifies inputs into fast commands, weather, memory, or web. |
| **Execution** | `ExecutionController` | [`execution/ExecutionController.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/execution/ExecutionController.kt) | Dispatches steps to registered capabilities. |
| **Capabilities** | `CapabilityRegistry` | [`execution/CapabilityRegistry.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/execution/CapabilityRegistry.kt) | Registry of all phone action capabilities. |
| **Memory** | `MemoryRepository` | [`memory/MemoryRepository.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/memory/MemoryRepository.kt) | DataStore backed note storage and trust scoring. |
| **Overlay UI** | `DynamicIsland` | [`overlay/DynamicIsland.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/overlay/DynamicIsland.kt) | Compose-based dynamic island assistant overlay. |
| **Overlay Service** | `OverlayService` | [`overlay/OverlayService.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/overlay/OverlayService.kt) | WindowManager service managing overlay lifecycle. |

---

## 2. Open Jarvis (`open-jarvis`) Source Code Map

All paths relative to `voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/`:

| Subsystem | Class / Interface | Verified File Path | Description |
| :--- | :--- | :--- | :--- |
| **Agent Core** | `AgentCore` | [`agent/AgentCore.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/AgentCore.kt) | Central loop executing perception $\rightarrow$ action cycles. |
| **Provider Fallback** | `ProviderFallbackChain` | [`agent/ProviderFallbackChain.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/ProviderFallbackChain.kt) | Dynamic fallback with 429 and 401 provider cooldowns. |
| **Error Translation** | `JarvisErrorTranslator` | [`agent/ProviderFallbackChain.kt#L70`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/ProviderFallbackChain.kt#L70) | Maps raw HTTP/Security exceptions into user-friendly speech. |
| **Risky Confirmation** | `RiskyActionConfirmation` | [`agent/RiskyActionConfirmation.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/RiskyActionConfirmation.kt) | Evaluates action risk (LOW, MEDIUM, HIGH) by package category. |
| **Self Healing** | `SelfHealingExecutor` | [`agent/SelfHealingExecutor.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/SelfHealingExecutor.kt) | Re-reads screen upon action failure and queries LLM for fixes. |
| **Prompt Engine** | `PromptEngine`, `PromptSanitizer` | [`agent/PromptEngine.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/PromptEngine.kt) | System prompt construction and secret sanitization. |
| **Accessibility Service** | `JarvisAccessibilityService`| [`accessibility/JarvisAccessibilityService.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/accessibility/JarvisAccessibilityService.kt) | Performs UI taps, text typing, and back gestures. |
| **Screen Reading** | `ScreenReader` | [`accessibility/ScreenReader.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/accessibility/ScreenReader.kt) | Traverses `AccessibilityNodeInfo` hierarchy and outputs text. |
| **LLM Adapter** | `UniversalAdapter` | [`llm/UniversalAdapter.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/llm/UniversalAdapter.kt) | Unifies Groq, Gemini, Llama, and OpenAI under `LLMProvider`. |
| **MCP Integration** | `MCPClient`, `MCPManager` | [`mcp/MCPClient.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/mcp/MCPClient.kt) | Implements Model Context Protocol client specification. |
| **UI Overlay** | `FloatingOverlayWidget` | [`ui/overlay/FloatingOverlayWidget.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/ui/overlay/FloatingOverlayWidget.kt) | Movable Compose floating bubble with action status cards. |
| **Voice Manager** | `VoiceManager` | [`voice/VoiceManager.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/voice/VoiceManager.kt) | Encrypted prefs + Android SpeechRecognizer integration. |

---

## 3. Friday Mobile (`friday-mobile`) Source Code Map

All paths relative to `voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/`:

| Subsystem | Class / Interface | Verified File Path | Description |
| :--- | :--- | :--- | :--- |
| **Speaker Embedder** | `SpeakerEmbedder` | [`service/SpeakerEmbedder.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/SpeakerEmbedder.kt) | On-device ONNX inference for `voxceleb_resnet34_LM`. |
| **Acoustic Features**| `KaldiFbank` | [`core/KaldiFbank.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/core/KaldiFbank.kt) | 80-bin mel-filterbank computation matching WeSpeaker. |
| **Speaker Gate** | `SpeakerGate` | [`service/voice/SpeakerGate.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/voice/SpeakerGate.kt) | Compares speaker embedding against enrolled profile threshold. |
| **Foreground Wake** | `FridayWakeWordService`| [`service/FridayWakeWordService.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/FridayWakeWordService.kt) | Foreground service coordinating microphone lifecycle. |
| **Call Interruption**| `CallWatcher` | [`service/voice/CallWatcher.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/voice/CallWatcher.kt) | Pauses wake listener when phone or VoIP call is active. |
| **Audio Focus** | `AudioFocusHold` | [`service/voice/AudioFocusHold.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/voice/AudioFocusHold.kt) | Manages audio focus transitions and keeps players paused. |
| **Agent Tool Loop** | `FridayAgent` | [`agent/FridayAgent.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/agent/FridayAgent.kt) | Multi-turn tool execution loop with hallucination safeguards. |
| **Learned Commands** | `LearnedCommands` | [`agent/LearnedCommands.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/agent/LearnedCommands.kt) | Caches successfully resolved phrases to bypass LLM on repeat. |
| **Tool Definitions** | `AgentTools` | [`agent/AgentTools.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/agent/AgentTools.kt) | Tool definitions, schemas, and argument deserialization. |
| **Mode Engine** | `ModeEngine`, `ModeStore`| [`core/modes/ModeEngine.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/core/modes/ModeEngine.kt) | Compiles voice automation macros once; executes without LLM. |
| **SMS Confirmation** | `SmsConfirmation` | [`core/people/SmsConfirmation.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/core/people/SmsConfirmation.kt) | Spoken voice confirmation before sending messages. |
| **Secure Storage** | `KeystoreCipher` | [`data/local/secure/KeystoreCipher.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/data/local/secure/KeystoreCipher.kt) | AES256-GCM cipher using Android Keystore for database encryption. |
| **Proactive Brief** | `MorningBriefWorker` | [`service/MorningBriefWorker.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/MorningBriefWorker.kt) | WorkManager job assembling daily schedule and weather briefs. |
| **Lock Screen** | `LockScreenActivity` | [`service/lock/LockScreenActivity.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/lock/LockScreenActivity.kt) | Full-screen assistant activity operating securely over lockscreen. |

---

## 4. AI Assistant for Android (`ai-assistant-android`) Source Code Map

All paths relative to `voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/`:

| Subsystem | Class / Interface | Verified File Path | Description |
| :--- | :--- | :--- | :--- |
| **Audio Hygiene** | `AudioHygieneProcessor` | [`speech/AudioHygieneProcessor.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/speech/AudioHygieneProcessor.kt) | Configures `AudioRecord` with hardware AEC, NS, and AGC. |
| **Voice State Machine**| `VoiceStateMachine` | [`speech/VoiceStateMachine.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/speech/VoiceStateMachine.kt) | State machine handling echo bleed suppression and barge-in. |
| **Sherpa STT Manager** | `SpeechRecognizerManager` | [`speech/SpeechRecognizerManager.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/speech/SpeechRecognizerManager.kt) | Manages offline Sherpa-ONNX streaming and decoding. |
| **VAD Processor** | `VadIntelligenceProcessor`| [`speech/VadIntelligenceProcessor.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/speech/VadIntelligenceProcessor.kt) | Analyzes voice energy to determine speech boundaries. |
| **Text Classifier** | `TextClassifierHelper` | [`classifier/TextClassifierHelper.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/classifier/TextClassifierHelper.kt) | MediaPipe Tasks Text on-device intent classifier. |
| **Assist Activity** | `AssistActivity` | [`AssistActivity.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/AssistActivity.kt) | Android system assistant interceptor (`ACTION_ASSIST`). |

---

## 5. Sanna (`sannabotapp`) Source Code Map

All paths relative to `voxyn-reference-lab/sannabotapp/`:

| Subsystem | Class / Interface / Module | Verified File Path | Description |
| :--- | :--- | :--- | :--- |
| **Accessibility Engine**| `SannaAccessibilityService`| [`android/app/src/main/java/com/sannabot/native/SannaAccessibilityService.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/sannabotapp/android/app/src/main/java/com/sannabot/native/SannaAccessibilityService.kt) | Production Accessibility service with gesture fallbacks & retries. |
| **Notification Intercept**| `NotificationListenerService`| [`android/app/src/main/java/com/sannabot/native/NotificationListenerService.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/sannabotapp/android/app/src/main/java/com/sannabot/native/NotificationListenerService.kt) | Intercepts incoming notifications for automated agent handling. |
| **Native Scheduler** | `SchedulerModule` | [`android/app/src/main/java/com/sannabot/native/SchedulerModule.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/sannabotapp/android/app/src/main/java/com/sannabot/native/SchedulerModule.kt) | AlarmManager wrapper triggering headless background JS tasks. |
| **TypeScript Bridge** | `AccessibilityModule.ts` | [`src/native/AccessibilityModule.ts`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/sannabotapp/src/native/AccessibilityModule.ts) | TypeScript interfaces for UI tree traversal and gestures. |
| **Agent Controller** | `Agent` | [`src/agent/`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/sannabotapp/src/agent/) | Multi-step agent planner and execution controller. |

---

## 6. AI-Buddy (`ai-buddy`) Source Code Map

All paths relative to `voxyn-reference-lab/ai-buddy/lib/`:

| Subsystem | Class / Service | Verified File Path | Description |
| :--- | :--- | :--- | :--- |
| **Context Memory** | `ContextualMemoryExtractor`| [`services/contextual_memory_extractor.dart`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-buddy/lib/services/contextual_memory_extractor.dart) | Local pattern-matching memory extractor (scores 1–10). |
| **Memory Ledger** | `MemoryLedger` | [`services/memory_ledger.dart`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-buddy/lib/services/memory_ledger.dart) | Audit log and history of memory creations and updates. |
| **Proactive Engine**| `ProactiveEngine` | [`services/proactive_engine.dart`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-buddy/lib/services/proactive_engine.dart) | Autonomous trigger engine (time, weather, POI geofences). |
| **Piper TTS** | `PiperTtsService` | [`services/piper_tts_service.dart`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-buddy/lib/services/piper_tts_service.dart) | Local on-device Piper neural TTS synthesis using ONNX models. |
| **Action Policy** | `ActionPolicy` | [`services/action_policy.dart`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-buddy/lib/services/action_policy.dart) | Security rules governing permitted tool interactions. |
| **Atomic Store** | `AtomicStore`, `WriteLease`| [`services/atomic_store.dart`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-buddy/lib/services/atomic_store.dart) | Thread-safe data transactions preventing corrupted memory writes. |
