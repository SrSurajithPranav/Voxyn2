# VOXYN Reference Lab: Open-Source Architecture Research & Engineering Blueprint

This research repository contains an in-depth reverse-engineering analysis of five open-source Android AI assistant projects. The goal is to inform the architectural evolution of **VOXYN**, a rootless, voice-first Android AI assistant built in Kotlin with Koin dependency injection and an immutable execution pipeline:

$$\text{INPUT} \rightarrow \text{PERCEPTION} \rightarrow \text{INTENT} \rightarrow \text{CONFIDENCE} \rightarrow \text{RISK} \rightarrow \text{DECISION} \rightarrow \text{PLAN} \rightarrow \text{EXECUTE} \rightarrow \text{VERIFY} \rightarrow \text{LEARN}$$

---

## 1. Reference Projects Evaluated

| Project | Repository / Origin | Technology Stack | Primary Architectural Focus | License |
| :--- | :--- | :--- | :--- | :--- |
| **Open Jarvis** | [`tokenarc/open-jarvis`](https://github.com/tokenarc/open-jarvis.git) | Kotlin, Compose, Room, WorkManager, OkHttp-SSE | Accessibility agent loop, LLM self-healing executor, risky action confirmation, UI graph analysis, MCP client | MIT (2024 TokenArc) |
| **Friday Mobile** | [`n1dlee/friday-mobile`](https://github.com/n1dlee/friday-mobile.git) | Kotlin, Compose, Koin 4.0, Room, Vosk, ONNX Runtime | Text-independent ONNX speaker verification, voice-session state machine, learned commands, lock screen assistant, audio hygiene & call watcher | MIT (2026 n1dlee) + CC-BY-4.0 (wespeaker model) |
| **AI Assistant for Android** | [`souravanand001/ai-assistant-android`](https://github.com/souravanand001/ai-assistant-android.git) | Kotlin, Compose, MediaPipe Text, Sherpa-ONNX, Edge-TTS | Hardware audio effects (AcousticEchoCanceler, NoiseSuppressor), echo bleed window, voice state machine with barge-in | MIT (2025 Sourav Anand) |
| **Sanna** | [`sannabotdev/sannabotapp`](https://github.com/sannabotdev/sannabotapp.git) | React Native + 27 Kotlin Native Modules | Production Accessibility service with gesture fallback, UI tree caching, background notification listener, headless sub-agents | MIT (SannaBot Dev) |
| **AI-Buddy** | [`Weltenwanderer1/ai-buddy`](https://github.com/Weltenwanderer1/ai-buddy.git) | Flutter/Dart, Piper TTS, SQLite, Workmanager | Heuristic contextual memory extraction (tiered 1–10), proactive trigger engine (time, weather, location, POIs), write leases | MIT (2026 Günther Schuch) |

---

## 2. Directory Structure & Deliverables

All deliverables in this research lab are structured as follows:

```
voxyn-reference-lab/
├── README.md                          # Research overview, setup instructions, and executive summary
├── repository-inventory.md            # Deep dive into structure, licenses, dependencies, and build status
├── architecture-comparison.md         # Comprehensive side-by-side analysis of all 15 core subsystems
├── source-code-map.md                 # Verified package, class, interface, and source file locations
├── feature-matrix.md                  # Feature availability, offline capability, and Android requirements
├── security-review.md                 # Permissions, secure keystore storage, data leaks, and attack surface
├── build-results.md                   # Real execution outputs, SDK diagnostics, tests, and build status
├── voxyn-adoption-plan.md             # Ranked, evidence-based recommendations for VOXYN
├── target-architecture.md             # Blueprint for VOXYN's target architecture preserving immutable pipeline
├── pipeline-contracts.md              # Complete Kotlin interfaces and immutable data contracts
├── feature-adoption-matrix.md         # Granular adoption verdicts (Adopt, Adapt, Reject)
├── implementation-roadmap.md          # Phased migration roadmap with milestones and acceptance criteria
├── testing-strategy.md                # Deterministic pipeline testing, edge-case coverage, and audio mocks
└── ui-design-specifications.md        # Screen inventory, component specs, interaction states, and Stitch prompts
```

---

## 3. Environment Setup & Reproduction Instructions

### Prerequisites
- **Operating System:** Windows 10/11, macOS, or Linux
- **JDK:** OpenJDK 17 (e.g., Temurin-17.0.20+8)
- **Android SDK:** API Level 34 / 35 with Build Tools 34.0.0+
- **Environment Variables:**
  - `ANDROID_HOME` or `ANDROID_SDK_ROOT` pointing to your local SDK (e.g., `C:\Users\<username>\AppData\Local\Android\Sdk`)

### Running Verification Builds
To run read-only test verification on the VOXYN baseline:
```powershell
$env:ANDROID_HOME="C:\Users\srsur\AppData\Local\Android\Sdk"
cd c:\Users\srsur\Downloads\Voxyn\Voxyn2
.\gradlew.bat testDebugUnitTest
```

To run verification on Friday Mobile:
```powershell
$env:ANDROID_HOME="C:\Users\srsur\AppData\Local\Android\Sdk"
cd c:\Users\srsur\Downloads\Voxyn\voxyn-reference-lab\friday-mobile
.\gradlew.bat testDebugUnitTest
```

---

## 4. Key Takeaways for VOXYN

1. **Architecture Harmony:** `friday-mobile` is architecturally the closest sibling to VOXYN: it uses **Kotlin + Koin**, Room, and offline Vosk/ONNX. Its ONNX `wespeaker` speaker embedder and microphone lifecycle (settle margins, audio focus recovery, call watching) provide direct, zero-friction architectural upgrades.
2. **Audio Engineering:** `ai-assistant-android` provides the exact formula for preventing self-triggering and audio bleed: binding `AcousticEchoCanceler` and `NoiseSuppressor` to the `AudioRecord` session, coupled with an explicit echo bleed window (>300ms post-TTS).
3. **Execution Reliability:** `open-jarvis` and `sannabotapp` offer the gold standard for robust UI automation: gesture dispatch fallback when Accessibility click fails, UI node index caching, progressive retries for slow rendering, and an LLM self-healing loop for failed tool calls.
4. **Controlled Learning & Memory:** `ai-buddy` demonstrates how to perform tiered (1–10) local heuristic memory extraction without expensive LLM round-trips, while `friday-mobile` demonstrates "learned commands" (caching successful tool parameterizations by spoken phrase).
