package com.voxyn.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SherpaParakeetRecognizer — command STT via sherpa-onnx + NVIDIA
 * Parakeet-TDT 0.6B v3 INT8 (offline transducer, greedy search).
 *
 * Command capture is segment-based, never "re-run the whole buffer":
 *
 *   AudioRecord (16 kHz mono PCM16)
 *        ↓ 512-sample chunks → float
 *   Silero VAD (sherpa-onnx Vad)
 *        ↓ speech start → listen, speech end → segment
 *   OfflineRecognizer (Parakeet, loaded once & cached by ParakeetModelManager)
 *        ↓ one inference per complete segment
 *   final transcript
 *
 * The engine stops itself after emitting a result or a no-speech event.
 * Nothing is recorded to disk and nothing leaves the device.
 */
class SherpaParakeetRecognizer(
    private val context: Context,
    private val modelManager: ParakeetModelManager
) : SpeechRecognizerEngine {

    companion object {
        private const val SAMPLE_RATE        = 16000
        private const val SILENCE_TIMEOUT_MS = 8_000L   // no speech at all → give up
        private const val MAX_SESSION_MS     = 20_000L  // hard cap per listen
        private const val CHUNK_SAMPLES      = 512      // 32 ms at 16 kHz
    }

    @Volatile
    override var isRunning = false
        private set

    private var listener: SpeechRecognizerEngine.Listener? = null
    private var job: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioRecord: AudioRecord? = null
    private var vad: Vad? = null

    override fun start(listener: SpeechRecognizerEngine.Listener): Boolean {
        if (isRunning) return true
        this.listener = listener
        isRunning = true
        job = scope.launch {
            // The ~650 MB model load is heavy; never block the caller (main thread).
            val recognizer = modelManager.getRecognizer()
            if (recognizer == null) {
                VoxynLogger.e("ASR model unavailable", tag = "ASR")
                this@SherpaParakeetRecognizer.listener?.onError("ASR model unavailable")
                isRunning = false
                return@launch
            }
            captureAndRecognize(recognizer)
        }
        return true
    }

    override fun stop() {
        isRunning = false
        job?.cancel()
        job = null
        releaseAudioRecord()
        try { vad?.release() } catch (_: Exception) {}
        vad = null
        listener = null
    }

    private suspend fun CoroutineScope.captureAndRecognize(recognizer: OfflineRecognizer) {
        val vadEngine = createVad() ?: run {
            listener?.onError("VAD init failed")
            isRunning = false
            return
        }
        vad = vadEngine

        val record = createAudioRecord() ?: run {
            listener?.onError("AudioRecord init failed")
            isRunning = false
            return
        }
        audioRecord = record
        try {
            record.startRecording()

            VoxynLogger.i("ASR: command capture started", "ASR")
            val startTime = SystemClock.elapsedRealtime()
            var speechNotified = false
            val buffer = ShortArray(CHUNK_SAMPLES)

            while (isActive && isRunning) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            val now = SystemClock.elapsedRealtime()

            val samples = FloatArray(read) { buffer[it] / 32768.0f }
            vadEngine.acceptWaveform(samples)

            if (vadEngine.isSpeechDetected() && !speechNotified) {
                speechNotified = true
                VoxynLogger.i("ASR: speech detected", "ASR")
                listener?.onSpeechDetected()
            }

            while (!vadEngine.empty()) {
                val segment = vadEngine.front()
                vadEngine.pop()
                val segSamples = segment.samples
                val audioMs = segSamples.size * 1000L / SAMPLE_RATE
                VoxynLogger.i("ASR: speech segment complete (audio duration = ${audioMs} ms)", "ASR")

                VoxynLogger.i("ASR: inference started", "ASR")
                val t0 = SystemClock.elapsedRealtime()
                val text = runInference(recognizer, segSamples)
                val inferenceMs = SystemClock.elapsedRealtime() - t0
                VoxynLogger.i("ASR: inference completed in ${inferenceMs} ms", "ASR")

                if (text.isNotBlank()) {
                    VoxynLogger.i("ASR: transcript = \"$text\"", "ASR")
                    val shortSamples = ShortArray(segSamples.size) {
                        (segSamples[it] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    listener?.onResult(text, audioMs, inferenceMs, shortSamples)
                    isRunning = false
                    return
                } else {
                    // Noise that VAD treated as speech but the model heard nothing.
                    VoxynLogger.i("ASR: empty transcript — continuing continuous listening", "ASR")
                    speechNotified = false
                }
            }

            if (speechNotified && now - startTime >= MAX_SESSION_MS) {
                VoxynLogger.i("ASR: session cap reached — resetting segment", "ASR")
                speechNotified = false
            }
                delay(10L)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            VoxynLogger.e("ASR capture failed: ${e.message}", tag = "ASR")
            listener?.onError(e.message ?: "Audio capture failed")
        } finally {
            // Natural completion must release the mic too; VoiceInputService may not
            // call stop() until it has started processing the transcript.
            isRunning = false
            releaseAudioRecord()
            try { vad?.release() } catch (_: Exception) {}
            vad = null
        }
    }

    private fun runInference(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            try { stream.release() } catch (_: Exception) {}
        }
    }

    private fun createVad(): Vad? = runCatching {
        val vadConfig = VadModelConfig().apply {
            sampleRate = SAMPLE_RATE
            numThreads = 1
            provider = "cpu"
            sileroVadModelConfig = SileroVadModelConfig().apply {
                model = modelManager.file("silero_vad.onnx").absolutePath
                threshold = 0.5f
                minSilenceDuration = 0.8f
                minSpeechDuration = 0.25f
                maxSpeechDuration = 10f
                windowSize = 512
            }
        }
        Vad(assetManager = null, config = vadConfig)
    }.onFailure {
        VoxynLogger.e("ASR: VAD init failed: ${it.message}", tag = "ASR")
    }.getOrNull()

    private fun createAudioRecord(): AudioRecord? = runCatching {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            try { record.release() } catch (_: Exception) {}
            null
        } else {
            runCatching {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                        enabled = true
                        VoxynLogger.i("AcousticEchoCanceler enabled on mic session", "ASR")
                    }
                }
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.apply {
                        enabled = true
                        VoxynLogger.i("NoiseSuppressor enabled on mic session", "ASR")
                    }
                }
            }
            record
        }
    }.onFailure {
        VoxynLogger.e("ASR: AudioRecord init failed: ${it.message}", tag = "ASR")
    }.getOrNull()

    private fun releaseAudioRecord() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }
}
