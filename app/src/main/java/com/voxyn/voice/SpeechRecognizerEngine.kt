package com.voxyn.voice

/**
 * Abstraction over the command speech-to-text engine.
 *
 * VoiceInputService only talks to this interface, so the underlying recognizer
 * (sherpa-onnx/Parakeet, the Vosk fallback, or anything else) can be swapped
 * without touching the service, the engine or the router. This is also the
 * seam where the future speaker-verification phase will sit:
 *
 *   wake candidate → SpeakerVerifier → SpeechRecognizerEngine → SpeechNormalizer
 */
interface SpeechRecognizerEngine {

    /** True while the engine owns the microphone and is capturing. */
    val isRunning: Boolean

    /**
     * Starts capturing the microphone and streaming results to [listener].
     * Returns false (and does not touch the mic) if the engine cannot start.
     * The engine stops itself after emitting a result or a no-speech event.
     */
    fun start(listener: Listener): Boolean

    /** Stops capture immediately. Safe to call when not running. */
    fun stop()

    interface Listener {
        /** First speech frame was detected (only engines with VAD report this). */
        fun onSpeechDetected()

        /** Partial/streaming transcript, when the engine can provide one. */
        fun onPartial(text: String)

        /** Current PCM level in dBFS from the active microphone capture. */
        fun onAudioLevel(rmsDb: Float) = Unit

        /** A complete spoken command, with optional audio samples for speaker verification. */
        fun onResult(text: String, audioDurationMs: Long, inferenceMs: Long, audioSamples: ShortArray? = null)

        /** The engine captured audio but no usable speech was found. */
        fun onNoSpeech()

        /** The engine failed and must be treated as an empty capture. */
        fun onError(message: String)
    }
}
