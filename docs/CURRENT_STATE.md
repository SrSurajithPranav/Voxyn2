# VOXYN Current State

Version: 1.1

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

Alpha (V1 Core Complete)

Development Stage

Hardened & Core Complete (~86% V1 Milestone)

Target Device

OnePlus CPH2707 (Android SDK 36)

Language

Kotlin

Minimum Android

Android 10 (API 29)

Target SDK

Android 16 (API 36)

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

~86% V1 Completion Milestone Achieved:
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
- **Real Post-Condition Verification**: Checks actual system state post-execution (Wi-Fi, Bluetooth, Torch, Stream Volume, Ringer Mode, Screenshot file creation) and reports `SUCCESS`, `FAILURE`, `PARTIAL`, or `UNVERIFIED`.
- **Automated Test & Build Validation**: 100% unit tests passing across all suites including the 12 core scenario end-to-end verification tests. `assembleDebug` builds cleanly.

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

Voice Input (Vosk Wake Word + Parakeet STT / Vosk STT Fallback)

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

- Voice (`WakeWordService`, `VoiceInputService`, `TextToSpeechManager`, `SpeechNormalizer`, `ParakeetRecognizer`, `VoskRecognizer`)
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

✓ Offline Wake word service (Vosk)
✓ Speech recognition (Parakeet INT8 local + Vosk fallback)
✓ Utterance-tracked Text-to-Speech (TTS)
✓ Speech normalizer with rhotacism & phonetic alias engine
✓ CapabilityRegistry migration with 9 isolated capabilities
✓ Real post-condition verification engine
✓ Natural multi-turn conversation session
✓ World time & OSM driving / geocoded distance routing
✓ Contact lookup & phone dialer
✓ WhatsApp composition pre-filled & SMS auto-send
✓ Hands-free camera capture
✓ Explicit persistent memory repository
✓ Dynamic Island overlay HUD
✓ Clean debug APK build & 100% unit test pass rate

---

# Known Limitations

Current implementation does not yet support:

1. **WhatsApp auto-send without user interaction**: WhatsApp has no public background sending API without Accessibility UI automation. VOXYN safely opens the WhatsApp conversation with recipient and message pre-filled for 1-tap send (SMS supports full automated dispatch after confirmation).
2. **WhatsApp group background dispatch**: Group messaging is routed and prepared but restricted to WhatsApp compose intent due to public platform constraints.
3. **Initial Offline Model Download**: On a clean installation with no existing local backup in `/Download/VoxynAI/`, VOXYN downloads ~1.2 GB of INT8 models (Parakeet ASR + Gemma LLM) before fully offline neural execution; on subsequent installs it restores automatically from local storage.

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
