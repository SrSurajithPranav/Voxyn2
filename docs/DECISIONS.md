# VOXYN Architecture Decision Records (ADR)

Version: 1.0

Status: Living Document

Owner: VOXYN Core

Related Documents:
- 00_PROJECT_OVERVIEW.md
- 01_ARCHITECTURE.md
- 02_CODING_RULES.md
- 03_CURRENT_STATE.md
- 05_PHASE_ROADMAP.md

---

# Purpose

This document records the major engineering decisions made during the development of VOXYN.

Architecture is not only *what* was chosen.

It is also *why* it was chosen.

Every significant architectural decision should be documented here before implementation.

If a future contributor asks:

> "Why was this designed this way?"

The answer should exist in this document.

---

# ADR Format

Every decision follows this structure:

Decision ID

Status

Context

Alternatives Considered

Decision

Consequences

Review Date

---

# ADR-001

## Title

Use Koin instead of Hilt

### Status

Accepted

### Context

VOXYN requires lightweight dependency injection.

The project values:

- Simplicity
- Fast compilation
- Easy debugging
- Runtime flexibility

### Alternatives

Hilt

Dagger

Manual Dependency Injection

Service Locator

### Decision

Use Koin.

### Reasoning

Koin provides:

- Constructor injection
- Minimal boilerplate
- Easier onboarding
- Faster iteration

Compile-time DI was considered unnecessary for the current project size.

### Consequences

Positive

✓ Cleaner code

✓ Faster development

✓ Less generated code

Negative

Slightly weaker compile-time validation.

---

# ADR-002

## Title

Immutable AI Pipeline

### Status

Accepted

### Context

Many assistants evolve into inconsistent execution paths.

VOXYN requires predictable reasoning.

### Decision

Freeze the pipeline.

INPUT

↓

PERCEPTION

↓

INTENT

↓

CONFIDENCE

↓

RISK

↓

DECISION

↓

PLAN

↓

EXECUTE

↓

VERIFY

↓

LEARN

### Consequences

Positive

Predictable behavior

Simpler debugging

Stable architecture

Better documentation

Negative

Pipeline changes require significant discussion.

---

# ADR-003

## Title

Capability-Based Execution

### Status

Accepted

### Context

ExecutionController becomes difficult to maintain as capabilities increase.

### Alternatives

Large switch statement

Reflection

Plugin framework

Capability registry

### Decision

Each Android action becomes an independent Capability (Tool).

Execution dispatches through a registry.

### Benefits

Independent testing

Better scalability

Cleaner architecture

Lower coupling

Future plugin support

---

# ADR-004

## Title

Local-First Architecture

### Status

Accepted

### Context

Users should not lose functionality without internet.

### Decision

Prefer local execution whenever practical.

Cloud reasoning should enhance the experience, not enable it.

### Consequences

Voice commands remain usable offline.

Critical phone controls remain available.

Improved privacy.

Reduced latency.

---

# ADR-005

## Title

AI Provider Abstraction

### Status

Accepted

### Context

The AI ecosystem changes rapidly.

Locking VOXYN to a single provider creates long-term risk.

### Decision

Planner depends on an AI Provider interface.

Providers become interchangeable implementations.

### Supported Providers

OpenAI

Claude

Gemini

Groq

DeepSeek

OpenRouter

Ollama

Future providers

---

# ADR-006

## Title

Privacy First

### Status

Accepted

### Decision

User data belongs to the user.

Collection must always be minimal.

Cloud upload must be intentional.

No hidden telemetry.

No unnecessary tracking.

---

# ADR-007

## Title

Explainable Decisions

### Status

Accepted

### Context

Users should understand why VOXYN acts.

### Decision

Major actions must be explainable.

Planner decisions should be traceable.

Risk decisions should be inspectable.

---

# ADR-008

## Title

Modes Instead of Personalities

### Status

Accepted

### Context

Traditional assistants expose personalities.

VOXYN changes behavior using Modes.

Examples

Driving Mode

Meeting Mode

Gaming Mode

Study Mode

Sleep Mode

Emergency Mode

### Benefits

Behavior changes remain deterministic.

Less prompt engineering.

Cleaner architecture.

---

# ADR-009

## Title

Verification Before Learning

### Status

Accepted

### Context

Learning from failed actions creates poor behavior.

### Decision

Every action should be verified before becoming part of learning history.

---

# ADR-010

## Title

Documentation First

### Status

Accepted

### Decision

Architecture evolves through documentation before implementation.

Every major feature requires:

Architecture update

Decision record

Current state update

Roadmap update

Implementation

Never implementation first.

---

# Future ADRs

Future decisions should be recorded for topics such as:

Plugin SDK

Memory implementation

Learning engine

Vector database

Cross-device synchronization

Automation engine

Security model

Permission framework

Developer SDK

Desktop support

Wear OS

Smart home

Enterprise mode

Distributed execution

---

# Decision Lifecycle

Every architecture decision follows:

Proposal

↓

Discussion

↓

Evaluation

↓

Acceptance

↓

Implementation

↓

Documentation

↓

Review

Rejected ideas should also be documented when valuable.

---

# Updating This Document

Create a new ADR whenever:

Architecture changes.

A major dependency changes.

A core module is redesigned.

A long-term engineering decision is made.

Never overwrite historical decisions.

Instead:

Supersede them with a new ADR.

---

# Success Criteria

This document succeeds when:

A future contributor understands not only *how* VOXYN works,

but *why* it works that way.

Engineering knowledge should never exist only in conversations.

It should exist permanently in documentation.

---

End of Document


---

# ADR-011

## Title

Communication delivery uses compose intents, never auto-send

## Status

Accepted

## Context

VOXYN must support "Message Rahul saying I'll be late", "Send WhatsApp to Rahul I'm coming", "Call Mom" and related commands. Sending a message is an external side effect; WhatsApp exposes no public API for automatically sending messages to arbitrary personal accounts, and auto-sending SMS requires SEND_SMS special-app access that OEMs (OnePlus/ColorOS) restrict. The user chose compose-style delivery.

## Alternatives Considered

1. Auto-send SMS via SEND_SMS permission — rejected: unreliable on the target device, no benefit for WhatsApp.
2. Coordinate-based UI automation — rejected: brittle and explicitly forbidden by milestone rules.
3. Accessibility-driven send — rejected: only works when the target app is already open and focused.

## Decision

Messages are delivered with compose intents:

- SMS: `ACTION_SENDTO sms:<phone>` + `sms_body` (opens the SMS app prefilled; user taps Send).
- WhatsApp: `ACTION_VIEW https://wa.me/<digits>?text=<encoded>` when the number has a country code, falling back to `Intent.ACTION_SEND` (`text/plain`, package `com.whatsapp`).
- Calls: `ACTION_CALL` with the resolved contact's number, falling back to `ACTION_DIAL` when CALL_PHONE is not granted.
- Group messaging: `MESSAGE_GROUP` is routed and parsed but responds "I can't send to groups yet" — a deliberate seam for future work; no fake group support.

All risky actions (CALL_CONTACT, SEND_MESSAGE, SEND_WHATSAPP_MESSAGE) go through the existing confirmation flow; a follow-up "yes" resumes the pending intent, "no" cancels. Ambiguous contacts are never auto-chosen — VOXYN asks "I found two Rahuls. Which one?".

## Consequences

- TTS is honest about the outcome ("Opened WhatsApp to Rahul.") rather than claiming auto-send.
- No SEND_SMS permission is added to the manifest.
- Contacts are resolved from Android Contacts plus a small static alias map (mom/dad/etc.); custom aliases via memory are future work.

## Review Date

2026-08-10

---

# ADR-012

## Title

SMS auto-send via SmsManager; WhatsApp remains compose-only

## Status

Accepted (supersedes ADR-011's SMS delivery choice)

## Context

The user wants real auto-send for messages. SMS can be delivered programmatically
with the SEND_SMS runtime permission, so compose-only SMS is no longer the right
default. WhatsApp still cannot auto-send to arbitrary personal accounts through
any public API.

## Alternatives Considered

1. Keep SMS compose-only — rejected: user explicitly requested auto-send.
2. Auto-send via accessibility/tap automation — rejected: brittle and only works while the target app is open.

## Decision

SMS messages are auto-sent with `SmsManager.sendTextMessage()` after the user
confirms the action ("Just to confirm — should I message Rahul? Say yes.").
The manifest requests `SEND_SMS` at app launch; if the permission is missing,
VOXYN answers "I need SMS permission to auto-send" instead of silently doing
nothing. A result broadcast logs actual send status (`SENT` / failure).

WhatsApp delivery stays compose-only (`wa.me` deep link → `ACTION_SEND`
fallback) and the TTS is honest: "Opened WhatsApp to Rahul."

## Consequences

- `SEND_SMS` is added to the manifest and runtime permission flow.
- The confirmation gate still applies — auto-send never happens silently.
- WhatsApp auto-send and group targeting remain documented limitations.

## Review Date

2026-08-10

---

# ADR-013

## Title

Auto-restore on-device models (ASR + LLM) at launch; reject corrupt downloads

## Status

Accepted (extends ADR-004/ADR-008 model management)

## Context

After a reinstall the app fell back to the weak Vosk command STT and the local
LLM failed with "Unable to open zip archive" because the Downloads backup held a
raw TFLite flatbuffer (1.35 GB) instead of a valid MediaPipe `.task` zip. The
user should never need the settings screen to make VOXYN offline-capable again.

## Decision

`VoxynApplication` restores both models on a background IO coroutine at every
launch:

- `ParakeetModelManager.ensureAvailable()` copies the Parakeet ASR files back
  from `Download/VoxynAI/asr` (or verifies the private copy) so the command STT
  uses Parakeet instead of the Vosk fallback.
- `LocalModelManager.ensureModelAvailable()` restores `llm.task` from
  `Download/VoxynAI/voxyn-llm.task`; when no valid backup exists it downloads
  in the background so the app heals itself without UI interaction.

Both managers already validate `.task` files by requiring a `PK` local-file
header and `PK\x05\x06` end-of-central-directory marker, which rejects raw
TFLite flatbuffers and truncated downloads. A `downloadInProgress` guard in
`LocalModelManager` prevents concurrent downloads (app-launch restore racing
the settings button).

## Consequences

- Fresh installs with model files in `Download/VoxynAI` become fully offline in
  seconds without opening the app.
- Corrupt backups are detected, never copied, and trigger a fresh download.
- The correct model is `gemma3-1B-it-int4.task` (554,661,243 bytes,
  `JafarKhan1989/gemma-3-1b-mediapipe`), verified by header/tail magic.

## Review Date

2026-08-10

---

# ADR-014

## Title

Camera capability architecture and Android background execution compliance

## Status

Accepted

## Context

User requires photo and video commands ("Take a photo", "Take a photo in 10 seconds", "Record a video for 30 seconds"). On modern Android (API 28+, API 34+ FGS restrictions), accessing camera sensors silently from background services without an active foreground UI is blocked by the platform security model.

## Decision

Implement `CameraCapability` implementing the `Capability` interface:
- Enforces `android.permission.CAMERA` runtime check.
- Handles countdown delays ("in 10 seconds") cleanly.
- Dispatches legitimate system camera intents (`MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA`, `MediaStore.INTENT_ACTION_VIDEO_CAMERA`).
- Integrates with `CapabilityRegistry` and `ExecutionController` with honest reporting.

## Consequences

- Full compliance with Android platform security rules.
- Transparent feedback to the user without brittle or unsafe hacks.

## Review Date

2026-08-29

---

# ADR-015

## Title

Multi-stop distance routing and rhotacism speech normalization

## Status

Accepted

## Context

Users with rhotacism or acoustic STT confusions produce phonetic variants ("chat jeep tee" -> ChatGPT, "you do" -> YouTube, "hunger books" -> HungerBox). Additionally, geographic queries frequently involve waypoints ("how far is Chennai to Bangalore via Hyderabad").

## Decision

1. Extend `SpeechNormalizer` to deterministically correct phonetic variants and map against installed package labels prior to intent routing.
2. Extend `RequestRouter` and `HybridGeneralResolver` to detect waypoints ("via", multi-stop commas), query `OsmDistanceProvider` for each leg, and synthesize an aggregate road distance and estimated driving duration.

## Consequences

- Robust recognition without requiring perfect pronunciation.
- Accurate multi-leg geographic distance queries without cloud vendor lock-in.

## Review Date

2026-08-29

---

# ADR-016

## Title

Complete modular CapabilityRegistry execution architecture and post-condition verification

## Status

Accepted

## Context

`ExecutionController` previously contained legacy switch-case action dispatch that duplicated functionality, bypassed Koin dependency injection, and complicated testing and scaling of Android capabilities. In addition, execution outcome was often equated with simple API invocation without verifying actual system post-conditions.

## Decision

1. Completely refactor `ExecutionController` into a lightweight delegator that resolves executable actions solely through `CapabilityRegistry.find(step.actionType)`.
2. Implement and register 9 focused capabilities in Koin: `SystemCapability`, `AppCapability`, `CommunicationCapability`, `MediaCapability`, `ClockCapability`, `NavigationCapability`, `AccessibilityCapability`, `ModeCapability`, and `CameraCapability`.
3. Strengthen the `verify` lifecycle method across capabilities to query observable Android system state (`WifiManager.isWifiEnabled`, `BluetoothAdapter.isEnabled`, `AudioManager` volume/ringer, torch state, screenshot file creation) and return `VerificationOutcome.Success`, `Failure`, `PartialSuccess`, or `Unverified`.

## Consequences

- Clean separation of concerns complying with SOLID principles.
- Independent testability of capabilities with pure unit tests.
- High transparency and honesty in execution reporting.

## Review Date

2026-08-29

---

# ADR-017

## Title

Multi-turn conversational follow-ups and explicit user memory lifecycle

## Status

Accepted

## Context

User interactions with voice assistants often involve conversational follow-ups (*"What time is it in New York?"* -> *"And London?"* -> *"What about Tokyo?"*) and explicit requests to remember or forget information (*"Remember that my gym is at 6 PM"*, *"What did I ask you to remember about Rahul?"*, *"Forget that"*).

## Decision

1. Extend `ConversationSession` and `HybridGeneralResolver` to maintain referent state across turns within a 30-second follow-up window, resolving relative inquiries for time, weather, and distance without requiring full re-prompting.
2. Extend `MemoryRepository` with dedicated persistent explicit notes storage, keyword-based recall, and forgetting capabilities (`forgetNotes`, `forgetLast`), strictly isolated from transient conversation history and user preferences.

## Consequences

- Natural, multi-turn conversational interaction.
- User-controlled, persistent explicit memory that respects privacy.

## Review Date

2026-08-29
