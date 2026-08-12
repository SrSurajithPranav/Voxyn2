# VOXYN — PRD, TRD & App Flow

Version: 1.0.19 (versionCode 20)

Status: Living documents — update whenever the product or architecture changes.

This folder contains the product, technical, and flow documentation for VOXYN:

| File | What it is |
| --- | --- |
| `PRD.md` | Product Requirements Document — vision, users, features, requirements, acceptance criteria |
| `TRD.md` | Technical Requirements Document — architecture, modules, protocols, data flow, testing |
| `APP_FLOW.md` | App flow — screens, states, conversation loop, service lifecycle, error flows, test paths |

Related docs in `docs/`:

- `ARCHITECTURE.md` — voice pipeline and microphone ownership
- `MODEL_SETUP.md` — Vosk / Parakeet / LLM model setup
- `CODING_RULES.md.txt` — coding conventions
- `DECISIONS.md.txt` — architectural decisions
- `PHASE_ROADMAP.md.txt` — long-term roadmap
- `CURRENT_STATE.md.txt` — implementation status

## Golden acceptance test

Without touching the screen:

1. Say **"Hey VOXYN"** → hear **"Yes?"**
2. Say **"Turn on flashlight"** → hear **"Flashlight on."**
3. Say **"Open YouTube"** (no wake phrase) → hear **"Opening YouTube."**
4. Say **"Set a 10 minute timer"** (no wake phrase) → hear **"Timer's running."**
5. Stay silent 30 s → hear **"Standing by."** and wake detection resumes.
