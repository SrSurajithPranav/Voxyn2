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
