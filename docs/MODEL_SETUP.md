# Model setup — command STT (sherpa-onnx + Parakeet)

## Exact versions

- sherpa-onnx Android AAR: `com.github.k2-fsa:sherpa-onnx:v1.13.4` (JitPack)
- Model: `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` (NVIDIA Parakeet-TDT 0.6B v3, INT8)

## Files (installed to app-private `filesDir/models/asr/`)

| File | URL | Size |
|------|-----|------|
| `encoder.int8.onnx` | https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/encoder.int8.onnx | 652 MB |
| `decoder.int8.onnx` | https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/decoder.int8.onnx | 11.8 MB |
| `joiner.int8.onnx`  | https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/joiner.int8.onnx | 6.3 MB |
| `tokens.txt`        | https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/tokens.txt | 94 KB |
| `silero_vad.onnx`   | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx | 0.6 MB |

Total ≈ 670 MB. Never committed to Git; never bundled in the APK.

## How the model gets onto the phone

1. First time the app listens for a command (or when you tap SPEECH → DOWNLOAD in
   the app), `ParakeetModelManager` downloads any missing files with resume support
   (`Range` requests, `.part` files) and validates each file size.
2. While the download is in progress the assistant keeps working using the Vosk
   fallback recognizer.
3. After the download finishes, the files are also backed up to
   `Downloads/VoxynAI/asr/` (MediaStore, no permission needed). After an
   uninstall/reinstall, `ensureAvailable()` restores them from there — no
   re-download.
4. The `OfflineRecognizer` is created once on first use and cached for the
   process lifetime.

## Sherpa-onnx runtime config (for reference)

```
OfflineRecognizerConfig
  featConfig      : sampleRate 16000, featureDim 80
  modelConfig     : modelType "nemo_transducer"
  transducer      : encoder / decoder / joiner paths
  tokens          : tokens.txt
  numThreads      : 2
  provider        : "cpu"
  decodingMethod  : "greedy_search"

VadModelConfig
  sileroVadModelConfig
    model               : silero_vad.onnx
    threshold           : 0.5
    minSilenceDuration  : 0.8 s  (endpoint)
    minSpeechDuration   : 0.25 s
    maxSpeechDuration   : 10 s
```

---

# Model setup — on-device LLM (MediaPipe GenAI, Gemma 3 1B)

## Exact model

- MediaPipe `.task` bundle: `gemma3-1B-it-int4.task` — Gemma 3 1B, INT4, instruction-tuned
- Primary URL: `https://huggingface.co/JafarKhan1989/gemma-3-1b-mediapipe/resolve/main/gemma3-1B-it-int4.task`
- Mirror: `https://huggingface.co/gummybear2555/Gemma-3n-E2B-it-int4/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task`
- Expected size: **554,661,243 bytes** (≈ 529 MB)

## Why the old download failed

MediaPipe GenAI `.task` files are ZIP archives. A raw TFLite flatbuffer
(1.35 GB, starts `54 46 6c 33`) is NOT a valid task bundle — the engine rejects
it with `Unable to open zip archive`. Any mirror that serves a raw `.bin`/flatbuffer
must not be used.

## Validate a downloaded file (Termux)

```sh
# Head must contain "PK" at offset 4 (00 00 00 00 50 4b 03 04)
head -c 16 voxyn-llm.task | od -A x -t x1z
# Tail must end with the zip end-of-central-directory marker (50 4b 05 06)
tail -c 24 voxyn-llm.task | od -A x -t x1z
```

## Where the files live

| Location | File | Purpose |
|----------|------|---------|
| App-private | `filesDir/models/llm.task` | Loaded by `LocalLlmProvider` at runtime |
| Downloads backup | `Download/VoxynAI/voxyn-llm.task` | Survives uninstall/reinstall |

The app restores the private copy from Downloads at every launch
(`LocalModelManager.ensureModelAvailable()`), so after copying the file to
`Download/VoxynAI/` once, a reinstall needs **no** re-download. If no valid
backup exists, VOXYN downloads the model in the background by itself.

## Manual install (fastest path)

1. Download `gemma3-1B-it-int4.task` from the primary URL above.
2. Copy it to `Download/VoxynAI/voxyn-llm.task` on the phone (replace any old file).
3. Verify the header/tail magic as shown above.
4. Launch VOXYN — the log will show `Local LLM model ready — offline AI active`.
