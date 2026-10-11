package com.voxyn.voice

import com.voxyn.apps.AppCatalog
import com.voxyn.utils.VoxynLogger

/**
 * Uses the bundled low-memory Vosk recognizer for command capture. The optional
 * Parakeet 0.6B model is hundreds of MB resident and its native initialization
 * was colliding with the first wake-to-command handoff on the target phone.
 */
class SpeechRecognizerEngineProvider(
    private val voskModelProvider: VoskModelProvider,
    private val appCatalog: AppCatalog
) {

    fun currentEngine(): SpeechRecognizerEngine {
        VoxynLogger.i("ASR: bundled Vosk selected for immediate low-memory command capture; Parakeet is not loaded", "ASR")
        return VoskCommandRecognizer(voskModelProvider, appCatalog)
    }
}
