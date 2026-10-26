# VOXYN Pipeline Contracts
**Status: Prompt 2 Deliverable — Evidence-Based Blueprint (No source code modified)**

Every contract below is grounded in the verified VOXYN source:
- [`Contracts.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/core/Contracts.kt)
- [`PipelineEvent.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/core/PipelineEvent.kt)
- [`Capability.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/execution/Capability.kt)
- [`VoxynEngine.kt`](file:///c:/Users/srsur/Downloads/Voxyn/Voxyn2/app/src/main/java/com/voxyn/engine/VoxynEngine.kt)

---

## The 10-Phase Immutable Pipeline

```
INPUT → PERCEPTION → INTENT → CONFIDENCE → RISK → DECISION → PLAN → EXECUTE → VERIFY → LEARN
```

The pipeline is unidirectional and non-negotiable. A phase may **short-circuit** (return null/abort) but never flows backward. Every phase boundary is defined by an explicit data contract below.

---

## Phase 0 → 1: INPUT Contract

### Input Envelope (Proposed extension — wraps existing String parameter)

```kotlin
data class InputEnvelope(
    val raw: String,
    val sourceType: InputSource,
    val timestampMs: Long,
    val audioRmsDb: Float = 0f,
    val isContinuation: Boolean = false,
    val speakerVerified: Boolean = false
)
enum class InputSource { VOICE, TEXT, NOTIFICATION, SHORTCUT }
```

**Existing hook:** `VoxynEngine.process(input: String)` at VoxynEngine.kt:L84.
`InputEnvelope.raw` maps 1:1 to the current `input` String parameter.

**Events emitted:**
```kotlin
PipelineEvent.PhaseStarted(PipelinePhase.INPUT)
PipelineEvent.PhaseCompleted(PipelinePhase.INPUT, raw)
```

**Early-exit:**
- `raw.isBlank()` → silent drop; no events emitted.
- `speakerVerified == false` AND action in HIGH_RISK_ACTIONS → `PhaseFailed(INPUT, "UNKNOWN_SPEAKER", canFallback = false)`.

---

## Phase 1 → 2: PERCEPTION Contract

```kotlin
data class PerceivedText(
    val normalized: String,
    val appNameCandidates: List<String>,
    val contactCandidates: List<String>,
    val detectedLocale: Locale,
    val confidenceScore: Float
)
```

**Existing hook:** `perceive(raw)` calls `SpeechNormalizer` at VoxynEngine.kt:L120.

**Phase rules:**
- Phonetic app name resolution: "insta" → "instagram".
- Number normalization: "call two zero seven" → "call 207".
- Command injection guard: reject inputs with shell metacharacters.

---

## Phase 2 → 3: INTENT Contract

```kotlin
// Extends existing IntentResult in Contracts.kt — additive fields only
data class IntentResult(
    val action: String,
    val params: Map<String, String> = emptyMap(),
    val confidence: Float = 0f,
    val rawInput: String = "",
    // New additive fields:
    val resolvedBy: IntentSource = IntentSource.INTENT_ROUTER,
    val alternativeActions: List<String> = emptyList(),
    val learnedCacheHit: Boolean = false
)

enum class IntentSource {
    LEARNED_CACHE, INTENT_ROUTER, LOCAL_LLM, GROQ_FAST, CLAUDE, GENERAL_RESOLVER
}
```

**Resolution priority:**
1. LearnedCommandStore.match() → learnedCacheHit = true, skip LLM.
2. RequestRouter.route() → memory / web routes bypass IntentRouter.
3. IntentRouter.resolve() → deterministic regex.
4. action == "UNKNOWN" → ProviderFallbackChain.resolveIntent().

---

## Phase 3 → 4: CONFIDENCE Contract

```kotlin
data class ConfidenceAssessment(
    val weightedScore: Float,            // = intent.confidence * 0.7 + memoryTrust * 0.3
    val intentConfidence: Float,
    val memoryTrustScore: Float,
    val thresholdMet: Boolean,           // weightedScore >= 0.65f
    val clarificationRequired: Boolean
)
```

**Constants (VoxynConstants.kt):**
```kotlin
const val CONFIDENCE_THRESHOLD = 0.65f
const val HIGH_RISK_CONFIRMATION_THRESHOLD = 0.85f
const val LEARNED_CACHE_CONFIDENCE = 1.0f
```

---

## Phase 4 → 5: RISK Contract

```kotlin
data class RiskAssessment(
    val riskLevel: RiskLevel,
    val riskScore: Float,                // 0.0–1.0
    val requiresConfirmation: Boolean,
    val requiresSpeakerVerification: Boolean,
    val sensitiveParams: List<String>
)

enum class RiskLevel { SAFE, MODERATE, HIGH, CRITICAL }
```

**Risk classification policy:**
| Action Pattern | RiskLevel | riskScore | Requires Confirm | Requires Speaker |
|:---|:---|:---|:---|:---|
| OPEN_APP, READ_*, GET_* | SAFE | 0.1 | false | false |
| SET_ALARM, SET_REMINDER | MODERATE | 0.4 | false | false |
| SEND_MESSAGE, SEND_WHATSAPP | HIGH | 0.8 | true | true |
| CALL_CONTACT | HIGH | 0.85 | true | true |
| DELETE_FILE, LOCK_PHONE | CRITICAL | 0.95 | true | true |

---

## Phase 5 → 6: DECISION Contract

```kotlin
// Existing in Contracts.kt — no changes needed
sealed class DecisionState {
    object Execute : DecisionState()
    data class AwaitingConfirmation(val intent: IntentResult) : DecisionState()
    data class AwaitingClarification(val missing: List<String>) : DecisionState()
    object Rejected : DecisionState()
}
```

**Decision logic:**
```
if (requiresSpeakerVerification && !speakerVerified) → Rejected
else if (clarificationRequired) → AwaitingClarification
else if (requiresConfirmation) → AwaitingConfirmation (timeout: 15s)
else → Execute
```

---

## Phase 6 → 7: PLAN Contract

```kotlin
// Existing in Contracts.kt — no changes needed
data class ActionStep(
    val id: String,           // UUID for idempotency
    val description: String,
    val actionType: String,
    val params: Map<String, String> = emptyMap()
)

data class ExecutionPlan(
    val steps: List<ActionStep>,
    val currentStep: Int = 0
)
```

**Anti-hallucination enforcement (SAYS_ITSELF / MessageGrounding):**
```kotlin
val GROUNDED_ACTIONS = setOf("SEND_MESSAGE", "SEND_WHATSAPP_MESSAGE", "SEND_EMAIL")

fun isGroundedInSpokenPrompt(messageText: String, rawSpokenInput: String): Boolean {
    val cleanMessage = messageText.lowercase().trim()
    val cleanInput = rawSpokenInput.lowercase().trim()
    return cleanInput.contains(cleanMessage) ||
           calculateWordOverlap(cleanMessage, cleanInput) >= 0.75f
}
```
For any step in GROUNDED_ACTIONS, `params["message"]` must pass this check or the step is rejected before execution.

---

## Phase 7 → 8: EXECUTE Contract

```kotlin
// Existing Capability.kt interface — currently correct
interface Capability {
    val id: String
    val supportedActions: Set<String>
    val riskLevel: CapabilityRisk
    val requiredPermissions: Set<String>
    val requiresNetwork: Boolean
    val retrySafe: Boolean get() = false
    suspend fun execute(step: ActionStep): PhaseResult<String>
    suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome
}
```

**Self-healing retry (proposed addition to ExecutionController):**
```kotlin
// Current: VoxynEngine.kt:L638 repeats the same failed step blindly.
// Proposed: Re-inspect screen state before retry #2.
suspend fun executeWithSelfHealing(
    step: ActionStep,
    capability: Capability,
    screenContext: String?
): PhaseResult<String> {
    val result1 = capability.execute(step)
    if (result1 is PhaseResult.Success) return result1
    val healedStep = if (screenContext != null) healStep(step, screenContext) else step
    return capability.execute(healedStep)
}
```

---

## Phase 8 → 9: VERIFY Contract

```kotlin
// Existing in Contracts.kt — no changes needed
sealed class VerificationOutcome {
    object Success : VerificationOutcome()
    object PartialSuccess : VerificationOutcome()
    object Failure : VerificationOutcome()
    object NeedsRollback : VerificationOutcome()
    object Unverified : VerificationOutcome()
}
```

**Verification by capability:**
| Capability | Verification Method | Success Condition |
|:---|:---|:---|
| AppLaunchCapability | ActivityManager.getRunningTasks() | Target app in foreground |
| SendMessageCapability | Sent-box timestamp or intent result | Message in sent folder |
| AlarmCapability | AlarmManager pending intents query | Alarm at requested time |
| CallCapability | TelephonyManager.callState monitor | CALL_STATE_OFFHOOK |
| SystemCapability | Post-action setting query | Setting matches request |

**SAYS_ITSELF TTS rule:** For GROUNDED_ACTIONS, TTS text comes from capability.execute().value — never from LLM summary.

---

## Phase 9 → 10: LEARN Contract

```kotlin
data class LearningRecord(
    val action: String,
    val normalizedPhrase: String,
    val outcome: VerificationOutcome,
    val resolvedBy: IntentSource,
    val latencyMs: Long,
    val timestampMs: Long = System.currentTimeMillis()
)
```

**LEARN actions:**
1. MemoryRepository.recordOutcome(record) — updates trust score.
2. If outcome == Success && resolvedBy != LEARNED_CACHE → cache phrase → step mapping.
3. ContextualMemoryExtractor.extractAndStore(rawInput) — auto-extract personal facts.

**Events:** `PipelineEvent.TrustScoreUpdated`, `PipelineEvent.PipelineComplete`.

---

## PipelineEvent Bus Rules

- `SharedFlow<PipelineEvent>` (buffer capacity = 64) is the ONLY channel to UI/overlay.
- UI reads events; never writes back to engine.
- Shell layer consumes the same flow via a separate `collect` call.

---

## Contract Compliance Checklist

| Phase | Contract Type | Anti-Hallucination | Events |
|:---:|:---|:---:|:---:|
| INPUT | InputEnvelope | Speaker gate | PhaseStarted, PhaseCompleted |
| PERCEPTION | PerceivedText | Injection guard | PhaseStarted, PhaseCompleted |
| INTENT | IntentResult (extended) | SAYS_ITSELF prep | PhaseStarted, PhaseCompleted |
| CONFIDENCE | ConfidenceAssessment | — | ClarificationRequired |
| RISK | RiskAssessment | — | PhaseStarted, PhaseCompleted |
| DECISION | DecisionState | Speaker verify gate | DecisionMade, ConfirmationRequired |
| PLAN | ExecutionPlan | MessageGrounding | PhaseStarted, PhaseCompleted |
| EXECUTE | PhaseResult<String> | SAYS_ITSELF TTS | StepStarted, StepCompleted |
| VERIFY | VerificationOutcome | Capability-owned TTS | VerificationResult |
| LEARN | LearningRecord | — | TrustScoreUpdated, PipelineComplete |
