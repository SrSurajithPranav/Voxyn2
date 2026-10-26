# VOXYN Adoption Plan: Ranked Technical Recommendations

Based on the reverse engineering of the five reference projects, this plan presents ranked, evidence-based recommendations for VOXYN. Every recommendation adheres strictly to VOXYN's non-negotiable constraints:
- Preserve Kotlin and Koin dependency injection (No Hilt, No Firebase).
- Preserve the 10-phase immutable pipeline:
  $$\text{INPUT} \rightarrow \text{PERCEPTION} \rightarrow \text{INTENT} \rightarrow \text{CONFIDENCE} \rightarrow \text{RISK} \rightarrow \text{DECISION} \rightarrow \text{PLAN} \rightarrow \text{EXECUTE} \rightarrow \text{VERIFY} \rightarrow \text{LEARN}$$
- Maintain rootless operation using supported Android APIs.
- Keep local inference and offline behavior first where feasible.
- Use `VoxynLogger` exclusively (no `println`).
- Keep VOXYN Assistant separate from the future VOXYN Shell.

---

## 1. Summary of Ranked Recommendations

| Rank | Subsystem & Recommendation | Primary Inspiration | Effort / Impact | License Feasibility |
| :---: | :--- | :--- | :--- | :--- |
| **1** | **Deterministic Hallucination & Grounding Guardrails** | Friday Mobile (`FridayAgent.kt`) | Medium / **Critical** | MIT |
| **2** | **Audio Hygiene & Hardware Acoustic Echo Cancellation** | AI Assistant Android (`AudioHygieneProcessor.kt`) | Low / **Critical** | MIT |
| **3** | **Microphone Lifecycle, Settle Margins & Call Interruption** | Friday Mobile (`FridayWakeWordService.kt`, `CallWatcher.kt`) | Low / **High** | MIT |
| **4** | **Deep Neural Speaker Verification via ONNX Runtime** | Friday Mobile (`SpeakerEmbedder.kt`, `KaldiFbank.kt`) | Medium / **High** | MIT + CC-BY-4.0 |
| **5** | **Multi-Tier Provider Fallback Chain with Cooldowns** | Open Jarvis (`ProviderFallbackChain.kt`) | Low / **High** | MIT |
| **6** | **Accessibility Tree Hardening & Gesture Fallback** | Sanna (`SannaAccessibilityService.kt`) | Medium / **High** | MIT |
| **7** | **Self-Healing LLM Execution for Failed Tool Steps** | Open Jarvis (`SelfHealingExecutor.kt`) | Medium / **Medium** | MIT |
| **8** | **Learned Command Caching to Bypass Cloud LLMs** | Friday Mobile (`LearnedCommands.kt`) | Low / **Medium** | MIT |
| **9** | **Heuristic Tiered Memory Extraction (No-LLM Overhead)** | AI-Buddy (`contextual_memory_extractor.dart`) | Medium / **Medium** | MIT |
| **10**| **Dynamic Island UI Refinement & Interaction Waveforms** | VOXYN Overlay + Friday Arc Reactor | Low / **Medium** | Custom |

---

## 2. Granular Recommendation Specifications

### Rank 1: Deterministic Hallucination & Grounding Guardrails
- **Current Limitation in VOXYN:** When an external or fallback LLM (such as Claude or local MediaPipe) is called, it can return an acknowledgement or summary claiming an action succeeded before verification occurs, or it could hallucinate message text.
- **Reference Implementation:** Friday Mobile [`FridayAgent.kt:L60`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/agent/FridayAgent.kt#L60) (`SAYS_ITSELF`) and [`MessageGrounding.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/agent/MessageGrounding.kt).
- **VOXYN Integration:**
  - Embed in the `DECISION` and `VERIFY` phases of `VoxynEngine`.
  - For consequential communication actions (`SEND_MESSAGE`, `SEND_WHATSAPP_MESSAGE`, `CALL_CONTACT`), enforce that the capability speaks its own exact result string; never allow the LLM to rewrite it.
  - Implement `MessageGrounding.isOwnWords(messageText, rawUserPrompt)` before building the `ActionStep`. If the message text was not spoken by the user in this prompt, reject it.
- **Verdict:** **ADAPT** directly into VOXYN pipeline contracts.

---

### Rank 2: Audio Hygiene & Hardware Acoustic Echo Cancellation
- **Current Limitation in VOXYN:** In [`VoiceInputService.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/voice/VoiceInputService.kt), `AudioRecord` uses raw PCM capture without configuring platform acoustic echo cancellation. When VOXYN speaks through the device speaker, audio bleed can re-trigger Vosk or Parakeet recognizers.
- **Reference Implementation:** AI Assistant Android [`AudioHygieneProcessor.kt:L53-70, L137-179`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-assistant-android/app/src/main/java/com/app/assistant/speech/AudioHygieneProcessor.kt#L53-L70).
- **VOXYN Integration:**
  - Update `VoiceInputService` to use `MediaRecorder.AudioSource.VOICE_COMMUNICATION`.
  - In `AudioRecord` setup, attach `AcousticEchoCanceler` and `NoiseSuppressor` to the `audioSessionId` when available (`isAvailable() == true`).
  - Introduce an explicit `echoBleedWindow` in `VoiceSessionCoordinator`: discard any detected speech starting within 300ms after TTS playback begins.
- **Verdict:** **ADOPT** into `com.voxyn.voice`.

---

### Rank 3: Microphone Lifecycle, Settle Margins & Call Interruption
- **Current Limitation in VOXYN:** When switching from TTS back to continuous listening, immediate microphone acquisition can conflict with the audio HAL, causing audio clicks or failed capture. Furthermore, incoming phone calls or WhatsApp calls keep the mic hot in the background.
- **Reference Implementation:** Friday Mobile [`FridayWakeWordService.kt:L66-70`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/FridayWakeWordService.kt#L66-L70) and [`CallWatcher.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/voice/CallWatcher.kt).
- **VOXYN Integration:**
  - Add `MIC_SETTLE_MS = 200L` before restarting `AudioRecord` after TTS or speech recognition stops.
  - Implement `CallWatcher` in `VoiceInputService` to monitor `TelephonyManager` call state and `AudioManager.mode`. If `MODE_IN_CALL` or `MODE_RINGTONE` occurs, automatically pause microphone listening; resume cleanly when the call terminates.
- **Verdict:** **ADOPT** into `com.voxyn.voice`.

---

### Rank 4: Deep Neural Speaker Verification via ONNX Runtime
- **Current Limitation in VOXYN:** [`SpeakerVerifier.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/security/SpeakerVerifier.kt) uses simulated synthetic tone matching and heuristic acoustic features, which is insecure against casual spoofing.
- **Reference Implementation:** Friday Mobile [`SpeakerEmbedder.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/service/SpeakerEmbedder.kt) and `app/src/main/assets/speaker_embedding.onnx`.
- **VOXYN Integration:**
  - VOXYN already includes `arm64-v8a` ABI filters.
  - Add `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` or leverage Sherpa-ONNX's existing embedded ONNX runtime.
  - Port `KaldiFbank` (80 mel bins) and `SpeakerEmbedder` to generate 256-dimensional float embeddings from 16kHz audio.
  - Update `SpeakerProfile` to store real 256-float voiceprints with cosine similarity thresholding ($0.68$).
- **License Considerations:** `speaker_embedding.onnx` is WeSpeaker's `voxceleb_resnet34_LM` (CC-BY-4.0). We must attribute WeSpeaker in legal notices.
- **Verdict:** **ADAPT** into `com.voxyn.security`.

---

### Rank 5: Multi-Tier Provider Fallback Chain with Cooldowns
- **Current Limitation in VOXYN:** In [`VoxynEngine.kt:L166-195`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/engine/VoxynEngine.kt#L166-L195), if `LocalLlmProvider` returns unknown, it falls back to Claude directly without tracking rate limits, timeouts, or alternative cloud providers.
- **Reference Implementation:** Open Jarvis [`ProviderFallbackChain.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/ProviderFallbackChain.kt) and Friday Mobile rate limit delay logic.
- **VOXYN Integration:**
  - Create a structured `ModelProvider` interface with concrete implementations: `LocalMediaPipeProvider`, `GroqProvider`, `ClaudeProvider`, `OpenAiCompatibleProvider`.
  - Introduce `ProviderFallbackChain` in `com.voxyn.ai`: tracks provider health, HTTP 429 rate limit cooldowns (e.g. 60s), and seamless backup fallback.
- **Verdict:** **ADAPT** into `com.voxyn.ai`.

---

### Rank 6: Accessibility Tree Hardening & Gesture Fallback
- **Current Limitation in VOXYN:** [`VoxynAccessibilityService.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/perception/VoxynAccessibilityService.kt) performs basic single-pass node clicks. If an app is slow to render (cold launch) or overrides `performAction(ACTION_CLICK)`, the action fails.
- **Reference Implementation:** Sanna [`SannaAccessibilityService.kt:L97-100`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/sannabotapp/android/app/src/main/java/com/sannabot/native/SannaAccessibilityService.kt#L97-L100).
- **VOXYN Integration:**
  - Implement progressive retries (50ms, 100ms, 200ms) when capturing screen nodes after launching an app.
  - Add `dispatchGesture` fallback: if `node.performAction(ACTION_CLICK)` returns `false`, calculate the bounding box (`node.getBoundsInScreen()`) and dispatch a tap gesture using `GestureDescription`.
  - Filter out VOXYN's own overlay package (`com.voxyn`) from the parsed node hierarchy.
- **Verdict:** **ADOPT** into `com.voxyn.perception`.

---

### Rank 7: Self-Healing LLM Execution for Failed Tool Steps
- **Current Limitation in VOXYN:** In [`VoxynEngine.kt:L638-656`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/engine/VoxynEngine.kt#L638-L656), `executeWithRecovery` simply re-attempts the exact same failed step once. If an element wasn't clickable or state changed, repeating the same action always fails.
- **Reference Implementation:** Open Jarvis [`SelfHealingExecutor.kt:L33-52`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/open-jarvis/app/src/main/java/com/openjarvis/agent/SelfHealingExecutor.kt#L33-L52).
- **VOXYN Integration:**
  - Keep the retry in `EXECUTE` phase, but before attempt 2, inspect the current screen or error condition.
  - If the step failed due to a missing UI node or parameter mismatch, feed the current state back to the intent/planner to synthesize an alternative step rather than blindly repeating the failed one.
- **Verdict:** **ADAPT** into `com.voxyn.execution`.

---

### Rank 8: Learned Command Caching to Bypass Cloud LLMs
- **Current Limitation in VOXYN:** Every non-regex command goes through general query resolution or Claude, costing latency (1–3s) and network tokens.
- **Reference Implementation:** Friday Mobile [`LearnedCommands.kt`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/friday-mobile/app/src/main/java/com/friday/ai/agent/LearnedCommands.kt).
- **VOXYN Integration:**
  - Add `LearnedCommandStore` in `com.voxyn.memory`.
  - In the `LEARN` phase, when an intent resolved by an LLM successfully executes (`VerificationOutcome.Success`), store the mapping: `normalizedPhrase -> (action, params)`.
  - In `PERCEPTION`/`INTENT`, check `LearnedCommandStore` before invoking external LLMs. If matched, execute instantly in $<5\text{ms}$.
- **Verdict:** **ADOPT** into `com.voxyn.memory`.

---

### Rank 9: Heuristic Tiered Memory Extraction (No-LLM Overhead)
- **Current Limitation in VOXYN:** Memory saving is triggered only by explicit commands (`"remember that..."`). VOXYN does not retain personal facts mentioned casually in conversations.
- **Reference Implementation:** AI-Buddy [`contextual_memory_extractor.dart`](file:///c:/Users/srsur/Downloads/Voxyn/voxyn-reference-lab/ai-buddy/lib/services/contextual_memory_extractor.dart).
- **VOXYN Integration:**
  - Create `ContextualMemoryExtractor` in `com.voxyn.memory` using Kotlin regex patterns for personal info, preferences, relationships, and routines.
  - In the `LEARN` pipeline phase, pass conversational turns to `ContextualMemoryExtractor`. If high-confidence personal facts are identified, record them into `MemoryRepository` automatically.
- **Verdict:** **ADAPT** into `com.voxyn.memory`.

---

### Rank 10: Dynamic Island UI Refinement & Interaction Waveforms
- **Current Limitation in VOXYN:** VOXYN has an initial `DynamicIsland.kt` implementation, but lacks refined audio waveform feedback during speech synthesis and smooth state transitions.
- **Reference Implementation:** Open Jarvis `FloatingOverlayWidget.kt` and Friday Mobile Canvas reactor animations.
- **VOXYN Integration:**
  - Enhance `DynamicIsland` in `com.voxyn.overlay` with live RMS audio level metering from `VoiceInputService`.
  - Provide distinct animated visual states: `IDLE` (compact dot), `LISTENING` (pulsing blue orb), `THINKING` (spinning amber ring), `EXECUTING` (progress step badge), `CONFIRMATION` (amber prompt with countdown), `SPEAKING` (green audio waveform).
- **Verdict:** **ADOPT** into `com.voxyn.overlay`.
