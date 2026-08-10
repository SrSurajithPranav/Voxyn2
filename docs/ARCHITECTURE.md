# VOXYN Architecture

## Voice pipeline

```
Microphone
   │
   ▼
Vosk (wake-word only, always-on in WakeWordService)
   │  "Hey VOXYN" detected → activation phrase → release mic
   ▼
VoiceInputService
   │
   ├─ SpeechRecognizerEngine (abstraction)
   │     ├─ SherpaParakeetRecognizer  ← primary (sherpa-onnx + Parakeet-TDT 0.6B v3 INT8)
   │     └─ VoskCommandRecognizer     ← fallback while the Parakeet model downloads
   │           AudioRecord 16 kHz → Silero VAD (primary) → segment → OfflineRecognizer
   ▼
SpeechNormalizer  (rhotacism/app-name corrections, [unk] cleanup)
   ▼
RequestRouter → VoxynEngine → ExecutionController / AI providers → TTS
```

## Microphone ownership

Exactly one owner at a time:

1. `WakeWordService` runs Vosk always-on. On wake-phrase detection it stops Vosk,
   releases the mic, speaks an activation line and starts `VoiceInputService`.
2. `VoiceInputService` picks the current `SpeechRecognizerEngine` (Parakeet, or
   Vosk fallback), captures one command, runs the engine, then either re-arms for
   follow-up commands inside the 30 s `ConversationSession` window or sends
   `ACTION_RESUME` to `WakeWordService`.
3. `WakeWordService` restarts Vosk detection only after `ACTION_RESUME`.

## STT engine abstraction

`SpeechRecognizerEngine` is the seam between the service and the recognizer:

- `SherpaParakeetRecognizer` — sherpa-onnx `OfflineRecognizer` with
  `model-type=nemo_transducer` (encoder/decoder/joiner) + Silero VAD. Segment-based:
  the VAD accumulates a complete utterance, then exactly one inference runs on that
  segment (never re-running a growing buffer).
- `VoskCommandRecognizer` — the previous Vosk command path, kept as a controlled
  fallback so VOXYN never goes silent while the Parakeet model is downloading.

`VoxynEngine`, `RequestRouter`, `SpeechNormalizer` and the execution system do not
know (and do not care) which STT engine produced the transcript.

Future phase (not implemented): a speaker-verification step between wake detection
and the STT engine can be inserted at the `SpeechRecognizerEngine` boundary without
touching the rest of the pipeline.

## Model lifecycle & memory

| Model | Engine | Size | Lifetime |
|-------|--------|------|----------|
| Vosk model-en-us | wake word (always) | ~40 MB (assets) | process |
| Parakeet-TDT 0.6B v3 INT8 | command STT | ~670 MB (downloaded) | loaded once, cached |
| Silero VAD | speech segmentation | ~0.6 MB (downloaded) | per listen session |
| Gemma 3 1B int4 | local LLM | ~0.55 GB (downloaded) | loaded on demand, cached |

Large models (Parakeet, Gemma) are cached once loaded and never reloaded per
command. They coexist in memory only when both are actually used; the assistant
holds roughly 1.2 GB of model memory on an 8 GB device.
