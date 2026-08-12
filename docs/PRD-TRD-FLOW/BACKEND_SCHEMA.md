# VOXYN — Backend & Data Schema

Version: 1.0.19 / Status: Living / Owner: VOXYN Core

VOXYN is **offline-first**: the "backend" is the on-device persistence layer
(DataStore + files + in-memory session state). This document defines every data
contract, its storage, and (as a future option) a minimal cloud sync schema.

## 1. Storage map

| Store | Tech | Contents | Lifetime |
| --- | --- | --- | --- |
| `voxyn_memory` | Preferences DataStore | trust scores, memory notes | Persistent |
| `filesDir/models/asr/` | Files | Parakeet encoder/decoder/joiner, tokens, VAD | Persistent |
| `filesDir/model/` | Files | Vosk wake model (extracted from assets) | Persistent |
| `filesDir/` | File | Local LLM `.task` (~554 MB) | Persistent |
| `/sdcard/Download/VoxynAI/` | Raw files | Backup of ASR + LLM models (reinstall-safe) | User-owned |
| MediaStore Downloads | ContentProvider | Mirror of model backups + `voxyn_status.log` | Persistent |
| `/sdcard/Download/voxyn-debug.log` | Raw file | Runtime/crash log for diagnostics | Debug |
| In-memory | Singletons | `ConversationSession`, `ContextEngine`, recognizers | Process |

## 2. Core domain contracts (Kotlin)

```kotlin
// Conversation
data class ConversationTurn(
    val userInput: String,
    val action: String,
    val response: String,
    val timestamp: Long = System.currentTimeMillis()
)

class ConversationSession {          // Koin singleton, in-memory only
    val turns: MutableList<ConversationTurn>
    var isActive: Boolean
    var lastInteractionTime: Long
    val windowMs = 30_000L
    fun isWithinWindow(): Boolean
    fun addTurn(turn: ConversationTurn)
    fun updateLastTurn(action: String, response: String)
    fun getContext(): String          // last 3 turns: "User: …\nVOXYN: …"
    fun end()
}

// Intent
data class IntentResult(
    val action: String,               // "OPEN_APP", "SET_TIMER", "UNKNOWN", ...
    val params: Map<String, String>,  // e.g. app_name, contact, destination
    val confidence: Float,
    val rawInput: String
)

data class ActionStep(
    val id: String,
    val description: String,
    val actionType: String,
    val params: Map<String, String>
)

// Pipeline events
sealed class PipelineEvent {
    PhaseStarted(phase: PipelinePhase)
    PhaseCompleted(phase, summary)
    PhaseFailed(phase, error)
    DecisionMade(state)
    ExecutionStepStarted(step)
    ExecutionStepCompleted(result)
    VerificationResult(outcome)
    EngineInterrupted(reason)
    ClarificationRequired(missing)
    ConfirmationRequired(intent)
    TrustScoreUpdated(action, score)
    PipelineComplete
}

enum class PipelinePhase {
    INPUT, PERCEPTION, INTENT, CONFIDENCE, RISK,
    DECISION, PLAN, EXECUTE, VERIFY, LEARN
}
```

## 3. DataStore schema — `voxyn_memory`

Preferences DataStore (key → type).

| Key | Type | Meaning |
| --- | --- | --- |
| `<ACTION>_success` | Int | Successful executions of an action (per action key) |
| `<ACTION>_failure` | Int | Failed executions |
| `memory_notes_json` | String | JSON array of personal memory notes |

Trust score formula (per action):

```
trust = success / (success + failure), clamped to [0.1, 0.95]; default 0.5
```

### memory_notes_json

```json
[
  "My meeting is on Friday at 10 am",
  "I prefer coffee without sugar",
  "Call mom after 7 pm on weekdays"
]
```

Notes are matched to spoken topics by shared meaningful words (`recallNotes`).

## 4. File schemas

### Model backup manifest (`/sdcard/Download/VoxynAI/`)

| File | Size (expected) | Used by |
| --- | --- | --- |
| `asr/voxyn-asr-encoder.int8.onnx` | 652,184,281 B | Parakeet STT |
| `asr/voxyn-asr-decoder.int8.onnx` | 11,845,275 B | Parakeet STT |
| `asr/voxyn-asr-joiner.int8.onnx` | 6,355,277 B | Parakeet STT |
| `asr/voxyn-asr-tokens.txt` | 93,939 B | Parakeet STT |
| `asr/voxyn-asr-silero_vad.onnx` | 643,854 B | VAD |
| `voxyn-llm.task` | 554,661,243 B | Local LLM (MediaPipe) |

Restore order: private copy → MediaStore → raw path (needs All-files access) →
resumable download (`<name>.part` preserved; Range requests; size validation).

### CHAIN intent payload

`IntentRouter` splits "open X and set timer" into a JSON array of steps:

```json
["OPEN_APP:YouTube", "SET_ALARM"]
```

stored in `IntentResult.params["steps"]`.

### Debug log line format

```
I WAKE: Wake phrase detected — waking up
E FATAL: Uncaught on main: java.lang.OutOfMemoryError: ...
```

## 5. MediaStore backup rows

| Collection | Display name | Content type | Relative path |
| --- | --- | --- | --- |
| Downloads | `voxyn_status.log` | text/plain | `Download/` |
| Downloads | `voxyn-asr-*` model files | application/octet-stream | `Download/VoxynAI/asr/` |
| Downloads | `voxyn-llm.task` | application/octet-stream | `Download/VoxynAI/` |

## 6. Event/session context contract

Conversation context string sent to the LLM / resolver for follow-ups
(no PII beyond the words already spoken):

```
Prior conversation:
User: Turn on flashlight
VOXYN: Flashlight on.

User: Open YouTube
VOXYN: Opening YouTube.
```

## 7. Optional cloud sync (future, not in V1)

Only if multi-device or web is added. Privacy-first defaults: nothing leaves
the device unless explicitly enabled; sync is E2E-encrypted; audio never syncs.

Proposed REST schema (v1 proposal — document, don't build yet):

```
POST /v1/sync/up        → { delta: [ {id, type: "memory|conversation|preference", payload, updated_at} ] }
GET  /v1/sync/down?since → same delta envelope
Entity "conversation":   { id, app_id: "com.voxyn", device_id, started_at, turns: ConversationTurn[] }
Entity "memory":         { id, kind: "trust|note|preference", action?, score?, text?, created_at }
Entity "preference":     { key, value_json }
```

Local-first rule: cloud is a replicating cache of the on-device DataStore,
never the source of truth.
