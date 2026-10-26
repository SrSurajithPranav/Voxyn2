# Feature Matrix: Offline Capabilities, Android API Requirements & Status

This matrix details the feature distribution, offline capabilities, minimum Android requirements, and implementation maturity across VOXYN and the five reference projects.

---

## 1. Feature Availability & Offline Support Matrix

| Subsystem / Feature | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Always-On Wake Word** | **Offline** (Vosk) | None (PTT) | **Offline** (Vosk) | None (PTT / VAD) | **Offline** (Vosk native) | None (PTT) |
| **Speaker Verification** | **Offline** (Acoustic) | None | **Offline** (WeSpeaker ResNet34 ONNX) | None | None | None |
| **Command Speech-to-Text**| **Offline** (Parakeet-TDT) | Cloud (Android STT) | Cloud (Groq Whisper) | **Offline** (Sherpa-ONNX) | Cloud (Android STT) | **Hybrid** (Local/Cloud) |
| **Voice Activity Detection**| **Offline** (Vosk Energy) | Cloud (System VAD) | **Offline** (Vosk endpointing)| **Offline** (Silero VAD) | Cloud (System VAD) | **Offline** (Silero VAD) |
| **Text-to-Speech Output** | **Offline** (Android TTS) | **Offline** (Android TTS)| Cloud (Edge-TTS) | Cloud (Edge-TTS) | **Offline** (Android TTS)| **Offline** (Piper ONNX) |
| **Intent Classification** | **Offline** (Regex/Phonetic)| Cloud (LLM) | **Hybrid** (Rules + Groq) | **Offline** (MediaPipe Text)| Cloud (LLM Agent) | **Hybrid** (Rules + LLM) |
| **Conversational AI** | **Hybrid** (MediaPipe / Web)| Cloud (Multi-LLM) | Cloud (Groq Llama 3.3 70B) | Cloud (Groq Llama) | Cloud (OpenAI/Anthropic) | Cloud (Anthropic/Ollama) |
| **UI Automation (Accessibility)**| **Offline** (Basic clicks) | **Offline** (Accessibility) | None (Intent-only) | None | **Offline** (Accessibility) | None |
| **Self-Healing UI Execution** | **Offline** (Retry/Fallback)| Cloud (LLM healing) | None | None | **Offline** (Retry loops) | None |
| **Hallucination Guardrails**| **Offline** (Verify phase) | None | **Offline** (`SAYS_ITSELF`, Grounding) | None | None | **Offline** (`ActionPolicy`) |
| **Learned Phrase Bypassing** | None | None | **Offline** (`LearnedCommands`) | None | None | **Offline** (Tool learning) |
| **Contextual Memory Extraction**| **Offline** (Regex notes) | None | Cloud (Session summarizer) | None | None | **Offline** (Heuristic tiers) |
| **Lock Screen Operations** | In-app only | System Alert | **Offline** (`showWhenLocked`) | Lockscreen theme | Unlocks screen | None |
| **Proactive Suggestions** | None | None | Cloud/Loc (`MorningBriefWorker`)| None | Headless sub-agents | **Offline** (`ProactiveEngine`)|
| **Encrypted Credential Storage**| **Offline** (Keystore) | **Offline** (CryptoPrefs)| **Offline** (Keystore AES-GCM)| **Offline** (CryptoPrefs)| **Offline** (Keychain) | **Offline** (SecureStorage) |
| **MCP (Model Context Protocol)**| None | Client implemented | None | None | None | None |

---

## 2. Platform Requirements & Footprint

| Metric / Requirement | VOXYN | Open Jarvis | Friday Mobile | AI Assistant Android | Sanna | AI-Buddy |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Minimum Android SDK** | **Android 8.0 (API 26)** | **Android 8.0 (API 26)** | **Android 8.0 (API 26)** | **Android 7.0 (API 24)** | **Android 8.0 (API 26)** | **Android 8.0 (API 26)** |
| **Target Android SDK** | **Android 14 (API 34)** | **Android 14 (API 34)** | **Android 15 (API 35)** | **Android 15 (API 35)** | **Android 14 (API 34)** | **Android 14 (API 34)** |
| **Architecture ABI** | `arm64-v8a` only | All ABIs (Fat APK) | `arm64-v8a` only | All ABIs | All ABIs | All ABIs |
| **Base APK Size** | ~75 MB | ~38 MB | ~35 MB | ~65 MB | ~95 MB (React Native) | ~45 MB (Flutter) |
| **On-Device Model Footprint**| ~650MB (Parakeet) + ~1GB (Gemma) | Variable (GGUF download) | ~20 MB (WeSpeaker) + Vosk | ~650 MB (Sherpa-ONNX) | None | ~50 MB (Piper Voice) |
| **RAM Consumption (Idle)** | ~110 MB | ~85 MB | ~75 MB | ~90 MB | ~180 MB | ~120 MB |
| **RAM Consumption (Active)** | ~450 MB (ASR loaded) | ~180 MB | ~140 MB | ~420 MB | ~280 MB | ~190 MB |
| **Root Required?** | **No (100% Rootless)** | **No (100% Rootless)** | **No (100% Rootless)** | **No (100% Rootless)** | **No (100% Rootless)** | **No (100% Rootless)** |

---

## 3. Implementation Maturity & Status

| Project | Development Stage | Stability Assessment | Key Strengths | Known Gaps / Weaknesses |
| :--- | :--- | :--- | :--- | :--- |
| **VOXYN** | Active Alpha / Core Pipeline Landed | High architectural discipline; deterministic verification | Immutable 10-phase pipeline; zero-dependency fast commands; offline Parakeet ASR | Speaker verification uses synthetic tone simulation rather than deep neural ONNX embeddings; audio focus settle margins needed |
| **Friday Mobile** | Production Personal Daily Driver | Very high; battle-tested on Samsung Android 14/15 devices | Production ONNX speaker verification; rigorous mic lifecycle; learned commands; strict R8 keep rule verification | Heavily tied to Groq cloud for general conversation; UI is hardcoded in Russian |
| **Open Jarvis** | Experimental Alpha | Moderate; depends heavily on LLM latency | Advanced UI self-healing loops; dynamic provider fallback chain; early MCP client | Missing Gradle wrapper; high token usage for basic device actions; no wake word |
| **AI Assistant Android** | Active Beta | Good audio stability; high performance | Hardware AEC/NS/AGC filters; echo bleed suppression; clean voice state machine | Intent routing lacks multi-step chains; cloud TTS adds latency |
| **Sanna** | Active Developer Preview | Good Accessibility resilience | Progressive rendering retries; gesture click fallbacks; headless notification interceptor | React Native runtime overhead; large memory footprint; no on-device ASR |
| **AI-Buddy** | Active Multi-platform App | Very high feature count | Local heuristic memory extractor; proactive location/time engine; Piper neural TTS | Flutter architecture is incompatible with native Android Koin pipeline; cloud-dependent for STT |
