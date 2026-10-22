# VOXYN Current State

Version: 1.2

Status: Living Document

Owner: VOXYN Core

Related Documents:
- 00_PROJECT_OVERVIEW.md
- 01_ARCHITECTURE.md
- 02_CODING_RULES.md
- 04_DECISIONS.md
- 05_PHASE_ROADMAP.md

---

# Purpose

This document describes the CURRENT implementation state of VOXYN.

Unlike the Architecture document, this file changes frequently.

Whenever a milestone is completed, updated, or refactored,
this document must be updated.

This file is the primary source of truth for:

- Current implementation
- Existing modules
- Work in progress
- Known issues
- Current priorities
- Technical debt

Every developer and AI assistant must read this document before making changes.

---

# Project Status

Project Name

VOXYN

Current Version

Alpha (Reliability Hardening)

Development Stage

Feature-rich prototype; end-to-end reliability work remains active

Target Device

OnePlus CPH2707 (Android SDK 36)

Language

Kotlin

Minimum Android

Android 8 (API 26)

Target SDK

Android 14 (API 34)

Architecture Status

Pure Modular Capability Architecture + Immutable 10-Stage Pipeline

Pipeline Status

Frozen

---

# Vision Status

Goal:

Build an always-on Android AI Operating System capable of:

- Understanding user intent
- Planning actions
- Executing Android capabilities
- Learning responsibly
- Working online and offline
- Acting proactively
- Remaining privacy-first

Current Progress

Implemented capability breadth is substantial; the reliability milestone is not complete until end-to-end tests pass. Documentation claims of full verification and completion are superseded by the current findings below.
- **Complete CapabilityRegistry Migration**: `ExecutionController` purely delegates all actions through DI-injected `Capability` singletons.
- **9 Modular Capabilities**:
  1. `SystemCapability` (Wi-Fi, Bluetooth, Flashlight, Volume, Modes, Brightness, Airplane, Battery, Lock, Screenshot)
  2. `AppCapability` (App resolution, launcher activities, WebAPKs/PWAs)
  3. `CommunicationCapability` (Phone calls, WhatsApp compose prefilled, SMS auto-send with confirmation)
  4. `MediaCapability` (Play, Pause, Next, Prev tracks)
  5. `ClockCapability` (Alarms, Timers)
  6. `NavigationCapability` (Google Maps & geo navigation)
  7. `AccessibilityCapability` (On-screen messaging, likes, scroll up/down, go back/home, notifications)
  8. `ModeCapability` (Driving, Workout, Study, Sleep, Meeting, Morning)
  9. `CameraCapability` (Photo capture, timed photo capture, video recording)
- **TTS Utterance Tracking & Mic Collision Prevention**: `TextToSpeechManager` implements `UtteranceProgressListener`, `speak(text, onComplete)`, and `speakAndWait(text)` with `isSpeaking()` locking to guarantee clean single-speaker sequencing without microphone deadlocks.
- **Speech Robustness & Rhotacism Normalization**: `SpeechNormalizer` utilizes structured phonetic rules for ChatGPT ("chat gpt", "chat jeep tee", "chat gp tea"), X/Twitter, WhatsApp ("whats app", "what sap", "wasapp"), YouTube ("you do", "your job", "u tube"), Instagram ("insta"), and HungerBox ("hunger books", "hungry box").
- **Confidence-Scored App Resolution**: `AppCatalog` and `AppLaunchResolver` enforce confidence thresholds (`MIN_MATCH_CONFIDENCE = 60`, `MIN_SUGGESTION_CONFIDENCE = 50`) and present disambiguation prompts (*"Did you mean X?"*) instead of blindly opening incorrect applications.
- **Multi-Turn Referents & OSRM Distance Comparisons**: `ConversationSession` and `HybridGeneralResolver` maintain referents across conversational turns and calculate multi-city / comparative distance queries.
- **Persistent Explicit Memory**: `MemoryRepository` supports saving, keyword recall, and forgetting explicit notes isolated from user preferences and action trust scores.
- **Capability-level verifiers** exist; engine integration and individual action coverage are still being hardened.
- **Test/build status**: reliability changes are in progress; do not treat prior completion claims as current validation.

---

# Immutable Components

The following components are considered architecturally frozen.

They should not be redesigned without an ADR.

✓ Pipeline

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

✓ Planner concept

✓ Context model

✓ Memory model

✓ Mode Engine concept

✓ Capability architecture direction

---

# Current Architecture

Current implementation consists of:

Voice Input (continuous foreground listening; Parakeet STT / Vosk command STT fallback; no wake phrase)

↓

Perception (SpeechNormalizer)

↓

Intent Router (IntentRouter + RequestRouter)

↓

Confidence Evaluation

↓

Risk Analysis

↓

Decision Engine

↓

Planner

↓

Execution (ExecutionController + CapabilityRegistry)

↓

Verification (VerificationEngine)

↓

Learning (LearningEngine + MemoryRepository)

Architecture remains aligned with
01_ARCHITECTURE.md.

---

# Existing Modules

Current modules include:

- Voice (`VoiceInputService`, `TextToSpeechManager`, `SpeechNormalizer`, `SherpaParakeetRecognizer`, `VoskCommandRecognizer`)
- Accessibility (`VoxynAccessibilityService`, `AccessibilityCapability`)
- Communication (`ContactResolver`, `MessageComposer`, `CommunicationCapability`)
- Execution (`ExecutionController`, `CapabilityRegistry`, 9 modular capabilities)
- Intent (`IntentRouter`, `RequestRouter`)
- Overlay (`OverlayService`, `DynamicIsland`)
- Modes (`ModeEngine`, `ModeCapability`)
- Memory (`MemoryRepository`, `ConversationSession`)
- Context (`ContextEngine`)
- AI Providers (`HybridGeneralResolver`, `OsmDistanceProvider`, `DuckDuckGoSearchProvider`, `WikipediaSearchProvider`, `OpenMeteoWeatherProvider`, `WorldTimeProvider`, `NewsSearchProvider`, `LocalKnowledgeProvider`)
- Logging (`VoxynLogger`)
- Dependency Injection (`AppModule` via Koin)

---

# Completed Features

✓ Continuous voice-listening foreground service (no wake phrase; Android microphone permission and notification required)
✓ Speech recognition (Parakeet INT8 local + Vosk fallback)
✓ Utterance-tracked Text-to-Speech (TTS)
✓ Speech normalizer with rhotacism & phonetic alias engine
✓ CapabilityRegistry migration with 9 isolated capabilities
◐ Capability-level post-condition verifiers (engine integration is under reliability hardening)
✓ Natural multi-turn conversation session
✓ World time & OSM driving / geocoded distance routing
✓ Contact lookup & phone dialer
✓ WhatsApp composition pre-filled & SMS auto-send
✓ Camera intent hand-off with CAMERA runtime-permission checks
✓ Explicit persistent memory repository
✓ Dynamic Island overlay HUD
◐ Unit tests cover routing and capabilities; the current reliability changes require a fresh test/build run

---

# Known Limitations

Current implementation does not yet support:

1. **WhatsApp auto-send without user interaction**: WhatsApp has no public background sending API without Accessibility UI automation. VOXYN safely opens the WhatsApp conversation with recipient and message pre-filled for 1-tap send (SMS supports full automated dispatch after confirmation).
2. **WhatsApp group background dispatch**: Group messaging is routed and prepared but restricted to WhatsApp compose intent due to public platform constraints.
3. **Initial Offline Model Download**: On a clean installation with no existing local backup in `/Download/VoxynAI/`, VOXYN downloads ~1.2 GB of INT8 models (Parakeet ASR + Gemma LLM) before fully offline neural execution; on subsequent installs it restores automatically from local storage.
4. **Dedicated background camera capture**: Android 14+ restricts while-in-use camera access from a background process. VOXYN uses a visible system-camera hand-off and reports it as unverified; it must not claim that a photo was captured. Scheduled photo/video capture is not reliable without a user-visible foreground camera session.
5. **SDK 36 validation**: The checked-in build is currently `compileSdk`/`targetSdk` 34 because this workspace only has Android platform 34 installed. Android 16 device validation requires platform 36 to be installed before the target can be raised and tested.

---

# Stabilization Update — August 2026

- Command recognizers now release `AudioRecord`, VAD, Vosk recognizers, and streams on normal completion, cancellation, and error paths.
- Voice input explicitly relinquishes a command recognizer before processing a result, then re-arms the continuous listener.
- TTS uses a monotonic utterance ID and ignores stale platform callbacks; replacing or stopping speech completes outstanding callbacks so waiting callers cannot hang.
- The conversation session is bounded to the most recent eight turns; long exchanges cannot retain an unbounded in-memory transcript.
- HUD execution now transitions through `THINKING`, `EXECUTING`, and `SPEAKING` rather than showing execution as speech.
- App matching rejects close fuzzy ties rather than arbitrarily selecting an installed app.
- `VoxynEngine`'s invalid `plan.actions` reference was corrected to its existing `ExecutionPlan` API.

**Verification note:** this historical update does not establish the current branch's build or device status. The current changes need their own test/build and on-device confirmation.

---

# Reliability Hardening — October 2026

- Current-fact questions and explicit web searches are routed to web providers before any language model. If no web source answers, VOXYN reports that it could not verify the current fact.
- Web answers carry a source URL and retrieval time in the pipeline UI log.
- The engine now consumes capability verification results; unverified outcomes no longer increase the action trust score.
- Automatic retries are limited to a single-step action whose capability explicitly declares retry safety and whose failure allows fallback.
- Alarm and timer requests require an explicit time or duration. Ambiguous hours and timer units trigger a follow-up instead of silently defaulting to 7 AM or five minutes.
- The wake-word service has been removed from the app lifecycle. The foreground voice service continuously re-arms command recognition; users can pause listening from the HUD.
- Internet access is used for current information. Cloud LLM reasoning remains optional and is only configured with an explicit API key; local commands and deterministic answers continue to work offline.

**Current verification status:** pending targeted unit tests and debug build after this update. No device-level validation has been run.

---

# Before Every Commit

Verify:

✓ Architecture unchanged
✓ Pipeline preserved
✓ Documentation updated
✓ No regressions
✓ Logging added
✓ Permissions verified
✓ Tests completed (100% passing)

---

End of Document
