package com.voxyn.voice

import com.voxyn.security.CommandVerificationGate
import com.voxyn.security.GateResult
import com.voxyn.security.SpeakerProfile
import com.voxyn.security.SpeakerProfileRepository
import com.voxyn.security.SpeakerVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class VoiceSessionCoordinatorTest {

    private lateinit var coordinator: VoiceSessionCoordinator
    private lateinit var diagnostics: VoiceDiagnosticsManager
    private lateinit var gate: CommandVerificationGate
    private lateinit var verifier: SpeakerVerifier
    private lateinit var repository: SpeakerProfileRepository
    private lateinit var tempFolder: File

    @Before
    fun setup() {
        tempFolder = File.createTempFile("test_coord_dir", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }
        repository = SpeakerProfileRepository(baseDir = tempFolder)
        verifier = SpeakerVerifier(repository)
        gate = CommandVerificationGate(verifier, repository)
        diagnostics = VoiceDiagnosticsManager()
        coordinator = VoiceSessionCoordinator(gate, diagnostics)
    }

    @Test
    fun `lifecycle progresses monotonically through states`() {
        assertEquals(SessionState.IDLE, coordinator.getState())

        val sid = coordinator.onWakeDetected()
        assertEquals(SessionState.WAKE_DETECTED, coordinator.getState())

        coordinator.onListeningStarted(sid, "SherpaParakeet")
        assertEquals(SessionState.LISTENING, coordinator.getState())

        // Enroll speaker so verification succeeds
        val tone = ShortArray(16000) { index ->
            (Math.sin(2.0 * Math.PI * 250.0 * index / 16000.0) * 8000).toInt().toShort()
        }
        val vp = verifier.extractVoiceprint(tone)
        repository.enrollProfile(SpeakerProfile(id = "owner_1", name = "Surya", isOwner = true, voiceprint = vp))

        val gateResult = coordinator.onSpeechResult(sid, "open youtube", 1000L, tone)
        assertTrue(gateResult is GateResult.Authorized)
        assertEquals(SessionState.PROCESSING, coordinator.getState())

        coordinator.onCommandExecuting(sid)
        assertEquals(SessionState.EXECUTING, coordinator.getState())

        coordinator.onSessionEnded()
        assertEquals(SessionState.IDLE, coordinator.getState())
    }

    @Test
    fun `stale session callbacks are rejected and ignored`() {
        val sid1 = coordinator.onWakeDetected()
        coordinator.onListeningStarted(sid1, "SherpaParakeet")

        // User or system starts a new session
        val sid2 = coordinator.onWakeDetected()
        assertEquals(sid2, coordinator.getActiveSessionId())

        // Late callback from sid1 arrives
        val tone = ShortArray(16000) { 1000 }
        val staleResult = coordinator.onSpeechResult(sid1, "stale command", 1000L, tone)

        assertTrue(staleResult is GateResult.Denied)
        assertEquals("STALE_SESSION", (staleResult as GateResult.Denied).reason)
    }
}
