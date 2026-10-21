package com.voxyn.voice

import android.content.Context
import com.voxyn.apps.AppCatalog
import com.voxyn.utils.VoxynLogger

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chooses the command STT engine for the current session:
 *  - sherpa-onnx / Parakeet when the model is installed and healthy,
 *  - the bundled Vosk model as an automatic fallback when Parakeet is absent,
 *    fails to load, crashes, or encounters native inference errors.
 */
class SpeechRecognizerEngineProvider(
    private val context: Context,
    private val parakeetModelManager: ParakeetModelManager,
    private val voskModelProvider: VoskModelProvider,
    private val appCatalog: AppCatalog
) {

    private val parakeetFailed = AtomicBoolean(false)
    private val fallbackCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)

    /**
     * Reports a runtime or initialization failure from an engine.
     * When Parakeet fails, flags Parakeet as unhealthy and increments fallback metrics.
     */
    fun reportEngineFailure(engine: SpeechRecognizerEngine, reason: String) {
        if (engine is SherpaParakeetRecognizer) {
            val count = failureCount.incrementAndGet()
            parakeetFailed.set(true)
            fallbackCount.incrementAndGet()
            VoxynLogger.w("ASR: Parakeet failure #$count ($reason) — Vosk fallback engaged", "ASR")
        }
    }

    /**
     * Resets the failure state, allowing Parakeet to be re-attempted.
     */
    fun resetFailureState() {
        parakeetFailed.set(false)
        failureCount.set(0)
    }

    fun isParakeetPreferred(): Boolean =
        parakeetModelManager.isModelPresent() && !parakeetFailed.get()

    fun currentEngine(): SpeechRecognizerEngine =
        if (isParakeetPreferred()) {
            VoxynLogger.i("ASR: Parakeet engine selected", "ASR")
            SherpaParakeetRecognizer(context, parakeetModelManager)
        } else {
            if (parakeetModelManager.isModelPresent() && parakeetFailed.get()) {
                VoxynLogger.w("ASR: Parakeet previously failed — routing to Vosk fallback", "ASR")
            } else {
                VoxynLogger.i("ASR: Parakeet model not installed — using Vosk fallback", "ASR")
            }
            getFallbackEngine()
        }

    fun getFallbackEngine(): SpeechRecognizerEngine {
        return VoskCommandRecognizer(voskModelProvider, appCatalog)
    }
}
