# VOXYN — Implementation Plan

Version: 1.0.19 / Status: Living / Owner: VOXYN Core

Plan structure: current status → priority backlog (P0/P1/P2) → roadmap mapping →
definition of done. Every item lists effort, dependencies, and success criteria.

## 1. Current status

- Version 1.0.19 (versionCode 20); build + unit tests green; no new warnings.
- Docs complete: PRD, TRD, App Flow, UI/UX brief, Backend schema (this set).
- Pipeline foundation, wake word, Parakeet STT, LLM, execution, messaging
  (SMS auto-send; WhatsApp compose/accessibility), overlay, modes: implemented.
- Known open items:
  1. GitHub push blocked — token lacks `Contents: write` (403).
  2. Field validation of the 1.0.19 crash fix via `/sdcard/Download/voxyn-debug.log`.
  3. First-command word capture after wake (pre-warm races with TTS).
  4. Fresh-install model restore still depends on All-files access prompt.

## 2. Priority backlog

### P0 — Stabilize (do first)
| # | Task | Effort | Success criteria |
| --- | --- | --- | --- |
| 1 | Push `main` to GitHub once token has `Contents: write` | 5 min | Remote `main` == local `main` (6 commits) |
| 2 | Field-test 1.0.19 on device; read `voxyn-debug.log` for any crash | 30 min | App opens; golden test works; no FATAL lines |
| 3 | Robust first-command capture: pre-warm recognizer on wake before TTS ends; extend WARMUP grace if needed | 1 day | "Hey VOXYN" → "Yes?" → first word of command captured ≥ 95% |
| 4 | Model restore hardening: retry raw-path restore after grant; validate all 5 ASR + 1 LLM files; no silent download loop | 1 day | Reinstall restores offline with 0 network |
| 5 | Crash-proofing: keep heavy loads off cold start; native-load exceptions surfaced with friendly TTS | 1 day | No crash on open / wake / listen paths |
| 6 | WhatsApp send confirmation flow finalization (accessibility auto-send, OS-limited) | 1–2 days | Documented limitation; consistent confirm prompt |

### P1 — Polish (next)
| # | Task | Effort | Success criteria |
| --- | --- | --- | --- |
| 7 | Settings screen: wake sensitivity, TTS voice/pitch/speed, model manager, permissions | 2–3 days | All persistent via DataStore; UI reachable by voice |
| 8 | Onboarding flow: permissions → model restore → wake test | 2 days | First-run success without support |
| 9 | Conversation history UI + per-turn context | 2 days | Recent turns visible; memory survives process death |
| 10 | LLM answer quality: prompt tuning, answer trimming, offline fallbacks | 2 days | 90% of conversational queries answered offline sensibly |
| 11 | Battery: doze handling, mic-only-while-awake, wake model tuning | 2 days | Overnight idle drain measured & documented |
| 12 | Notification channels polish: silent actions, per-state icons | 1 day | Lint clean; states glanceable |

### P2 — Roadmap phases (see §3)
| # | Task | Phase |
| --- | --- | --- |
| 13 | Context Engine expansion (battery, location, headset, driving) | 4 |
| 14 | Memory upgrades: embeddings, semantic recall, ranking, expiration | 3 |
| 15 | Learning: routine detection, preference learning, capability ranking | 5 |
| 16 | Automation: morning/meeting/driving/sleep routines, explainable | 6 |
| 17 | Advanced UX: rich overlay, waveform, visual planning, interactive confirms | 7 |
| 18 | Speaker verification between wake and command | Future |

## 3. Roadmap mapping

Phase status against `PHASE_ROADMAP.md.txt` + `CURRENT_STATE.md.txt`:

| Phase | Goal | Status |
| --- | --- | --- |
| Pre-Alpha | Foundation (pipeline, routing, a11y, voice) | ✅ Complete |
| 1 | Execution engine, capability registry, planner, verification | 🟡 Mostly (registry + execution live; planner/verification partial) |
| 2 | Android intelligence (apps, phone, media, alarms, calls) | ✅ Complete |
| 3 | Memory (conversation/preference/routine, semantic) | 🟡 Partial: trust + notes; embeddings/ranking/expiry future |
| 4 | Context engine | 🟡 Minimal: foreground app only |
| 5 | Learning | ⬜ Future |
| 6 | Automation | ⬜ Future |
| 7 | Advanced UX | 🟡 Overlay exists; rich voice UI future |

## 4. Engineering guardrails

- Kotlin only; `VoxynLogger` only (never `println`); no Hilt/Firebase; Koin DI.
- Full files in patches; package header on every file; no TODOs committed.
- Preserve the microphone-ownership protocol (wake XOR command) and never
  speak TTS while the mic is open.
- Every release: bump `versionCode` +1; build `assembleDebug` + `test` green;
  zero new warnings; APK → `/sdcard/Download/voxyn-debug.apk`; commit; push
  only on explicit request.
- Regressions guarded by: intent-router unit tests, normalizer tests,
  session-window tests, gold acceptance test (see `README.md`).

## 5. Release checklist

1. [ ] Build: `./gradlew --no-daemon assembleDebug test` green
2. [ ] No new Kotlin warnings in changed files
3. [ ] Golden hands-free test passes on device
4. [ ] Reinstall-restore test passes (uninstall → install → All files → ready)
5. [ ] `versionCode` bumped; APK copied to `/sdcard/Download/voxyn-debug.apk`
6. [ ] Docs updated (CURRENT_STATE / this plan) if behavior changed
7. [ ] Committed; pushed when the user asks
