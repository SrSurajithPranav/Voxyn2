package com.voxyn.core

/**
 * VoxynConstants — single source of truth for all pipeline timing, threshold,
 * and policy constants.
 *
 * Keep constants here instead of scattering magic numbers through service and
 * engine classes. Every field is documented with its rationale.
 */
object VoxynConstants {

    // ─── Voice Lifecycle ──────────────────────────────────────────────────────

    /**
     * Milliseconds to wait after TTS output finishes before restarting the
     * AudioRecord session. Prevents an audio-HAL race condition that can cause
     * a click artifact or a failed AudioRecord.startRecording() call.
     *
     * Inspiration: Friday Mobile FridayWakeWordService.kt MIC_SETTLE_MS = 200L
     */
    const val MIC_SETTLE_MS: Long = 200L

    /**
     * Milliseconds to wait after audio focus is granted before reading PCM
     * samples. Avoids capturing the OS routing "pop" on some devices.
     *
     * Inspiration: Friday Mobile FridayWakeWordService.kt FOCUS_SETTLE_MS = 800L
     */
    const val FOCUS_SETTLE_MS: Long = 800L

    /**
     * Duration after TTS *starts* playing during which any ASR partial or
     * final result is discarded. Prevents the recognizer from treating the
     * assistant's own speech as a new user command (echo bleed).
     *
     * Inspiration: AI Assistant Android VoiceStateMachine.kt — 300ms bleed guard.
     */
    const val ECHO_BLEED_WINDOW_MS: Long = 300L

    /**
     * Delay before re-arming the always-on listening loop after a completed
     * command cycle. Gives the audio stack time to settle between sessions.
     */
    const val REARM_DELAY_MS: Long = 1_000L

    /**
     * Delay applied when the voice engine returns silence / no-speech before
     * the loop re-arms. Shorter than REARM_DELAY_MS because no audio routing
     * state change happened.
     */
    const val SILENCE_REARM_DELAY_MS: Long = 100L

    /**
     * Shortest initial startup delay before the first beginListening() call.
     * Allows the service to fully bind Koin dependencies before audio work begins.
     */
    const val START_DELAY_MS: Long = 200L

    // ─── Pipeline ─────────────────────────────────────────────────────────────

    /**
     * Wall-clock timeout for any single pipeline phase. A phase that exceeds
     * this emits PhaseFailed and the pipeline aborts cleanly.
     */
    const val PIPELINE_PHASE_TIMEOUT_MS: Long = 10_000L

    /**
     * Maximum time the service waits for engine.isProcessing() + tts.isSpeaking()
     * to return false before forcibly re-arming listening.
     */
    const val PIPELINE_MAX_WAIT_MS: Long = 60_000L

    // ─── Confidence & Risk ────────────────────────────────────────────────────

    /**
     * Weighted confidence formula: intent.confidence * INTENT_WEIGHT + trust * TRUST_WEIGHT.
     * Both weights must sum to 1.0.
     */
    const val INTENT_CONFIDENCE_WEIGHT: Float = 0.7f
    const val MEMORY_TRUST_WEIGHT: Float = 0.3f

    /**
     * Minimum weighted confidence score required to proceed without asking the
     * user for clarification.
     */
    const val CONFIDENCE_THRESHOLD: Float = 0.25f

    /**
     * Risk score above which an intent requires explicit user confirmation before
     * execution (maps to DecisionState.AwaitingConfirmation).
     */
    const val HIGH_RISK_THRESHOLD: Float = 0.8f

    /**
     * Risk score assigned to HIGH_RISK actions (SEND_MESSAGE, CALL_CONTACT, etc.).
     */
    const val HIGH_RISK_SCORE: Float = 0.9f

    /**
     * Risk score assigned to all other (safe) actions.
     */
    const val LOW_RISK_SCORE: Float = 0.1f

    // ─── Anti-Hallucination ───────────────────────────────────────────────────

    /**
     * Minimum word-overlap ratio (0–1) between an LLM-generated message body
     * and the user's original spoken input for the message to be considered
     * "grounded". Any message below this ratio is rejected by MessageGrounding.
     *
     * Inspiration: Friday Mobile MessageGrounding.kt / FridayAgent.kt SAYS_ITSELF policy.
     */
    const val GROUNDING_WORD_OVERLAP_THRESHOLD: Float = 0.75f

    // ─── Voice Engine Recovery ────────────────────────────────────────────────

    /**
     * Maximum number of consecutive recognition failures before the service
     * backs off and waits longer before retrying.
     */
    const val MAX_CONSECUTIVE_FAILURES: Int = 2

    /**
     * Maximum attempts for a first-command recognition before falling back.
     */
    const val FIRST_COMMAND_MAX_ATTEMPTS: Int = 2
}
