# VOXYN Development Roadmap

Version: 1.0

Status: Living Document

Owner: VOXYN Core

Related Documents:
- 00_PROJECT_OVERVIEW.md
- 01_ARCHITECTURE.md
- 02_CODING_RULES.md
- 03_CURRENT_STATE.md
- 04_DECISIONS.md

---

# Purpose

This document defines the long-term engineering roadmap for VOXYN.

Unlike the Architecture document, this roadmap is expected to evolve as the project matures.

Its purpose is to provide:

• Development priorities

• Milestones

• Engineering goals

• Dependencies

• Testing strategy

• Success criteria

Every new feature should belong to a roadmap phase.

---

# Development Philosophy

VOXYN is not being built feature-first.

It is being built foundation-first.

Every phase must strengthen the platform before introducing new capabilities.

The order is intentional.

Architecture

↓

Execution

↓

Memory

↓

Context

↓

Learning

↓

Automation

↓

Ecosystem

---

# Development Stages

Pre-Alpha

↓

Alpha

↓

Beta

↓

Release Candidate

↓

Version 1.0

↓

Version 2.0

↓

Long-term Evolution

---

# PRE-ALPHA

Status

Current

Goal

Build the engineering foundation.

Priority

Architecture over features.

Deliverables

✓ Architecture Handbook

✓ Engineering Documentation

✓ Immutable Pipeline

✓ Intent Routing

✓ Execution Layer

✓ Accessibility Integration

✓ Voice Input

✓ Overlay

✓ Wake Word

✓ Mode Engine Foundation

✓ Logging

✓ Provider Abstraction

Success Criteria

Core architecture becomes stable.

Future work should extend—not redesign—the foundation.

---

# PHASE 1

Foundation & Stability

Status

Complete (Core Stability Achieved)

Goal

Establish a stable AI operating system core and reliable startup lifecycle.

Objectives

✓ Complete Execution Engine
✓ Pure CapabilityRegistry Architecture & DI
✓ Speech Normalization (Phonetic & Rhotacism)
✓ Hybrid Query Routing (Offline Time, OSM Distance, Web News)
✓ Real Verification Framework with Post-Condition Checks
✓ Permission Lifecycle & Android 14/15 FGS Safeguards
✓ Dependency Injection with Koin

Deliverables

✓ Capability Interface & CapabilityRegistry
✓ 9 Modular Capability Classes (System, App, Comm, Media, Clock, Nav, A11y, Mode, Camera)
✓ Speech Normalizer with Rhotacism Compensation
✓ Hybrid General Resolver with Multi-Turn Referent Binding
✓ Dynamic Island Overlay Stability

Testing

✓ Regression Tests (100% test suite passing)
✓ Capability Tests
✓ Planner Tests

Risks Mitigated

✓ Android 14+ FGS crashes eliminated
✓ JVM compilation OOM avoided with SerialGC & TieredStopAtLevel=1
✓ Overlay BadTokenException handled gracefully

Dependencies

Architecture complete.

---

# PHASE 2

Android Intelligence & Capability Expansion

Status

Complete (85% V1 Milestone)

Goal

Turn VOXYN into a fully capable Android AI operating system assistant.

Features

✓ Open Applications with fuzzy package matching, PWAs, and suggestions
✓ Phone Controls & Torch
✓ Flashlight
✓ Volume (raise, lower)
✓ Bluetooth
✓ Wi-Fi
✓ Brightness
✓ Media Controls (Play, Pause, Skip, Prev)
✓ Screenshot
✓ Notifications (Read & Clear)
✓ Navigation & Multi-stop Distance Routing
✓ Alarm & Timer
✓ Calls & Contact Disambiguation
✓ Camera (Photo, Timer Photo, Video)
✓ WhatsApp Compose & SMS Auto-Send with Confirmation
✓ Accessibility Navigation & Screen Scraping

Expected Result

Hands-free Android control with honest, verified feedback.

---

# PHASE 3

Memory

Status

Active (Phase 3 Core Deliverables Implemented)

Goal

Persistent intelligent memory.

Features

✓ Explicit User Memory Notes ("Remember that ...")
✓ Keyword & Semantic Topic Recall ("What did I ask you to remember about ...")
✓ Forgetting Capabilities ("Forget that", "Forget about ...")
✓ User Preference Memory
✓ Action Trust Scores & Success Recording
- Routine Memory
- Memory Reflection

Deliverables

✓ Memory Repository with DataStore Persistence
✓ Memory Retrieval & Search
✓ Memory Forget Operations

Testing

✓ Memory consistency
✓ Persistence
✓ Retrieval accuracy

---

# PHASE 4

Context Engine

Status

Active (Device Context Infrastructure Implemented)

Goal

Understand the user's current situation.

Context Sources

✓ Battery Level & Charging Status
✓ Wi-Fi Connectivity State
✓ Audio / Music Playback State
✓ Bluetooth & Wired Headset Connection
✓ Foreground App Tracking
✓ Screen State
- Location
- Calendar & Activity

Context informs planning and intent resolution without invasive telemetry.

---

# PHASE 5

Learning Engine

Goal

Improve behavior responsibly.

Features

Routine Detection

Preference Learning

Execution Success Tracking

Capability Ranking

Planner Feedback

Learning must remain transparent.

Users should always remain in control.

---

# PHASE 6

Automation

Goal

Proactive assistance.

Examples

Morning routine

Driving automation

Meeting mode

Workout mode

Sleep mode

Battery optimization

Notification prioritization

Automation must always be explainable.

---

# PHASE 7

Advanced User Experience

Goal

Create a premium AI experience.

Features

Dynamic Island

Rich Overlay

Animated Voice UI

Natural Conversations

Visual Planning

Execution Progress

Interactive Confirmations

Focus

Delight without sacrificing performance.

---

# PHASE 8

Plugin SDK

Goal

Allow VOXYN to grow without modifying the core.

Deliverables

Capability SDK

Plugin Loader

Plugin Validation

Permission Framework

Developer Documentation

Marketplace foundation

Success

New capabilities become installable modules.

---

# PHASE 9

Multi-Model Intelligence

Goal

Use the best AI for each task.

Features

Provider Routing

Fallback

Offline Models

Task-based Selection

Latency Optimization

Cost Optimization

Planner becomes provider-independent.

---

# PHASE 10

Cross-Device Ecosystem

Goal

One assistant.

Multiple devices.

Targets

Phone

Tablet

Desktop

Laptop

Wear OS

Android TV

Smart Home

Vehicle

VOXYN coordinates them.

---

# PHASE 11

Developer Platform

Goal

Open ecosystem.

Deliverables

SDK

Documentation

Plugin API

Developer Tools

Sample Projects

Community Plugins

---

# PHASE 12

VOXYN OS

Long-Term Vision

Transition from

Android Assistant

↓

AI Operating System

Capabilities

Continuous reasoning

Long-term planning

Multi-device orchestration

Distributed execution

Unified memory

Adaptive intelligence

---

# Milestone Workflow

Every milestone follows:

Design

↓

Architecture Review

↓

Implementation

↓

Compilation

↓

Testing

↓

Documentation

↓

Review

↓

Merge

Never skip stages.

---

# Testing Requirements

Every phase requires:

Functional Tests

Regression Tests

Performance Validation

Permission Validation

Offline Validation

Security Review

Documentation Update

A phase is not complete until all pass.

---

# Success Metrics

VOXYN succeeds when:

A new capability requires only a new capability implementation.

A new AI provider requires only a provider adapter.

A new platform requires only a platform adapter.

The pipeline never changes.

Architecture remains understandable.

Performance remains predictable.

Documentation remains current.

---

# Current Focus

## Reliability hardening — October 2026

1. Route current facts and explicit web searches through web providers before any LLM; show source and retrieval time.
2. Connect capability verifiers to the engine and avoid claiming success for unverified handoffs.
3. Retry only explicitly idempotent single-step actions.
4. Clarify missing or ambiguous alarm and timer values instead of applying defaults.
5. Keep continuous foreground voice listening as the active no-wake-phrase design and remove stale wake-word lifecycle paths.
6. Re-run targeted unit tests and a debug build; verify microphone lifecycle and representative actions on the target device before marking the milestone complete.

Recently completed:

✓ Communication & Messaging (contacts, SMS auto-send, WhatsApp compose, confirmation flow) — see CURRENT_STATE.md and DECISIONS.md (ADR-011/012)

Remaining communication work:

- WhatsApp group messaging (needs a reliable public mechanism; currently an explicit unsupported seam)

The current engineering priority is:

1. Capability Architecture

2. Execution Refactor

3. Verification

4. Memory

5. Context

6. Learning

7. Automation

8. Plugin SDK

9. Multi-Device

---

# Release Philosophy

Ship foundations.

Then capabilities.

Then intelligence.

Then ecosystem.

Never reverse the order.

---

# Updating This Roadmap

Update whenever:

A phase completes.

Priorities change.

New long-term goals emerge.

Architecture evolves.

Do not rewrite history.

Record progress.

---

# Final Vision

VOXYN should eventually become more than an assistant.

It should become a trusted digital executive that understands goals instead of commands.

It should coordinate devices instead of merely controlling them.

It should learn responsibly instead of memorizing everything.

It should explain its reasoning instead of acting mysteriously.

It should work whether connected to the cloud or completely offline.

Every roadmap phase exists to move VOXYN one step closer to that vision.

---

End of Document
