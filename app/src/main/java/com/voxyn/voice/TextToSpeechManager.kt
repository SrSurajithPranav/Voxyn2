package com.voxyn.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * TextToSpeechManager — robust TTS playback with reliable completion callbacks
 * and single-speaker enforcement.
 *
 * Prevents TTS from talking over itself, queueing duplicate stale speech,
 * or interfering with microphone listening.
 */
class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false
    private var pendingSpeech: String? = null
    private var pendingCallback: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isCurrentlySpeaking = AtomicBoolean(false)
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val utteranceSequence = AtomicLong()
    private val activeUtteranceId = AtomicReference<String?>(null)

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                setupProgressListener()
                isReady = true
                VoxynLogger.i("TTS ready", "TTS")
                pendingSpeech?.let { text ->
                    val cb = pendingCallback
                    pendingSpeech = null
                    pendingCallback = null
                    speakNow(text, cb)
                }
            } else {
                VoxynLogger.e("TTS init failed: $status", tag = "TTS")
                completeCallbacks()
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == activeUtteranceId.get()) isCurrentlySpeaking.set(true)
                VoxynLogger.d("TTS started: $utteranceId", "TTS")
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != activeUtteranceId.get()) return
                activeUtteranceId.set(null)
                isCurrentlySpeaking.set(false)
                VoxynLogger.d("TTS completed: $utteranceId", "TTS")
                if (utteranceId != null) {
                    val cb = callbacks.remove(utteranceId)
                    cb?.let { mainHandler.post(it) }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != activeUtteranceId.get()) return
                activeUtteranceId.set(null)
                isCurrentlySpeaking.set(false)
                VoxynLogger.w("TTS error on: $utteranceId", "TTS")
                if (utteranceId != null) {
                    val cb = callbacks.remove(utteranceId)
                    cb?.let { mainHandler.post(it) }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != activeUtteranceId.get()) return
                activeUtteranceId.set(null)
                isCurrentlySpeaking.set(false)
                VoxynLogger.w("TTS error ($errorCode) on: $utteranceId", "TTS")
                if (utteranceId != null) {
                    val cb = callbacks.remove(utteranceId)
                    cb?.let { mainHandler.post(it) }
                }
            }
        })
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }
        if (!isReady) {
            pendingSpeech = text
            pendingCallback = onComplete
            // Treat queued speech as busy so a caller does not open the
            // microphone before TextToSpeech finishes initializing.
            isCurrentlySpeaking.set(true)
            VoxynLogger.d("TTS not ready; response queued", "TTS")
            return
        }
        speakNow(text, onComplete)
    }

    suspend fun speakAndWait(text: String) {
        if (text.isBlank()) return
        val deferred = CompletableDeferred<Unit>()
        speak(text) { deferred.complete(Unit) }
        deferred.await()
    }

    private fun speakNow(text: String, onComplete: (() -> Unit)?) {
        // QUEUE_FLUSH alone is not enough to make stale completion callbacks safe.
        // Complete any replaced request so speakAndWait callers cannot hang.
        tts?.stop()
        activeUtteranceId.set(null)
        isCurrentlySpeaking.set(false)
        completeCallbacks()
        val utteranceId = "voxyn_${utteranceSequence.incrementAndGet()}"
        activeUtteranceId.set(utteranceId)
        // Mark busy before calling into the platform.  onStart can arrive later
        // on another thread, and callers must not race that callback.
        isCurrentlySpeaking.set(true)
        if (onComplete != null) {
            callbacks[utteranceId] = onComplete
        }
        VoxynLogger.i("TTS: \"$text\"", "TTS")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun isSpeaking(): Boolean = isCurrentlySpeaking.get() || (tts?.isSpeaking == true)

    fun stop() {
        completeCallbacks()
        activeUtteranceId.set(null)
        isCurrentlySpeaking.set(false)
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
        pendingSpeech = null
        pendingCallback = null
    }

    private fun completeCallbacks() {
        val pending = callbacks.values.toList()
        callbacks.clear()
        pending.forEach { callback -> mainHandler.post(callback) }
    }
}
