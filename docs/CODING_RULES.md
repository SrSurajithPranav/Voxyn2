# VOXYN Engineering Standards & Coding Rules

Version: 1.0

Status: Living Document

Owner: VOXYN Core

Related Documents:
- 00_PROJECT_OVERVIEW.md
- 01_ARCHITECTURE.md
- 03_CURRENT_STATE.md
- 04_DECISIONS.md
- 05_PHASE_ROADMAP.md

---

# Table of Contents

1. Purpose
2. Engineering Philosophy
3. Core Principles
4. Repository Structure
5. Clean Architecture
6. SOLID Principles
7. Kotlin Standards
8. Naming Conventions
9. Package Organization
10. Dependency Injection
11. Coroutines
12. Threading
13. Logging
14. Error Handling
15. Security
16. Performance
17. Testing
18. Git Workflow
19. Documentation
20. AI Development Rules
21. Code Review Checklist
22. Definition of Done
23. Forbidden Practices

---

# 1. Purpose

This document defines the engineering standards for VOXYN.

Every contributor, human or AI, must follow these rules.

The goal is consistency, maintainability, scalability and long-term reliability.

Architecture describes HOW the system works.

This document defines HOW code is written.

---

# 2. Engineering Philosophy

Code is written once.

It is maintained forever.

Therefore prioritize:

Readability over cleverness.

Simplicity over abstraction.

Composition over inheritance.

Interfaces over implementations.

Small modules over monoliths.

Predictability over magic.

---

# 3. Core Principles

Every change should satisfy these principles.

• Single Responsibility

• Open for extension

• Closed for modification

• Dependency inversion

• Immutable data whenever possible

• Fail safely

• Local-first

• Privacy-first

• Offline-first where feasible

• Explainability

If a feature violates these principles, reconsider the design.

---

# 4. Repository Structure

Every feature belongs in its proper package.

Never place unrelated classes together.

Feature folders should remain cohesive.

Avoid dumping miscellaneous classes into util/.

---

# 5. Clean Architecture

Dependencies always point inward.

UI

↓

ViewModel

↓

Engine

↓

Planner

↓

Execution

↓

Capabilities

↓

Android APIs

Never reverse this direction.

---

# 6. SOLID

Every class should have one reason to change.

Prefer interfaces.

Avoid concrete dependencies.

Keep modules replaceable.

Avoid inheritance unless genuinely beneficial.

---

# 7. Kotlin Standards

Use Kotlin idioms.

Prefer:

val over var

data classes

sealed interfaces

sealed classes

extension functions

null safety

Avoid:

!!

lateinit unless unavoidable

global mutable state

reflection

---

# 8. Naming Conventions

Classes

PascalCase

Functions

camelCase

Variables

camelCase

Constants

UPPER_SNAKE_CASE

Packages

lowercase

Interfaces describe behavior.

Avoid meaningless names.

Bad

Manager

Helper

Processor

Utils

Good

IntentResolver

CapabilityRegistry

VoicePipeline

ModeEngine

---

# 9. Package Organization

Packages should represent responsibilities.

Never create packages for convenience.

Avoid deep nesting beyond necessity.

Every package should have a clear owner.

---

# 10. Dependency Injection

VOXYN uses Koin.

No Hilt.

No manual service locators.

No static dependency holders.

Everything injectable should be registered through Koin.

Prefer constructor injection.

Avoid property injection.

---

# 11. Coroutines

Never block the main thread.

Use structured concurrency.

Avoid GlobalScope.

Use lifecycle-aware scopes.

Cancel work when no longer needed.

Every suspend function should be cancellable.

---

# 12. Threading

UI updates occur only on Main.

I/O operations occur on IO dispatcher.

Heavy computation occurs on Default.

Never guess dispatchers.

Use explicit context switching.

---

# 13. Logging

Only VoxynLogger.

Never println().

Never Log.d() directly.

Every log entry should include:

Component

Severity

Meaningful message

Never log secrets.

Never log API keys.

Never log private user data.

---

# 14. Error Handling

Never silently ignore exceptions.

Recover whenever possible.

Expose meaningful errors.

Avoid generic Exception catches.

Prefer sealed result types.

Errors must remain actionable.

---

# 15. Security

Least privilege.

Ask only required permissions.

Never collect unnecessary information.

Never bypass Android security.

Never store secrets in source code.

Sensitive data must be encrypted.

---

# 16. Performance

Target startup under 2 seconds.

Avoid unnecessary allocations.

Avoid reflection.

Avoid repeated object creation.

Cache expensive computations responsibly.

Measure before optimizing.

---

# 17. Testing

Every capability should be testable.

Business logic should not depend on Android UI.

Regression tests for critical features.

Manual testing required before merge.

Prefer deterministic tests.

---

# 18. Git Workflow

One feature per branch.

Meaningful commit messages.

Small pull requests.

Never commit generated files unless required.

Never commit secrets.

---

# 19. Documentation

Every public interface should be documented.

Architecture changes require documentation updates.

Current implementation changes require CURRENT_STATE updates.

Major design decisions require ADR updates.

Roadmap changes require PHASE_ROADMAP updates.

---

# 20. AI Development Rules

AI is an engineering assistant.

Not the architect.

Before changing code:

Read documentation.

Understand architecture.

Inspect dependencies.

Minimize changes.

Preserve existing behavior.

If uncertain:

Explain.

Ask.

Do not guess.

---

# 21. Code Review Checklist

Before approving:

✓ Builds successfully

✓ Existing functionality preserved

✓ No unnecessary abstractions

✓ Naming is clear

✓ Logging follows standards

✓ No duplicated code

✓ No dead code

✓ Tests pass

✓ Documentation updated

✓ Security preserved

---

# 22. Definition of Done

A feature is complete only when:

Code compiles.

Manual testing passes.

Architecture remains intact.

Documentation updated.

Logging added.

Errors handled.

Permissions verified.

Performance acceptable.

No regressions introduced.

---

# 23. Forbidden Practices

The following are prohibited.

❌ println()

❌ Log.d()

❌ GlobalScope

❌ Hardcoded API keys

❌ Massive God classes

❌ Copy-paste duplication

❌ Random utility classes

❌ Reflection without justification

❌ Business logic inside UI

❌ Android APIs inside Planner

❌ Direct ViewModel dependencies in Engine

❌ Breaking immutable pipeline

❌ Skipping documentation

❌ Large unrelated commits

❌ Hidden side effects

❌ Silent exception swallowing

❌ Premature optimization

❌ Overengineering

❌ Feature creep

---

# Final Engineering Rule

Every line of code should make VOXYN:

More reliable.

More understandable.

More maintainable.

More secure.

More scalable.

If it does not, it should not be merged.

---

End of Document