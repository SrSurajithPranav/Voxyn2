# VOXYN Implementation Roadmap
**Status: Prompt 2 Deliverable — Evidence-Based Blueprint (No source code modified)**

Grounded in actual VOXYN source inspection and feature-adoption-matrix.md findings.

---

## Roadmap Principles

1. **Smallest safe increment** — each milestone produces independently testable, shippable code.
2. **No regressions** — every existing test must pass after each milestone.
3. **Kotlin + Koin only** — no Hilt, no Firebase, no framework replacement.
4. **Pipeline immutability preserved** — no changes to phase ordering or event bus contract.
5. **VoxynLogger** — no `println` in any new code.
6. **Rootless + offline-first** — all features gracefully degrade without network/root.

---

## Phase 0: Baseline Stabilization (Pre-work)

**Goal:** Establish a reproducible baseline before any feature work.
**Time estimate:** 0.5–1 day

### Tasks
- [ ] Run `./gradlew :app:test` and record baseline test pass/fail counts.
- [ ] Run `./gradlew :app:lintDebug` and record baseline lint warnings.
- [ ] Run `./gradlew :app:assembleDebug` and confirm successful APK generation.
- [ ] Document baseline: test count, lint warnings, APK size, build time.
- [ ] Create `VoxynConstants.kt` in `com.voxyn.core` with all magic numbers extracted from VoxynEngine.
- [ ] Add `@VisibleForTesting` annotations to private pipeline phase functions where needed.

### Files Changed
- `com/voxyn/core/VoxynConstants.kt` (NEW)
- `com/voxyn/engine/VoxynEngine.kt` (extract constants only)

### Acceptance Criteria
- Baseline test results documented in `build-results.md`.
- Build succeeds, no new lint errors introduced.

---

## Phase 1: Audio Hygiene & Microphone Lifecycle (Milestone 1)

**Goal:** Eliminate audio echo self-triggering and prevent mic conflicts during calls.
**Inspiration:** AI Assistant Android `AudioHygieneProcessor.kt`, Friday Mobile `CallWatcher.kt`
**Effort:** 1–2 days

### Tasks
1. **AudioHygieneProcessor:** In `VoiceInputService.kt`:
   - Change `MediaRecorder.AudioSource.MIC` → `MediaRecorder.AudioSource.VOICE_COMMUNICATION`.
   - After `audioRecord.startRecording()`, call `AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true }`.
   - Same for `NoiseSuppressor` and `AutomaticGainControl`. Guard each with `isAvailable()` check.
2. **Mic Settle Margins:** In `VoiceInputService.kt`, after TTS completes callback:
   - Add `delay(VoxynConstants.MIC_SETTLE_MS)` before calling `audioRecord.startRecording()`.
3. **CallWatcher:** Create `CallWatcher.kt` in `com.voxyn.voice`:
   - Register `PhoneStateListener` or `TelephonyCallback` (API 31+) via `TelephonyManager`.
   - `CALL_STATE_RINGING` or `CALL_STATE_OFFHOOK` → call `voiceService.pauseMicrophone()`.
   - `CALL_STATE_IDLE` → call `voiceService.resumeMicrophone()`.
   - Also watch `AudioManager.mode` for VoIP: if `MODE_IN_COMMUNICATION`, pause.

### Files Changed
- `com/voxyn/voice/VoiceInputService.kt`
- `com/voxyn/voice/CallWatcher.kt` (NEW)
- `com/voxyn/core/VoxynConstants.kt` (add MIC_SETTLE_MS = 200L, FOCUS_SETTLE_MS = 800L)

### Tests to Add/Update
- `VoiceInputServiceTest`: verify AEC is attached when `isAvailable()`.
- `CallWatcherTest`: simulate state transitions, verify pause/resume called.
- `MicSettleTest`: verify delay is applied before restart.

### Acceptance Criteria
- No audio self-trigger events in manual testing with TTS speaking.
- Mic pauses on incoming call simulation.
- All existing tests still pass.

---

## Phase 2: Voice Session Echo Bleed & Barge-In (Milestone 2)

**Goal:** Prevent Vosk/Parakeet from mis-recognizing TTS output as user input.
**Inspiration:** AI Assistant Android `VoiceStateMachine.kt:L44-65`
**Effort:** 0.5–1 day

### Tasks
1. In `VoiceSessionCoordinator.kt`:
   - Add `private var lastTtsStartTimeMs = 0L`.
   - In `onTtsStarted()`: set `lastTtsStartTimeMs = System.currentTimeMillis()`.
   - In `onPartialResult()` and `onFinalResult()`: add guard `if (System.currentTimeMillis() - lastTtsStartTimeMs < ECHO_BLEED_WINDOW_MS) return`.
2. Add `ECHO_BLEED_WINDOW_MS = 300L` to `VoxynConstants`.

### Files Changed
- `com/voxyn/voice/VoiceSessionCoordinator.kt`
- `com/voxyn/core/VoxynConstants.kt`

### Tests
- `VoiceSessionCoordinatorTest`: verify result is suppressed within 300ms of TTS start, and accepted after 300ms.

### Acceptance Criteria
- TTS output no longer re-triggers recognition in manual testing.

---

## Phase 3: Anti-Hallucination Guards & MessageGrounding (Milestone 3)

**Goal:** Enforce that message bodies sent via SEND_MESSAGE/CALL_CONTACT actions are grounded in spoken input, and that TTS after consequential actions uses the capability's own result string.
**Inspiration:** Friday Mobile `FridayAgent.kt` SAYS_ITSELF policy, `MessageGrounding.kt`
**Effort:** 1 day

### Tasks
1. Create `MessageGrounding.kt` in `com.voxyn.engine`:
   ```kotlin
   object MessageGrounding {
       fun isGroundedInSpokenPrompt(messageText: String, rawSpokenInput: String): Boolean
       fun calculateWordOverlap(a: String, b: String): Float
   }
   ```
2. In `VoxynEngine.kt` PLAN phase (where `ActionStep` is constructed for `SEND_MESSAGE` / `SEND_WHATSAPP_MESSAGE`):
   - Before adding the step to the plan, call `MessageGrounding.isGroundedInSpokenPrompt(params["message"]!!, raw)`.
   - If false: reject step, speak "I can only send the message you asked for."
3. In VERIFY phase TTS: for `GROUNDED_ACTIONS`, use `capability.execute(step).value` for TTS, never an LLM-generated summary.

### Files Changed
- `com/voxyn/engine/MessageGrounding.kt` (NEW)
- `com/voxyn/engine/VoxynEngine.kt` (PLAN phase guard + VERIFY TTS rule)

### Tests
- `MessageGroundingTest`: unit test overlap calculation, containment check, edge cases.
- `VoxynEngineTest`: verify that a fabricated message body fails the grounding check.

### Acceptance Criteria
- Sending "send a message to Alice saying Happy Birthday" → only "Happy Birthday" is sent.
- Any LLM-generated message body that is not in the original spoken prompt is rejected.

---

## Phase 4: Accessibility Hardening (Milestone 4)

**Goal:** Fix cold-launch timing failures and apps that override `performAction(ACTION_CLICK)`.
**Inspiration:** Sanna `SannaAccessibilityService.kt:L97-100`
**Effort:** 0.5–1 day

### Tasks
1. In `VoxynAccessibilityService.kt` (or whichever class performs node clicking):
   - Wrap `findAccessibilityNodeInfosByText()` in a retry loop with delays: `[50ms, 100ms, 200ms]`.
   - After each attempt, check if the node is found and `isClickable`.
2. If `performAction(ACTION_CLICK)` returns false:
   - Get `node.getBoundsInScreen(rect)`.
   - Dispatch `GestureDescription` tap at center of `rect`.
3. In all node filtering: `if (info.packageName == "com.voxyn") return` — exclude own overlay.

### Files Changed
- `com/voxyn/perception/VoxynAccessibilityService.kt`

### Tests
- `AccessibilityServiceTest`: mock node returning `performAction = false`, verify gesture dispatch called.
- Verify own-package exclusion.

### Acceptance Criteria
- App launch commands succeed even when target app takes > 200ms to render UI.

---

## Phase 5: Provider Fallback Chain (Milestone 5)

**Goal:** Replace hardcoded Claude fallback with a structured multi-provider fallback chain with cooldown tracking.
**Inspiration:** Open Jarvis `ProviderFallbackChain.kt`
**Effort:** 1.5–2 days

### Tasks
1. Create `ModelProvider.kt` interface in `com.voxyn.ai`:
   ```kotlin
   interface ModelProvider {
       val name: String
       val isLocal: Boolean
       suspend fun resolveIntent(prompt: String, context: String): Result<IntentResult>
       suspend fun generateText(prompt: String, context: String): Result<String>
   }
   ```
2. Implement:
   - `LocalMediaPipeProvider` (wraps existing `LocalModelManager`)
   - `GroqFastProvider` (wraps `HybridGeneralResolver` Groq path)
   - `ClaudeProvider` (wraps existing `ClaudeIntentResolver`)
3. Create `ProviderFallbackChain.kt`:
   - `val providers: List<ModelProvider>` ordered by preference.
   - Track `cooldowns: Map<String, Long>` per provider name.
   - On HTTP 429: `cooldowns[name] = System.currentTimeMillis() + 60_000`.
   - On call: skip providers where `cooldowns[name] > currentTime`.
4. Wire into `VoxynEngine`: replace `claudeResolver?.resolve()` at L177 with `ProviderFallbackChain.resolveIntent()`.
5. Register in `AppModule.kt` (Koin).

### Files Changed
- `com/voxyn/ai/ModelProvider.kt` (NEW)
- `com/voxyn/ai/LocalMediaPipeProvider.kt` (NEW — wraps LocalModelManager)
- `com/voxyn/ai/GroqFastProvider.kt` (NEW — wraps HybridGeneralResolver)
- `com/voxyn/ai/ClaudeProvider.kt` (NEW — wraps ClaudeIntentResolver)
- `com/voxyn/ai/ProviderFallbackChain.kt` (NEW)
- `com/voxyn/engine/VoxynEngine.kt` (wire chain)
- `com/voxyn/di/AppModule.kt` (register in Koin)

### Tests
- `ProviderFallbackChainTest`: verify provider skipped when cooldown active; verify fallback order.
- `LocalMediaPipeProviderTest`: verify wraps LocalModelManager correctly.

### Acceptance Criteria
- When Groq returns 429: next call uses Claude automatically.
- When Claude fails: returns graceful error, not crash.
- Local-first: if `LocalMediaPipeProvider` returns Success, remote never called.

---

## Phase 6: Learned Command Cache (Milestone 6)

**Goal:** Short-circuit LLM calls for repeat commands with < 5ms lookup.
**Inspiration:** Friday Mobile `LearnedCommands.kt`
**Effort:** 0.5–1 day

### Tasks
1. Add `learned_commands` Room table to `MemoryRepository`:
   ```kotlin
   @Entity("learned_commands")
   data class LearnedCommand(
       @PrimaryKey val normalizedPhrase: String,
       val actionType: String,
       val paramsJson: String,
       val resolvedBy: String,
       val successCount: Int,
       val lastUsedMs: Long
   )
   ```
2. Add `suspend fun matchLearned(phrase: String): ActionStep?` to `MemoryRepository`.
3. Add `suspend fun cacheCommand(phrase: String, step: ActionStep)` to `MemoryRepository`.
4. In `VoxynEngine.kt` INTENT phase, before `IntentRouter.resolve()`:
   - Call `memory.matchLearned(perceived)`. If non-null: build single-step plan, skip LLM.
5. In LEARN phase: if `VerificationOutcome.Success` and `resolvedBy != LEARNED_CACHE`:
   - Call `memory.cacheCommand(perceived, plan.steps.first())`.

### Files Changed
- `com/voxyn/memory/MemoryRepository.kt`
- `com/voxyn/engine/VoxynEngine.kt`

### Tests
- `MemoryRepositoryTest`: verify store and retrieval of learned command.
- `VoxynEngineTest`: verify LLM not invoked when learned command matches.

### Acceptance Criteria
- "Open WhatsApp" → learned after first successful use; < 5ms on repeat.

---

## Phase 7: Contextual Memory Extraction (Milestone 7)

**Goal:** Passively extract personal facts from conversation without LLM overhead.
**Inspiration:** AI-Buddy `contextual_memory_extractor.dart`
**Effort:** 1 day

### Tasks
1. Create `ContextualMemoryExtractor.kt` in `com.voxyn.memory`:
   - Define regex patterns for: name ("my name is (.+)", "I am (.+)"), preferences ("I (prefer|like|love) (.+)"), relationships ("my (wife|husband|sister|boss|friend) (?:is )?(.+)"), routines ("I usually (.+) at (.+)").
   - Score each extraction (0.0–1.0). If score >= 0.8, store in `MemoryRepository`.
2. Call `ContextualMemoryExtractor.extract(raw)` in LEARN phase of `VoxynEngine`.

### Files Changed
- `com/voxyn/memory/ContextualMemoryExtractor.kt` (NEW)
- `com/voxyn/engine/VoxynEngine.kt` (LEARN phase call)

### Tests
- `ContextualMemoryExtractorTest`: "My name is Surajith" → extracts `name = "Surajith"`.
- "I prefer dark mode" → extracts `preference = "dark mode"`.
- Verify false-positive patterns do not trigger storage.

### Acceptance Criteria
- After user says "my name is X", `VOXYN, what is my name?` correctly recalls X.

---

## Phase 8: Dynamic Island Waveform & State Refinement (Milestone 8)

**Goal:** Enhance `DynamicIsland` with RMS-driven audio waveform and the full state set.
**Inspiration:** Open Jarvis `FloatingOverlayWidget.kt`, Friday Mobile arc reactor canvas
**Effort:** 1 day

### Tasks
1. Add `fun updateRms(rmsDb: Float)` to `DynamicIsland.kt`.
2. In `VoiceInputService`, compute RMS from each `audioRecord.read()` buffer frame and call `dynamicIsland.updateRms(rms)` on each frame (via Handler.post, not direct call from IO thread).
3. Add `EXECUTING` state to `IslandState` enum (verify not already present).
4. Implement waveform Canvas drawing in `SPEAKING` state using the RMS value.
5. Add spring-interpolated width transitions between states.

### Files Changed
- `com/voxyn/overlay/DynamicIsland.kt`
- `com/voxyn/voice/VoiceInputService.kt`

### Tests
- `DynamicIslandTest`: verify state transitions and paint colors for each state.
- Verify `updateRms` is called from correct thread (not main thread directly from IO).

### Acceptance Criteria
- Visual waveform visible during TTS playback.
- Smooth animated transition between LISTENING and THINKING states.

---

## Phase 9: ONNX Speaker Verification (Milestone 9)

**Goal:** Replace heuristic speaker matching with deep neural 256-d embedding + cosine gate.
**Inspiration:** Friday Mobile `SpeakerEmbedder.kt`, `KaldiFbank.kt`
**Effort:** 2–3 days

### Tasks
1. Add `onnxruntime-android:1.20.0` to `libs.versions.toml` (or confirm Sherpa-ONNX exposes session API).
2. Add `voxceleb_resnet34_LM.onnx` to `app/src/main/assets/` (CC-BY-4.0 — add attribution to NOTICE).
3. Create `KaldiFbank.kt` in `com.voxyn.security`: 80-bin mel-filterbanks + CMVN.
4. Create `SpeakerEmbedder.kt` in `com.voxyn.security`: ONNX session → 256-d float output.
5. Update `SpeakerProfile.kt`: replace `audioSignature: FloatArray` with `embedding: FloatArray` (size 256).
6. Update `CommandVerificationGate.kt`: cosine similarity threshold = 0.68.
7. Update `VoiceEnrollmentManager.kt`: enrollment now runs `SpeakerEmbedder` and stores real embedding.
8. Add `NOTICE` file: "Portions use WeSpeaker voxceleb_resnet34_LM (CC-BY-4.0) — https://github.com/wenet-e2e/wespeaker".

### Files Changed
- `gradle/libs.versions.toml`
- `com/voxyn/security/KaldiFbank.kt` (NEW)
- `com/voxyn/security/SpeakerEmbedder.kt` (NEW)
- `com/voxyn/security/SpeakerProfile.kt`
- `com/voxyn/security/CommandVerificationGate.kt`
- `com/voxyn/security/VoiceEnrollmentManager.kt`
- `app/src/main/assets/voxceleb_resnet34_LM.onnx` (NEW — download from WeSpeaker)
- `NOTICE` (NEW or update)

### Tests
- `KaldiFbankTest`: verify 80-bin output shape for known audio.
- `SpeakerEmbedderTest`: verify 256-d output; same speaker produces cosine similarity > 0.68.
- `CommandVerificationGateTest`: different speaker produces cosine similarity < 0.68.

### Acceptance Criteria
- Enrollment generates real neural embedding (not synthetic).
- Different-voice test fails gate at 0.68 threshold.

---

## Phase 10: Self-Healing Executor (Milestone 10)

**Goal:** Replace blind retry with screen-context-aware alternate step generation.
**Inspiration:** Open Jarvis `SelfHealingExecutor.kt:L33-52`
**Effort:** 1.5 days

### Tasks
1. Add `captureScreenContext(): String` to `VoxynAccessibilityService` — returns top-level node text dump (max 500 chars).
2. In `ExecutionController.kt` (or `VoxynEngine.kt` executeWithRecovery at L638):
   - On step failure attempt #1: capture screen context via AccessibilityService.
   - Call `healStep(originalStep, screenContext)` to synthesize an alternate step (simple heuristic: find matching text node, update actionType to CLICK_TEXT if original was CLICK_NODE).
   - Attempt healed step.
3. Log each healing attempt with `VoxynLogger.w()`.

### Files Changed
- `com/voxyn/perception/VoxynAccessibilityService.kt`
- `com/voxyn/execution/ExecutionController.kt`
- `com/voxyn/engine/VoxynEngine.kt`

### Tests
- `ExecutionControllerTest`: simulate first attempt failure + healing producing alternate step.

### Acceptance Criteria
- Failed "open Settings WiFi" recovers by finding "Wi-Fi" text node instead of package name node.

---

## Dependency Map

```
Phase 0 (Baseline)
  └── Phase 1 (Audio Hygiene + Call Watch)
        └── Phase 2 (Echo Bleed)
              └── Phase 3 (Anti-Hallucination)
Phase 1 is also prerequisite for Phase 8 (RMS to DynamicIsland)
Phase 3 is prerequisite for Phase 5 (Provider Chain wires into same INTENT path)
Phase 5 is prerequisite for Phase 6 (Learned Cache uses same INTENT hook)
Phase 6 is prerequisite for Phase 7 (Learned Cache + Memory in same LEARN phase)
Phase 9 (Speaker Verification) is independent — can run in parallel with Phases 3–7
Phase 10 (Self-Healing) depends on Phase 4 (Accessibility Hardening)
```

---

## Milestone Acceptance Gate (Before Merging Any Milestone)

1. `./gradlew :app:test` — 0 new failures.
2. `./gradlew :app:lintDebug` — 0 new errors (warnings tolerated).
3. `./gradlew :app:assembleDebug` — APK builds successfully.
4. Manual smoke test: voice command "open WhatsApp" executes correctly.
5. No `println` in changed files (use `VoxynLogger`).
6. No hardcoded API keys or credentials.
7. No new permissions requested without explicit user flow.
