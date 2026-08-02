package com.voxyn.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.voxyn.utils.VoxynLogger
import java.util.Locale

class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                isReady = true
                VoxynLogger.i("TTS ready", "TTS")
            } else {
                VoxynLogger.e("TTS init failed: $status", tag = "TTS")
            }
        }
    }

    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        VoxynLogger.i("TTS: \"$text\"", "TTS")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voxyn_${System.currentTimeMillis()}")
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
