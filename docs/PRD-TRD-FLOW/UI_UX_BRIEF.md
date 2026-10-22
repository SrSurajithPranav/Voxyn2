# VOXYN — UI/UX Design Brief

Version: 1.0.19 / Status: Living / Owner: VOXYN Core

## 1. Design philosophy

VOXYN feels like a calm, capable co-pilot — a JARVIS-style companion, not a
settings app. The design is **voice-first with a glanceable mirror**: the user
spends most time talking and listening; the screen exists to confirm state,
show what is happening, and provide a typed fallback.

Principles:
- **Voice is the interface.** Every screen affordance must be reachable by
  voice ("Hey VOXYN, open settings"); taps are backups, not the main path.
- **Quiet confidence.** Dark, monochrome base with a single electric-cyan
  accent. Nothing decorative; everything communicates a state.
- **Glanceable in 2 seconds.** From the notification or overlay the user knows:
  idle / waking / listening / processing / window-open.
- **Never a dead end.** Every state has a spoken or visual next step
  ("Didn't catch that. Try again?").
- **Accessible by default.** High contrast, no color-only signals, and speech
  tolerance for the owner's rhotacism (never require exact pronunciation).

## 2. Brand & visual identity

| Token | Value | Use |
| --- | --- | --- |
| `VoxynCyan` | `#00E5FF` | Primary accent, actions, active states, brand icon |
| `VoxynBg` | `#080C10` | App background (near-black) |
| `VoxynSurface` | `#0F1419` | Cards / panels |
| `VoxynText` | `#E0F7FA` | Primary text |
| Success | `#00E676` | "ready", completed actions |
| Warn | `#FFAB00` | retries, risky actions |
| Error | `#FF1744` | failures |
| Dim | `#546E7A` | secondary text, inactive phase labels |

- Typography: **monospace family throughout** (`FontFamily.Monospace`) with
  letter-spaced uppercase labels — engineering-terminal personality.
- Shape: rounded surfaces (8–12 dp), pill-shaped overlay, minimal elevation
  (borders instead of shadows where possible).
- Motion: subtle — log entries `fadeIn + slideInVertically`, phase bar colors
  slide, mic/overlay pulse while listening. No distracting loops.

## 3. Screens & surfaces

### 3.1 Dashboard (MainActivity / MainScreen)
Top-to-bottom layout:

```
Header          VOXYN — <current phase> or "off"
Phase bar       INPUT PERCEIVE INTENT DECIDE PLAN EXEC VERIFY LEARN
Log panel       events: ▶ intent → ✓ completed / ✗ failed (auto-scroll)
AI panel        LOCAL AI   [model ready | downloading N% | DOWNLOAD]
STT panel       SPEECH     [Parakeet ready | downloading N% | DOWNLOAD]
Composer        [ command field ] [EXECUTE] [🎤]
```

Rules:
- Empty log shows "awaiting input..."; the panel reads as a terminal.
- Model panels use success/dim colors only; buttons only when action is needed.
- The mic button is the most prominent control (outlined cyan icon).

### 3.2 Dynamic Island overlay (OverlayService)
- Floating pill, top-center: **120 dp × 36 dp at y = 8 dp**.
- Mirrors state minimally: idle dot, pulse while listening, brief flash on
  wake ("Yes?" spoken while pill pulses).
- Non-interactive in V1 (informational only); must never cover status bar
  actions; dismissible via notification.

### 3.3 Notifications
- Wake channel: "VOXYN — say Hey VOXYN"; transient "VOXYN — waking up...".
- Voice channel: "speak your command" → "listening..." → partial transcript →
  "Processing..." → "go on, I'm listening" → back to idle.
- Stop action on the voice notification (manual escape hatch).

### 3.4 Dialogs
- Permission requests (mic first; notifications; overlay; contacts/call/sms).
- **Model restore**: "Restore models without re-downloading?" → Allow → All
  files access. Copy: "found model files in Downloads, Android hides them…".
- Confirmation for risky voice actions: spoken,
  "Just to confirm — should I send this message? Say yes."

### 3.5 Future surfaces (V2 backlog)
- Onboarding: 3 steps — permissions → model restore → wake test.
- Settings: wake sensitivity, TTS voice/pitch, model manager, permissions.
- Conversation history list + per-turn context peek.
- Voice waveform / animated listening ring on the overlay.
- Model download screen with resume + storage info.

## 4. Voice personality & microcopy

| Moment | Copy (TTS) |
| --- | --- |
| Wake | "Yes?" / "Listening." / "What do you need?" / "Go ahead." / "I'm here." |
| Processing | "On it." / "Give me a sec." / "Right away." / "Let me handle that." / "Sure thing." |
| Result | "Flashlight on." / "WiFi's on." / "Opening YouTube." / "Alarm set." / "Timer's running." |
| Window end | "Standing by." |
| Unknown | "Didn't catch that. Try again?" / "Not sure what you mean. Say it differently?" / "I missed that. What do you need?" |
| Confirm | "Just to confirm — should I …? Say yes." |

Guidelines: short sentences, present tense, no apologies, no robotic chaining
(never "first… then…"), positive outcomes stated as facts.

## 5. State language (visual)

| State | Header / notification | Phase bar | Overlay |
| --- | --- | --- | --- |
| Idle | VOXYN — say Hey VOXYN | dim | steady dot |
| Waking | VOXYN — waking up... | — | pulsing |
| Listening | speak your command / listening... | INPUT lit | pulsing |
| Processing | Processing... | INTENT→EXEC progress | pause |
| Window open | go on, I'm listening | — | steady |
| Error | spoken retry + log ✗ | phase in red | — |

## 6. Accessibility

- Contrast: cyan on near-black and text on surface exceed WCAG AA.
- Monospace labels ≥ 10 sp; body ≥ 12 sp; touch targets ≥ 48 dp.
- Every voice command has a visible/tap equivalent (typed composer + buttons).
- Rhotacism-tolerant: fuzzy app matching + `SpeechNormalizer` corrections, so
  users are never told they "said it wrong".

## 7. Success metrics (V1)

- Golden hands-free test passes with screen off.
- Any state is identifiable from the notification alone in < 2 s.
- No dead-end state exists (spoken recovery always offered).
- App opens without crashing; models restore without re-download.
