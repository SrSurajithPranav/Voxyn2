package com.voxyn.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingSpeech: String? = null
    private val readiness = CompletableDeferred<Boolean>()
    private val speechCompletions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                isReady = true
                readiness.complete(true)
                VoxynLogger.i("TTS ready", "TTS")
                pendingSpeech?.let {
                    pendingSpeech = null
                    speakNow(it)
                }
            } else {
                readiness.complete(false)
                VoxynLogger.e("TTS init failed: $status", tag = "TTS")
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                completeSpeech(utteranceId, true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                completeSpeech(utteranceId, false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                completeSpeech(utteranceId, false)
            }
        })
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!isReady) {
            pendingSpeech = text
            VoxynLogger.d("TTS not ready; response queued", "TTS")
            return
        }
        speakNow(text)
    }

    suspend fun speakAndAwait(text: String): Boolean {
        if (text.isBlank()) return true
        if (withTimeoutOrNull(SPEECH_TIMEOUT_MS) { readiness.await() } != true) {
            VoxynLogger.w("TTS was not ready before speech timeout", "TTS")
            return false
        }

        val utteranceId = "voxyn_${System.currentTimeMillis()}"
        val completion = CompletableDeferred<Boolean>()
        speechCompletions[utteranceId] = completion
        speakNow(text, utteranceId)

        return withTimeoutOrNull(SPEECH_TIMEOUT_MS) { completion.await() }
            ?: run {
                speechCompletions.remove(utteranceId)
                VoxynLogger.w("TTS playback timed out", "TTS")
                false
            }
    }

    private fun speakNow(text: String, utteranceId: String = "voxyn_${System.currentTimeMillis()}") {
        VoxynLogger.i("TTS: \"$text\"", "TTS")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun completeSpeech(utteranceId: String?, succeeded: Boolean) {
        utteranceId?.let { speechCompletions.remove(it)?.complete(succeeded) }
    }

    fun stop() {
        if (tts != null) {
            VoxynLogger.i("TTS stop requested", "TTS")
            tts?.stop()
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        pendingSpeech = null
        speechCompletions.values.forEach { it.complete(false) }
        speechCompletions.clear()
    }

    private companion object {
        const val SPEECH_TIMEOUT_MS = 15_000L
    }
}
