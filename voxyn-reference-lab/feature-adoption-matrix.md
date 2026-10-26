# VOXYN Feature Adoption Matrix
**Status: Prompt 2 Deliverable — Evidence-Based Blueprint (No source code modified)**

All findings are grounded in source code inspected during Prompt 1.
Format: For each feature — reference project, source files, approach, compatibility, dependencies, verdict.

---

## Feature 1: Audio Hygiene & Hardware Acoustic Echo Cancellation

| Dimension | Detail |
|:---|:---|
| **Reference Project** | AI Assistant Android |
| **Source Files** | `AudioHygieneProcessor.kt:L53-70, L137-179` |
| **Approach** | `AudioRecord` created with `VOICE_COMMUNICATION` source. Hardware AEC (`AcousticEchoCanceler.create(audioSessionId)`), NS (`NoiseSuppressor`), and AGC (`AutomaticGainControl`) attached immediately after `AudioRecord` construction. Results in HAL-level echo cancellation before PCM bytes reach Vosk/Parakeet. |
| **VOXYN Compatibility** | ✅ Direct drop-in. `VoiceInputService.kt` uses `AudioRecord` with raw `MIC` source. The session ID is accessible via `audioRecord.audioSessionId`. No architecture change needed. |
| **Dependencies** | `android.media.audiofx.*` — standard AOSP API, no additional Gradle dependency. |
| **Permissions** | `RECORD_AUDIO` (already declared). |
| **License** | MIT (AI Assistant Android) — no attribution required for the approach; API is standard Android. |
| **Hardware Constraint** | `AcousticEchoCanceler.isAvailable()` returns false on some low-end devices. Guard with `if (AcousticEchoCanceler.isAvailable())`. |
| **Verdict** | **ADOPT** — 3-line addition to `VoiceInputService`. Critical quality improvement. |

---

## Feature 2: Microphone Settle Margins & Call Interruption

| Dimension | Detail |
|:---|:---|
| **Reference Project** | Friday Mobile |
| **Source Files** | `FridayWakeWordService.kt:L66-70`, `CallWatcher.kt` |
| **Approach** | Two tokenized delays: `MIC_SETTLE_MS = 200L` (before mic restart after TTS), `FOCUS_SETTLE_MS = 800L` (after audio focus granted). `CallWatcher` registers `PhoneStateListener`; on `CALL_STATE_RINGING` or `CALL_STATE_OFFHOOK`, calls `pauseRecognition()`; resumes on `CALL_STATE_IDLE`. |
| **VOXYN Compatibility** | ✅ Direct. `VoiceInputService.kt` has TTS completion callbacks and AudioFocus listener. Insert `delay(MIC_SETTLE_MS)` before `audioRecord.startRecording()` after TTS ends. Implement `CallWatcher` as a nested class in `VoiceInputService`. |
| **Dependencies** | `android.telephony.TelephonyManager` — standard AOSP. |
| **Permissions** | `READ_PHONE_STATE` (already declared in VOXYN manifest). |
| **License** | MIT — standard Android API approach. |
| **Hardware Constraint** | VoIP calls (WhatsApp, etc.) use `AudioManager.MODE_IN_COMMUNICATION`; detect via `AudioManager.mode` instead of TelephonyManager for completeness. |
| **Verdict** | **ADOPT** — Prevents audio clicks and mic-during-call conflicts. Low effort, high reliability gain. |

---

## Feature 3: Deep Neural Speaker Verification (ONNX ResNet34)

| Dimension | Detail |
|:---|:---|
| **Reference Project** | Friday Mobile |
| **Source Files** | `SpeakerEmbedder.kt:L1-108`, `KaldiFbank.kt`, `assets/speaker_embedding.onnx` |
| **Approach** | WeSpeaker `voxceleb_resnet34_LM` (float16, ~20MB) loaded into ONNX Runtime Android single-threaded session. Kaldi mel-filterbanks (80 bins, 25ms frame, 10ms hop, 16kHz). Per-utterance CMVN. Produces 256-d float embedding. Cosine similarity gate at 0.68 threshold. Enrolled profiles stored encrypted in Android Keystore. |
| **VOXYN Compatibility** | ⚠️ Medium adaptation. VOXYN has `SpeakerVerifier.kt` with simulated tone matching. The 256-float profile would replace `SpeakerProfile.audioSignature: FloatArray`. The gate logic in `CommandVerificationGate.kt` already exists; update threshold and embedding size. |
| **Dependencies** | `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` (~17MB AAR) OR reuse embedded ONNX Runtime from Sherpa-ONNX already in VOXYN. Need to confirm Sherpa-ONNX exposes the session API publicly. |
| **Permissions** | `RECORD_AUDIO` (existing). |
| **License** | ONNX Runtime: MIT. WeSpeaker model: CC-BY-4.0 (attribution required in NOTICE file). |
| **Hardware Constraint** | ARM64 recommended. ~50ms inference on mid-range 2023 devices. Must run on IO dispatcher, never main thread. |
| **Verdict** | **ADAPT** — High security value. Replaces heuristic with production-grade neural embedding. Required attribution: "Portions use WeSpeaker voxceleb_resnet34_LM (CC-BY-4.0)". |

---

## Feature 4: Voice Session State Machine with Echo Bleed Window

| Dimension | Detail |
|:---|:---|
| **Reference Project** | AI Assistant Android |
| **Source Files** | `VoiceStateMachine.kt:L44-65` |
| **Approach** | Explicit FSM: `IDLE → LISTENING → PROCESSING → BOT_SPEAKING → IDLE`. Echo bleed window: `if (System.currentTimeMillis() - lastTtsStartTime < 300) return` — discards any recognition triggered within 300ms after TTS begins. Instant barge-in: `callback.stopTtsPlayback()` on first voice frame during BOT_SPEAKING. |
| **VOXYN Compatibility** | ✅ Direct. VOXYN's `VoiceSessionCoordinator.kt` tracks session state. Add `lastTtsStartTime` timestamp and 300ms suppression guard in `onPartialResult()` / `onFinalResult()` callbacks. |
| **Dependencies** | None (uses existing `System.currentTimeMillis()`). |
| **Permissions** | None additional. |
| **License** | MIT — the pattern is well-established. |
| **Verdict** | **ADOPT** — Eliminates self-triggering during speech output. Essential for always-on wake-word mode. |

---

## Feature 5: Multi-Tier Model Provider Fallback Chain

| Dimension | Detail |
|:---|:---|
| **Reference Project** | Open Jarvis |
| **Source Files** | `ProviderFallbackChain.kt`, Friday Mobile rate limit delay logic |
| **Approach** | Provider chain with health tracking: `LocalMediaPipe → GroqFast → Claude`. Each provider has a `cooldownUntilMs` field; if `System.currentTimeMillis() < cooldownUntilMs`, skip and try next. HTTP 429 response sets `cooldownUntilMs = now + 60_000`. |
| **VOXYN Compatibility** | ⚠️ Medium adaptation. Current `AiProvider` interface in `AiProvider.kt` is compatible (`answer()` returns `AiResponse?`). Need to: (1) add `ModelProvider` interface extending `AiProvider` with `resolveIntent()`, (2) build `ProviderFallbackChain` wrapper, (3) wire into `VoxynEngine` INTENT phase instead of direct `claudeResolver` call. |
| **Dependencies** | No new libraries. Uses `ktor-client` or `okhttp` already in VOXYN for HTTP calls. |
| **License** | MIT — chain pattern is standard. |
| **Verdict** | **ADAPT** — Directly improves reliability for all LLM-dependent commands. Reduces Claude API costs via local-first routing. |

---

## Feature 6: Accessibility Tree Hardening & Gesture Fallback

| Dimension | Detail |
|:---|:---|
| **Reference Project** | Sanna |
| **Source Files** | `SannaAccessibilityService.kt:L97-100` |
| **Approach** | Progressive retry delays (50ms, 100ms, 200ms) after `findAccessibilityNodeInfosByText()`. If `performAction(ACTION_CLICK)` returns false, compute `getBoundsInScreen(rect)` and dispatch a tap via `GestureDescription.StrokeDescription`. Filter own overlay package from node hierarchy. |
| **VOXYN Compatibility** | ✅ Direct. VOXYN has `VoxynAccessibilityService.kt`. Add retry loop with `delay(retryMs)` and `dispatchGesture` fallback. Add `filterInfo.packageName == "com.voxyn"` exclusion. |
| **Dependencies** | `AccessibilityService`, `GestureDescription` — standard AOSP API since API 24. |
| **Permissions** | `BIND_ACCESSIBILITY_SERVICE` (existing). |
| **License** | MIT — approach is standard Android. |
| **Verdict** | **ADOPT** — Fixes cold-launch timing failures and apps overriding performAction(). |

---

## Feature 7: Self-Healing LLM Execution for Failed Tool Steps

| Dimension | Detail |
|:---|:---|
| **Reference Project** | Open Jarvis |
| **Source Files** | `SelfHealingExecutor.kt:L33-52` |
| **Approach** | On step failure: re-query accessibility tree for current screen state. Feed current UI context (visible node text) back to planner to synthesize an alternative `ActionStep` instead of repeating the same failed action. |
| **VOXYN Compatibility** | ⚠️ Medium. Current `executeWithRecovery` at `VoxynEngine.kt:L638-656` retries blindly. Need to: (1) snapshot accessibility tree at failure time, (2) pass snapshot to modified retry logic, (3) construct alternate step only for `!capability.retrySafe` capabilities. |
| **Dependencies** | Existing `VoxynAccessibilityService` + existing `IntentRouter`. |
| **License** | MIT — approach is standard. |
| **Verdict** | **ADAPT** — Prevents infinite retry loops on UI state changes. |

---

## Feature 8: Learned Command Cache (LLM Bypass)

| Dimension | Detail |
|:---|:---|
| **Reference Project** | Friday Mobile |
| **Source Files** | `LearnedCommands.kt` |
| **Approach** | On successful LLM resolution (VerificationOutcome.Success), store `normalizedPhrase → (action, params)` in a Room DB table. In INTENT phase, check this table before invoking any LLM. Match uses normalized form; hit bypasses all providers in < 5ms. |
| **VOXYN Compatibility** | ✅ Direct. `MemoryRepository.kt` already uses Room. Add `learned_commands` table. Check in `INTENT` phase (new `LearnedCommandStore.match(normalized)` call before `IntentRouter`). |
| **Dependencies** | `androidx.room` (already in VOXYN). |
| **License** | MIT — Room is Apache 2.0. |
| **Verdict** | **ADOPT** — Significant latency reduction for repeat commands. |

---

## Feature 9: Contextual Memory Extraction (No-LLM)

| Dimension | Detail |
|:---|:---|
| **Reference Project** | AI-Buddy |
| **Source Files** | `contextual_memory_extractor.dart` |
| **Approach** | Heuristic Kotlin regex patterns scan each conversational turn for: name introductions ("my name is", "I am called"), preferences ("I prefer", "I like"), relationships ("my sister", "my boss"), routines ("I usually", "every morning"). High-confidence (≥ 0.8) extractions stored automatically. |
| **VOXYN Compatibility** | ✅ Direct. `MemoryRepository.kt` has `save(key, value)`. Create `ContextualMemoryExtractor.kt` in `com.voxyn.memory`. Call from `LEARN` phase in `VoxynEngine`. |
| **Dependencies** | Kotlin stdlib `Regex` — no additions. |
| **License** | MIT — regex patterns independently authored. |
| **Verdict** | **ADAPT** — Passively builds personal context without LLM overhead. |

---

## Feature 10: Edge-TTS with Prefetch Cache

| Dimension | Detail |
|:---|:---|
| **Reference Project** | Friday Mobile |
| **Source Files** | `EdgeTtsSpeaker.kt` |
| **Approach** | Microsoft Edge-TTS over WebSocket. Common short acknowledgements ("OK", "Done", "On it") are prefetched at service start and stored in-memory. First word of long responses is synthesized and played while the rest is generated in parallel. |
| **VOXYN Compatibility** | ⚠️ Optional enhancement. VOXYN's `TextToSpeechManager.kt` uses Android TTS with 5 preset voices. Edge-TTS provides better naturalness but requires internet. Would add an optional second TTS provider with fallback to existing Android TTS. |
| **Dependencies** | WebSocket client (Ktor or OkHttp — already present). Microsoft Edge-TTS protocol (no API key required). |
| **License** | MIT. Edge-TTS ToS: non-commercial usage, no SLA. |
| **Hardware Constraint** | Network required; Android TTS must remain as offline fallback. |
| **Verdict** | **OPTIONAL** — Improve voice quality when online. Keep Android TTS as default offline. |

---

## Feature 11: Dynamic Island Overlay with RMS Waveform

| Dimension | Detail |
|:---|:---|
| **Reference Project** | Open Jarvis (`FloatingOverlayWidget.kt`), Friday Mobile (Canvas arc reactor) |
| **Source Files** | `FloatingOverlayWidget.kt`, `FridayWakeWordService.kt` Arc Reactor canvas |
| **Approach** | Custom `View` with `Canvas` drawing. RMS amplitude fed from `AudioRecord` PCM frames in real time. States: IDLE (compact pill), LISTENING (pulsing orb), THINKING (rotating arc), EXECUTING (step counter), CONFIRMATION (amber badge), SPEAKING (green waveform). Spring-interpolated width transitions. |
| **VOXYN Compatibility** | ✅ VOXYN has `DynamicIsland.kt` with `IslandState` enum. Enhance existing `draw()` method with RMS waveform rendering. Add `updateRms(rmsDb: Float)` method called from `VoiceInputService`. |
| **Dependencies** | None (Canvas API). |
| **License** | N/A — independent original implementation. |
| **Verdict** | **ADOPT** — Polish feature with high user-perception impact. |

---

## Adoption Summary Table

| # | Feature | Verdict | Effort | Impact | Source License |
|:---:|:---|:---:|:---:|:---:|:---:|
| 1 | Hardware AEC + Noise Suppression | **ADOPT** | Low | Critical | MIT |
| 2 | Mic Settle Margins + Call Interruption | **ADOPT** | Low | High | MIT |
| 3 | ONNX Speaker Verification (ResNet34) | **ADAPT** | Medium | High | MIT + CC-BY-4.0 |
| 4 | Echo Bleed Window + Barge-In FSM | **ADOPT** | Low | High | MIT |
| 5 | Provider Fallback Chain | **ADAPT** | Medium | High | MIT |
| 6 | Accessibility Retry + Gesture Fallback | **ADOPT** | Low | High | MIT |
| 7 | Self-Healing Executor | **ADAPT** | Medium | Medium | MIT |
| 8 | Learned Command Cache | **ADOPT** | Low | Medium | MIT |
| 9 | Contextual Memory Extractor | **ADAPT** | Medium | Medium | MIT |
| 10 | Edge-TTS with Prefetch | **OPTIONAL** | Medium | Low | MIT |
| 11 | Dynamic Island RMS Waveform | **ADOPT** | Low | Medium | Original |
