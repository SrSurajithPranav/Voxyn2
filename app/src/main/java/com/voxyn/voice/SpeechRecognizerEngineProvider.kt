package com.voxyn.voice

import android.content.Context
import com.voxyn.apps.AppCatalog
import com.voxyn.utils.VoxynLogger

/**
 * Chooses the command STT engine for the current session:
 *  - sherpa-onnx / Parakeet when the model is installed,
 *  - the bundled Vosk model as a controlled fallback while it downloads
 *    (or if the download fails) so the assistant never goes silent.
 */
class SpeechRecognizerEngineProvider(
    private val context: Context,
    private val parakeetModelManager: ParakeetModelManager,
    private val voskModelProvider: VoskModelProvider,
    private val appCatalog: AppCatalog
) {

    fun currentEngine(): SpeechRecognizerEngine =
        if (parakeetModelManager.isModelPresent()) {
            VoxynLogger.i("ASR: Parakeet engine active", "ASR")
            SherpaParakeetRecognizer(context, parakeetModelManager)
        } else {
            VoxynLogger.w("ASR model unavailable — using Vosk fallback", "ASR")
            VoskCommandRecognizer(voskModelProvider, appCatalog)
        }
}
