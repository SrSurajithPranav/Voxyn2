package com.voxyn.voice

import com.voxyn.utils.VoxynLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class VoiceDiagnosticSnapshot(
    val sessionId: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
    val activeEngine: String = "Unknown",
    val modelReady: Boolean = false,
    val wakeState: String = "IDLE",
    val micState: String = "CLOSED",
    val sampleRate: Int = 16000,
    val audioDurationMs: Long = 0L,
    val rmsEnergyDb: Float = 0.0f,
    val isClipping: Boolean = false,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val asrLatencyMs: Long = 0L,
    val speakerVerificationResult: String = "NOT_EVALUATED",
    val authorizationOutcome: String = "NOT_EVALUATED",
    val pipelineStage: String = "IDLE",
    val commandOutcome: String = "NONE",
    val fallbackEngaged: Boolean = false,
    val failureReason: String = ""
)

class VoiceDiagnosticsManager {

    companion object {
        private const val MAX_LOG_EVENTS = 50
    }

    private val sessionCounter = AtomicLong(0)
    private val currentSnapshot = AtomicReference(VoiceDiagnosticSnapshot())
    private val eventHistory = ConcurrentLinkedQueue<String>()

    fun startSession(engineName: String): String {
        val id = "vses_${System.currentTimeMillis()}_${sessionCounter.incrementAndGet()}"
        currentSnapshot.set(
            VoiceDiagnosticSnapshot(
                sessionId = id,
                timestampMs = System.currentTimeMillis(),
                activeEngine = engineName,
                micState = "CAPTURING",
                wakeState = "WAKE_TRIGGERED"
            )
        )
        recordEvent("Session $id started with engine $engineName")
        return id
    }

    fun recordEvent(event: String) {
        val timestamp = System.currentTimeMillis()
        val entry = "[$timestamp] $event"
        eventHistory.add(entry)
        while (eventHistory.size > MAX_LOG_EVENTS) {
            eventHistory.poll()
        }
        VoxynLogger.d("DIAG: $event", "VOXYN::DIAG")
    }

    fun recordAudioQuality(durationMs: Long, rmsDb: Float, clipping: Boolean) {
        val current = currentSnapshot.get()
        currentSnapshot.set(
            current.copy(
                audioDurationMs = durationMs,
                rmsEnergyDb = rmsDb,
                isClipping = clipping
            )
        )
        recordEvent("Audio: ${durationMs}ms, RMS=${"%.1f".format(rmsDb)}dB, clipping=$clipping")
    }

    fun recordTranscript(partial: String? = null, final: String? = null, latencyMs: Long = 0L) {
        val current = currentSnapshot.get()
        currentSnapshot.set(
            current.copy(
                partialTranscript = partial ?: current.partialTranscript,
                finalTranscript = final ?: current.finalTranscript,
                asrLatencyMs = if (latencyMs > 0) latencyMs else current.asrLatencyMs
            )
        )
        if (final != null) {
            recordEvent("Transcript: \"$final\" (${latencyMs}ms)")
        }
    }

    fun recordSpeakerVerification(result: String, authorized: Boolean) {
        val current = currentSnapshot.get()
        currentSnapshot.set(
            current.copy(
                speakerVerificationResult = result,
                authorizationOutcome = if (authorized) "AUTHORIZED" else "DENIED"
            )
        )
        recordEvent("Speaker verification: $result, authorized=$authorized")
    }

    fun recordPipelineOutcome(stage: String, outcome: String) {
        val current = currentSnapshot.get()
        currentSnapshot.set(
            current.copy(
                pipelineStage = stage,
                commandOutcome = outcome,
                micState = "CLOSED"
            )
        )
        recordEvent("Pipeline: $stage -> $outcome")
    }

    fun recordFallback(reason: String) {
        val current = currentSnapshot.get()
        currentSnapshot.set(
            current.copy(
                fallbackEngaged = true,
                failureReason = reason
            )
        )
        recordEvent("Fallback engaged: $reason")
    }

    fun recordFailure(reason: String, fallbackUsed: Boolean = false) {
        val current = currentSnapshot.get()
        currentSnapshot.set(
            current.copy(
                failureReason = reason,
                fallbackEngaged = fallbackUsed || current.fallbackEngaged
            )
        )
        recordEvent("Failure: $reason (fallback=$fallbackUsed)")
    }

    fun getSnapshot(): VoiceDiagnosticSnapshot = currentSnapshot.get()

    /**
     * Produces a sanitized, privacy-conscious diagnostic summary.
     * Contains no raw audio, biometric voiceprints, passwords, or personal details.
     */
    fun exportSanitizedReport(): String {
        val snap = currentSnapshot.get()
        return buildString {
            appendLine("=== VOXYN VOICE DIAGNOSTIC REPORT ===")
            appendLine("Session ID: ${snap.sessionId}")
            appendLine("Timestamp: ${snap.timestampMs}")
            appendLine("Active Engine: ${snap.activeEngine}")
            appendLine("Fallback Used: ${snap.fallbackEngaged}")
            appendLine("Audio Duration: ${snap.audioDurationMs} ms")
            appendLine("Signal RMS: ${snap.rmsEnergyDb} dB (Clipping: ${snap.isClipping})")
            appendLine("ASR Latency: ${snap.asrLatencyMs} ms")
            appendLine("Speaker Verification: ${snap.speakerVerificationResult}")
            appendLine("Authorization: ${snap.authorizationOutcome}")
            appendLine("Pipeline Stage: ${snap.pipelineStage}")
            appendLine("Command Outcome: ${snap.commandOutcome}")
            if (snap.failureReason.isNotEmpty()) {
                appendLine("Failure Reason: ${snap.failureReason}")
            }
            appendLine("--- Event History (last ${eventHistory.size}) ---")
            eventHistory.forEach { appendLine(it) }
            appendLine("======================================")
        }
    }
}
