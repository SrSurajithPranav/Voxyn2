package com.voxyn.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voxyn.apps.AppCatalog
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * VoskCommandRecognizer — fallback command STT using the bundled small Vosk
 * model, kept so VOXYN still understands commands while the Parakeet model is
 * being downloaded (or if it fails). Same behavior as the old in-service Vosk
 * path: grammar-biased dictation, 8 s silence timeout, 1.2 s endpoint silence.
 */
class VoskCommandRecognizer(
    private val modelProvider: VoskModelProvider,
    private val appCatalog: AppCatalog
) : SpeechRecognizerEngine {

    companion object {
        private const val SAMPLE_RATE           = 16000
        private const val SILENCE_TIMEOUT_MS    = 8_000L
        private const val ENDPOINT_SILENCE_MS   = 1_200L

        private val COMMAND_WORDS = listOf(
            "open", "launch", "start", "stop", "turn", "on", "off", "enable", "disable",
            "set", "change", "adjust", "increase", "decrease", "raise", "lower", "volume",
            "up", "down", "louder", "quieter", "mute", "silent", "vibrate", "ringer",
            "normal", "mode", "flashlight", "light", "torch", "wifi", "wi", "fi",
            "bluetooth", "brightness", "screen", "screenshot", "capture", "lock", "phone",
            "call", "message", "messages", "send", "play", "pause", "next", "previous",
            "music", "song", "track", "navigate", "navigation", "direction", "alarm",
            "timer", "minute", "minutes", "second", "seconds", "hour", "hours", "clock",
            "am", "pm", "date", "today", "time", "what", "who", "when", "where", "how",
            "why", "is", "are", "your", "you", "my", "name", "weather", "hello", "hi",
            "hey", "thanks", "thank", "welcome", "help", "please", "okay", "yes", "no",
            "cancel", "go", "back", "home", "scroll", "swipe", "read", "notifications",
            "remember", "save", "and", "then", "the", "for", "me", "to", "it", "that",
            "this", "with", "in", "of", "wanna", "want", "voxyn"
        )

        private val NUMBER_WORDS = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
            "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand",
            "percent", "degrees"
        )

        private val COMMAND_PHRASES = listOf(
            "turn on bluetooth", "turn off bluetooth",
            "turn on wifi", "turn off wifi",
            "turn on flashlight", "turn off flashlight", "turn on torch", "turn off torch",
            "set volume to thirty percent", "set volume to fifty percent", "set volume",
            "increase volume", "decrease volume", "mute volume",
            "what is the weather today", "what is the weather", "weather today",
            "open youtube", "open settings", "open camera", "open whatsapp", "open spotify",
            "take a screenshot", "lock screen", "lock phone",
            "play music", "pause music", "next track", "previous track",
            "set a timer", "set an alarm", "stop timer", "cancel alarm",
            "go back", "go home", "scroll down", "scroll up"
        )
    }

    @Volatile
    override var isRunning = false
        private set

    private var listener: SpeechRecognizerEngine.Listener? = null
    private var job: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioRecord: AudioRecord? = null

    override fun start(listener: SpeechRecognizerEngine.Listener): Boolean {
        if (isRunning) return true
        val model = modelProvider.getModel()
        if (model == null) {
            VoxynLogger.e("ASR: Vosk fallback model unavailable", tag = "ASR")
            return false
        }
        this.listener = listener
        isRunning = true
        job = scope.launch { captureAndRecognize(model) }
        return true
    }

    override fun stop() {
        isRunning = false
        job?.cancel()
        job = null
        releaseAudioRecord()
        listener = null
    }

    /** Audio session for optional effects managed by the owning voice service. */
    fun getAudioSessionId(): Int = audioRecord?.audioSessionId ?: -1

    private suspend fun CoroutineScope.captureAndRecognize(model: org.vosk.Model) {
        val record = createAudioRecord() ?: run {
            listener?.onError("AudioRecord init failed")
            isRunning = false
            return
        }
        audioRecord = record

        var recognizer: Recognizer? = null
        try {
            val activeRecognizer = Recognizer(model, SAMPLE_RATE.toFloat(), buildCommandGrammar())
            recognizer = activeRecognizer
            record.startRecording()

        VoxynLogger.i("ASR: Vosk fallback capture started (8s silence timeout)", "ASR")
        val startTime = System.currentTimeMillis()
        var lastSpeechTime = startTime
        var hadSpeech = false
        var finalText: String? = null
        var lastPartial = ""
        val buffer = ShortArray(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
        )
        val capturedAudio = ArrayList<Short>(48000)

        while (isActive && isRunning) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            val now = System.currentTimeMillis()

            var energy = 0.0
            for (i in 0 until read) {
                val sample = buffer[i] / 32768.0
                energy += sample * sample
            }
            val rms = kotlin.math.sqrt(energy / read)
            listener?.onAudioLevel(if (rms > 0.0) (20.0 * kotlin.math.log10(rms)).toFloat() else -90f)

            if (capturedAudio.size < 160_000) {
                for (i in 0 until read) {
                    capturedAudio.add(buffer[i])
                }
            }

            if (activeRecognizer.acceptWaveForm(buffer, read)) {
                val text = JSONObject(activeRecognizer.result).optString("text", "").trim()
                if (text.isNotEmpty()) {
                    finalText = text
                    VoxynLogger.i("ASR: Vosk final: \"$text\"", "ASR")
                    break
                }
            } else {
                val partial = JSONObject(activeRecognizer.getPartialResult()).optString("partial", "").trim()
                if (partial.isNotEmpty()) {
                    // Crucial: Only reset the silence timer when NEW partial speech arrives.
                    // Resetting on every tick with an unchanged partial causes the loop to hang.
                    if (partial != lastPartial) {
                        lastPartial = partial
                        lastSpeechTime = now
                        hadSpeech = true
                        listener?.onPartial(partial)
                    }
                }
            }

            if (hadSpeech && now - lastSpeechTime >= ENDPOINT_SILENCE_MS) {
                val epText = runCatching {
                    JSONObject(activeRecognizer.result).optString("text", "").trim()
                }.getOrDefault("")
                if (epText.isNotEmpty()) {
                    finalText = epText
                }
                break
            }
            if (!hadSpeech && now - startTime >= SILENCE_TIMEOUT_MS) {
                break
            }
            delay(20L)
        }

            // Flush final decoder result if finalText was not yet obtained
            if (finalText.isNullOrEmpty()) {
                val flushed = runCatching {
                    JSONObject(activeRecognizer.finalResult).optString("text", "").trim()
                }.getOrDefault("")
                if (flushed.isNotEmpty()) {
                    finalText = flushed
                }
            }

            val best = finalText ?: lastPartial.takeIf { it.isNotEmpty() }
            val meaningful = best?.replace("[unk]", "")?.trim()
            if (!meaningful.isNullOrBlank()) {
                val duration = System.currentTimeMillis() - startTime
                val samples = ShortArray(capturedAudio.size) { capturedAudio[it] }
                VoxynLogger.i("ASR: transcript = \"$meaningful\"", "ASR")
                listener?.onResult(meaningful, duration, 0L, samples)
            } else {
                listener?.onNoSpeech()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            VoxynLogger.e("ASR: Vosk capture failed: ${e.message}", tag = "ASR")
            listener?.onError(e.message ?: "Vosk capture failed")
        } finally {
            isRunning = false
            try { recognizer?.close() } catch (_: Exception) {}
            releaseAudioRecord()
        }
    }

    /** Grammar-biased word and phrase list so the small Vosk model hears VOXYN vocabulary. */
    private fun buildCommandGrammar(): String = runCatching {
        val words = linkedSetOf<String>()
        words.add("[unk]")
        words.addAll(COMMAND_PHRASES)
        words.addAll(COMMAND_WORDS)
        words.addAll(NUMBER_WORDS)
        words.addAll(appCatalog.grammarTerms())
        val arr = JSONArray()
        words.forEach { arr.put(it) }
        arr.toString()
    }.getOrElse {
        VoxynLogger.e("Vosk command grammar build failed: ${it.message}", tag = "ASR")
        """["[unk]", "open", "turn", "on", "off", "set", "voxyn"]"""
    }

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
