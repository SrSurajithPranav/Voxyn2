# VOXYN — App Flow

Version: 1.0.19 / Status: Living / Owner: VOXYN Core

This document maps every user-visible path: launch, dashboard, hands-free
conversation, mic button, model restore, and all error flows.

## 1. Launch & onboarding flow

```
Install APK
   │
   ▼
First open (MainActivity)
   │
   ├─ Request permissions: mic, notifications, overlay, contacts/call/sms
   ├─ Prompt "All files access" if models exist in Downloads but are hidden
   │    └─ grant → models restore instantly (no re-download)
   ▼
VoxynApplication starts WakeWordService (foreground)
   │
   ▼
Notification: "VOXYN — say Hey VOXYN"
Dashboard shows: LOCAL AI ready / Parakeet STT ready
```

Key behaviors:
- App open is only needed for permissions + first model restore; after that the
  assistant runs from the background/lock screen.
- `BootReceiver` restarts services after reboot and after app update.
- If mic permission is missing, wake service is NOT started (logged WARN, UI
  prompts).

## 2. Dashboard (MainScreen)

Layout (top → bottom):

```
┌──────────────────────────────┐
│ VOXYN   <phase> / off        │  Header
│ INPUT PERCEIVE INTENT DECIDE │  Phase bar (8 phases)
│ PLAN EXEC VERIFY LEARN       │
│ ┌──────────────────────────┐ │
│ │ awaiting input.../logs   │ │  Log panel (engine events)
│ └──────────────────────────┘ │
│ LOCAL AI     model ready     │  AI panel (+DOWNLOAD button if missing)
│ SPEECH       Parakeet ready  │  STT panel (+DOWNLOAD button if missing)
│ [command field] [EXECUTE]    │
│ [🎤 mic]                     │  Command input row
└──────────────────────────────┘
```

Triggers:
- **EXECUTE**: typed command → `engine.process(text)` (same pipeline as voice).
- **🎤**: `ACTION_START_LISTENING` → manual listening session (no wake phrase
  needed; treated like a fresh conversation).
- **DOWNLOAD** (AI/SPEECH): explicit model download with progress.

Model restore dialog (auto-shown): "Restore models without re-downloading?" →
All-files access → models copy from `/sdcard/Download/VoxynAI/`.

## 3. Hands-free conversation loop (golden flow)

```
User: "Hey VOXYN"
   │
WakeWordService hears wake (Vosk grammar)
   │ stops Vosk, releases mic, speaks activation
   │ (pre-warm Parakeet recognizer during TTS)
VOXYN: "Yes?"
   │
VoiceInputService starts (foreground, mic)
   │ notification: "VOXYN — speak your command" → "listening..."
   │
User: "Turn on flashlight"
   │ transcript → SpeechNormalizer → RequestRouter → VoxynEngine
VOXYN (filler): "On it."
   │ IntentRouter → ExecutionController → action runs
VOXYN: "Flashlight on."
   │ session.addTurn(...) → window 30 s
   │ notification: "VOXYN — go on, I'm listening"
   │
User: "Open YouTube"            (no wake phrase needed)
VOXYN: "Opening YouTube."       (window refreshes)
   │
User: "Set a 10 minute timer"
VOXYN: "Timer's running."       (window refreshes)
   │
… 30 s of silence (2 failed listens) …
VOXYN: "Standing by."
   │ session.end() → WakeWordService ACTION_RESUME
Notification: "VOXYN — say Hey VOXYN"
```

## 4. Service lifecycle

### WakeWordService
- `ACTION_START` → foreground, load Vosk model, start detection.
- Wake → cancel Vosk → activation TTS → start `VoiceInputService`.
- `ACTION_RESUME` from voice service → restart Vosk detection.
- `ACTION_STOP` → graceful shutdown (app close).
- `START_STICKY` → Android restart; boot receiver for reboot/update.

### VoiceInputService
- `ACTION_START_LISTENING` → foreground mic, 200 ms start delay, listen.
- Transcript → engine pipeline (≤ 60 s wait, TTS allowed after mic closed).
- In-window follow-up → immediate re-listen (no wake phrase).
- Silence handling → retry (1 s backoff), max 2 attempts, then return to wake.
- Window expired → "Standing by." → `ACTION_RESUME` → destroy.

## 5. Mic button flow (manual)

```
Tap 🎤 → ACTION_START_LISTENING
   │
VoiceInputService created → ensure ASR model (restore before download)
   │
Listen → command → engine → result TTS → window opens (same as hands-free)
   │
Silence ×2 → "Standing by." → wake resumes
```

Mic button is for when wake word fails/permission issues; it uses the exact same
pipeline, so "Hey VOXYN" is never required.

## 6. Notification state map

| State | Notification text |
| --- | --- |
| IDLE | VOXYN — say Hey VOXYN |
| Waking | VOXYN — waking up... |
| Listening (first) | VOXYN — speak your command |
| Mic open | VOXYN — listening... |
| Partial result | VOXYN — "…partial text…" |
| Processing | Processing... |
| Conversation window | VOXYN — go on, I'm listening |
| Retrying silence | VOXYN — retrying... |
| Back to idle | VOXYN — say Hey VOXYN |
| Model downloading | VOXYN — downloading speech model N% |

## 7. Error flows

| Scenario | What happens |
| --- | --- |
| No speech after "Hey VOXYN" | Retry once → then back to idle (wake resumes) |
| No speech during window ×2 | "Standing by." → idle |
| Unknown command | "Didn't catch that. Try again?" → window stays open for follow-up |
| App name not found | "I couldn't find an installed app named X." → window stays open |
| Mic permission off | Wake service not started; UI prompts permission |
| ASR model missing | Vosk fallback + background download progress in notification |
| LLM missing/offline | Knowledge provider/web fallback; "I can't access current information right now." |
| Confirmation needed (risky) | "Just to confirm — should I …? Say yes." → voice "yes" executes |
| App crashes | Next open: debug log at `/sdcard/Download/voxyn-debug.log` shows last lines |

## 8. Test paths (manual QA)

### T1 Golden hands-free
1. Lock the phone. 2. "Hey VOXYN" → "Yes?". 3. "Turn on flashlight" →
   "Flashlight on." 4. "Open YouTube" → "Opening YouTube."
5. "Set a 10 minute timer" → "Timer's running." 6. Silence 30 s →
   "Standing by." 7. "Hey VOXYN" works again.

### T2 Reinstall resilience
1. Uninstall VOXYN. 2. Install `voxyn-debug.apk`. 3. Open once, allow
   All-files access. 4. Dashboard shows models ready (no download).
5. T1 passes.

### T3 Typed command parity
1. Type "turn on wifi" → execute → same result TTS as voice.

### T4 Follow-up context
1. "Open YouTube", then "also open Instagram" → both open; second without wake.

### T5 Accuracy (rhotacism)
1. "open your job" → YouTube. 2. "open insta" → Instagram.
3. "open timesheet" → resolves to installed app if present.

### T6 Mic button
1. Force-stop app → reopen → tap 🎤 → say command → responds.
2. No permission crash when mic denied.
