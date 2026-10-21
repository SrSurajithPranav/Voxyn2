package com.voxyn.voice

import com.voxyn.security.CommandVerificationGate
import com.voxyn.security.GateResult
import com.voxyn.security.InputSource
import com.voxyn.utils.VoxynLogger
import java.util.concurrent.atomic.AtomicReference

enum class SessionState {
    IDLE,
    WAKE_DETECTED,
    LISTENING,
    VERIFYING,
    PROCESSING,
    EXECUTING,
    SPEAKING,
    STANDBY,
    ERROR
}

/**
 * VoiceSessionCoordinator — Orchestrates monotonic voice session transitions,
 * audio routing between ASR and Speaker Verification, stale callback prevention,
 * and diagnostic event tracking.
 */
class VoiceSessionCoordinator(
    private val gate: CommandVerificationGate,
    private val diagnostics: VoiceDiagnosticsManager
) {

    private val currentState = AtomicReference(SessionState.IDLE)
    @Volatile private var activeSessionId: String = ""

    fun getState(): SessionState = currentState.get()

    fun getActiveSessionId(): String = activeSessionId

    fun onWakeDetected(): String {
        val sessionId = diagnostics.startSession("WAKE")
        activeSessionId = sessionId
        currentState.set(SessionState.WAKE_DETECTED)
        VoxynLogger.i("Session [$sessionId]: State -> WAKE_DETECTED", "COORDINATOR")
        return sessionId
    }

    fun onListeningStarted(sessionId: String, engineName: String): Boolean {
        if (sessionId != activeSessionId && activeSessionId.isNotEmpty()) {
            VoxynLogger.w("Session mismatch ($sessionId vs $activeSessionId) — ignoring", "COORDINATOR")
            return false
        }
        currentState.set(SessionState.LISTENING)
        diagnostics.recordEvent("Listening started with $engineName")
        VoxynLogger.i("Session [$sessionId]: State -> LISTENING", "COORDINATOR")
        return true
    }

    fun onSpeechResult(
        sessionId: String,
        transcript: String,
        audioDurationMs: Long,
        audioSamples: ShortArray?
    ): GateResult {
        if (sessionId != activeSessionId && activeSessionId.isNotEmpty()) {
            VoxynLogger.w("Session mismatch on speech result — ignoring", "COORDINATOR")
            return GateResult.Denied("STALE_SESSION", "Session expired")
        }

        currentState.set(SessionState.VERIFYING)
        diagnostics.recordAudioQuality(audioDurationMs, 0f, false)
        diagnostics.recordTranscript(final = transcript)
        VoxynLogger.i("Session [$sessionId]: State -> VERIFYING", "COORDINATOR")

        val gateOutcome = gate.verifyAndAuthorize(InputSource.VOICE, audioSamples, transcript)
        when (gateOutcome) {
            is GateResult.Authorized -> {
                currentState.set(SessionState.PROCESSING)
                diagnostics.recordSpeakerVerification("VERIFIED (${gateOutcome.speakerName})", true)
                VoxynLogger.i("Session [$sessionId]: State -> PROCESSING", "COORDINATOR")
            }
            is GateResult.Denied -> {
                currentState.set(SessionState.ERROR)
                diagnostics.recordSpeakerVerification("DENIED (${gateOutcome.reason})", false)
                diagnostics.recordFailure(gateOutcome.reason, false)
                VoxynLogger.w("Session [$sessionId]: State -> ERROR (${gateOutcome.reason})", "COORDINATOR")
            }
        }
        return gateOutcome
    }

    fun onCommandExecuting(sessionId: String? = null) {
        currentState.set(SessionState.EXECUTING)
        diagnostics.recordEvent("Command execution started for session: ${sessionId ?: activeSessionId}")
    }

    fun onSessionEnded() {
        currentState.set(SessionState.IDLE)
        diagnostics.recordEvent("Session $activeSessionId ended")
        VoxynLogger.i("Session [$activeSessionId]: State -> IDLE", "COORDINATOR")
        activeSessionId = ""
    }
}
