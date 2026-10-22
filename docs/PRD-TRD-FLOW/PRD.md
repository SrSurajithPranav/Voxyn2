# VOXYN — Product Requirements Document (PRD)

Version: 1.0.19 / Status: Living / Owner: VOXYN Core

## 1. Product overview

VOXYN is an always-on Android AI assistant that behaves like a voice-first
companion (a "JARVIS" experience). It listens hands-free for a wake phrase,
holds a natural back-and-forth conversation, executes real Android actions, and
speaks results naturally. It works fully offline for the core loop (offline STT
+ offline LLM) and is privacy-first: audio and conversation stay on-device.

Voice is the primary interface, but a companion app screen provides status,
logs, typed commands, and model management.

## 2. Vision

An always-on Android AI operating system layer that:

- Listens continuously for "Hey VOXYN" in the background.
- Understands intent, plans, and executes Android capabilities.
- Remembers within a conversation and learns responsibly across sessions.
- Works online and offline, proactively and hands-free.
- Remains privacy-first (no audio leaves the device; cloud is optional).

## 3. Target users

- **Primary**: the owner (android.dev) — hands-free daily assistant.
- **Accessibility user**: the owner has rhotacism (R/W pronunciation), so exact
  speech matching must never be required; app-name guesses and fuzzy matching
  are first-class features.
- **Secondary**: any phone user who wants offline voice control that works
  without unlocking/navigating the phone.

## 4. Core value proposition

1. **Always-on**: works from lock screen / other apps — never requires opening
   the app or tapping a mic.
2. **Conversational**: one wake phrase opens a 30-second multi-command window
   with follow-ups ("and open YouTube") and spoken, human-feeling replies.
3. **Offline-first**: Parakeet STT + local LLM (MediaPipe) — the core loop runs
   with no network; no cloud audio.
4. **Actionable**: actually does things (WiFi, flashlight, volume, apps, calls,
   alarms, timers, navigation, music, messaging, screen actions).

## 5. User stories

- As a user, I say "Hey VOXYN" while the phone is locked and VOXYN answers with
  "Yes?", so I never touch the screen.
- As a user with rhotacism, VOXYN understands "open your job" as YouTube
  without corrections, because app-name matching uses my installed-app catalog.
- As a user, I give a follow-up command ("also open YouTube") without repeating
  the wake phrase, within 30 seconds of the last exchange.
- As a user, after 30 seconds of silence VOXYN says "Standing by." and returns
  to wake detection instead of staying "on".
- As a user, reinstalling the app does not force a 1.2 GB re-download; models
  restore from `/sdcard/Download/VoxynAI/`.
- As a user, I see exactly what VOXYN is doing through its notifications and
  the in-app log panel.

## 6. Functional requirements

### FR-1 Wake word (always-on)
- FR-1.1 `WakeWordService` runs in the background with Vosk, listening only for
  the wake grammar: `hey voxyn`, `hi voxyn`, `okay voxyn`, `voxyn`, `[unk]`.
- FR-1.2 Auto-starts on app launch (`VoxynApplication`) and on device boot
  (`BootReceiver`), with `START_STICKY` restart.
- FR-1.3 Foreground notification: **"VOXYN — say Hey VOXYN"**.
- FR-1.4 Wake detection must not require the app to be open or the screen on.

### FR-2 Activation response
- FR-2.1 On wake, VOXYN stops wake listening, releases the mic, and speaks one
  of: "Yes?", "Listening.", "What do you need?", "Go ahead.", "I'm here."
  (randomized).
- FR-2.2 The speech model is pre-warmed while the activation line is spoken so
  the first command word is captured.

### FR-3 Command listening
- FR-3.1 `VoiceInputService` starts listening with Parakeet STT (sherpa-onnx)
  as primary and Vosk command recognition as fallback while Parakeet loads.
- FR-3.2 An 8-second silence timeout ends the listen; the first command gets
  2 attempts before returning to wake.
- FR-3.3 Notification shows "VOXYN — speak your command", then
  "VOXYN — listening...", then partial results as they stream in.

### FR-4 Processing & execution
- FR-4.1 While processing, VOXYN speaks a filler: "On it.", "Give me a sec.",
  "Right away.", "Let me handle that.", "Sure thing." (randomized).
- FR-4.2 Executed actions speak natural results, e.g. "Flashlight on.",
  "WiFi's on.", "Opening [app].".
- FR-4.3 Riskier actions ask for confirmation first
  ("Just to confirm — should I ...? Say yes.").

### FR-5 Conversation window
- FR-5.1 After a command, the session stays awake for 30 seconds; wake detection
  does NOT restart during this window.
- FR-5.2 A follow-up command within the window skips the wake phrase and goes
  straight to listening.
- FR-5.3 Conversation context (last 3 turns) is fed to the intent/AI layer so
  follow-ups resolve in context.
- FR-5.4 If no speech is heard for the window (2 consecutive failed listens),
  VOXYN speaks **"Standing by."** and returns to IDLE wake detection.
- FR-5.5 Notification during the window: **"VOXYN — go on, I'm listening"**.

### FR-6 Speech accuracy (rhotacism support)
- FR-6.1 STT is Parakeet (accurate) rather than Vosk for commands.
- FR-6.2 `SpeechNormalizer` corrects known R/W confusions and app-name
  mishears ("your job" → "youtube", "open insta" → "Instagram").
- FR-6.3 `OPEN_APP` resolves against the installed app catalog
  (`AppCatalog`, 174+ apps) with fuzzy matching, never against a fixed list.

### FR-7 Model management
- FR-7.1 First run: restore ASR + LLM models from
  `/sdcard/Download/VoxynAI/` (raw path) or MediaStore Downloads — no download.
- FR-7.2 If no local backup: download with progress, resumable, validated.
- FR-7.3 "All files access" prompt appears when models exist but are hidden.
- FR-7.4 The app shows model status on the dashboard (AI + SPEECH panels).

### FR-8 System resilience
- FR-8.1 `START_STICKY` on Wake/voice services; boot receiver restarts after
  reboot and after update (`MY_PACKAGE_REPLACED`).
- FR-8.2 No speech / no match paths never leave the user in a dead state:
  unknown commands get spoken retries ("Didn't catch that. Try again?").
- FR-8.3 Debug log written to `/sdcard/Download/voxyn-debug.log` for support.

## 7. Supported actions (V1)

| Category | Actions |
| --- | --- |
| System | WiFi on/off, Bluetooth on/off, flashlight on/off, volume up/down, brightness up/down, screenshot, lock phone |
| Modes | Silent, vibrate, normal, driving, study, sleep, meeting, morning |
| Apps | Open any installed app by name (fuzzy from app catalog) |
| Phone | Call/dial contact |
| Time | Set alarm, set timer, current time/date |
| Media | Play, pause, next, previous track |
| Navigation | Navigate to destination |
| Battery | Battery saver on, battery status |
| Messaging | Send SMS, send WhatsApp (compose), send current message, message group, contact lookup |
| Screen (a11y) | Go home/back, scroll up/down, like current, read notifications, remember this |
| Conversation | Greeting, thanks, smalltalk, capabilities ("what can you do"), unknown fallback |
| Chains | "open X and set timer" → multi-step `CHAIN` |

## 8. Non-functional requirements

- **Latency**: wake → "Yes?" < 2 s; command → filler immediately; action
  response as soon as execution completes.
- **Offline**: core loop (wake, STT, intent, execute, TTS) must work offline.
  LLM answers fall back to knowledge providers / web when offline/not loaded.
- **Privacy**: no audio streamed anywhere; models run on-device.
- **Memory**: ASR recognizer ~650 MB resident, loaded on demand (never during
  cold start); `largeHeap` enabled.
- **Reliability**: works from lock screen, survives reboots, restarts after
  being killed by OEM task killers where the OS allows.
- **Battery**: single mic owner at a time (wake XOR command); Vosk model kept
  small; no polling loops.
- **Logging**: `VoxynLogger` only — never `println`; logs to logcat, optional
  local sink, and debug file.

## 9. Acceptance criteria (must-pass)

1. Hands-free golden test (see `README.md`) passes without touching the screen.
2. Follow-ups within 30 s work without the wake phrase.
3. "Standing by." returns to wake detection after silence.
4. Reinstall uses `/sdcard/Download/VoxynAI/` without re-downloading.
5. App opens without crashing after models restore.
6. All existing commands keep working (no regression).
7. Build compiles with zero errors and zero new warnings.

## 10. Current limitations / out of scope (V1)

- WhatsApp **auto-send** is NOT possible via the public API; VOXYN composes the
  message and (optionally) opens the chat — sending remains a tap (or uses the
  accessibility auto-send path where enabled).
- Speaker verification between wake and command is a future phase.
- Proactive (unsolicited) suggestions are future work.
- OEM background restrictions may still kill services on aggressive brands;
  the app restarts via `START_STICKY`/boot receiver where permitted.
- MediaStore-sourced backups can be invisible on Android 11+; All-files access
  is the reliable restore path.
