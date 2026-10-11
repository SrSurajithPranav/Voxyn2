package com.voxyn.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandVerificationGateTest {
    private val profile = VoiceProfile(
        slot = 0, id = "owner-1", label = "Owner", enrolled = true,
        sampleCount = 1, samplePath = null, updatedAt = 1L, speakerEnrolled = true
    )

    @Test
    fun rejectedAndUncertainSpeakersNeverReachCommand() = runBlocking {
        val verifier = FakeVerifier(SpeakerVerificationResult.Rejected("unknown"))
        val gate = CommandVerificationGate(verifier)
        var executions = 0
        val outcome = gate.runIfAuthorized(usableAudio(), onRejected = {}, command = { executions++; Unit })
        assertTrue(outcome is GateExecution.Blocked)
        assertEquals(0, executions)

        verifier.result = SpeakerVerificationResult.Uncertain("close match", .57f)
        val uncertain = gate.runIfAuthorized(usableAudio(), onRejected = {}, command = { executions++; Unit })
        assertTrue(uncertain is GateExecution.Blocked)
        assertEquals(0, executions)
    }

    @Test
    fun unavailableVerifierAndTextOnlyRouteFailClosed() = runBlocking {
        val verifier = FakeVerifier(SpeakerVerificationResult.Unavailable("model missing"))
        val gate = CommandVerificationGate(verifier)
        var executions = 0
        gate.runIfAuthorized(usableAudio(), onRejected = {}, command = { executions++; Unit })
        val textOnly = gate.runIfAuthorized(null, onRejected = {}, command = { executions++; Unit })
        assertTrue(textOnly is GateExecution.Blocked)
        assertEquals(0, executions)
        assertEquals(1, verifier.calls)
    }

    @Test
    fun authorizedSpeakerReachesCommand() = runBlocking {
        val gate = CommandVerificationGate(FakeVerifier(SpeakerVerificationResult.Accepted(profile, .81f)))
        val outcome = gate.runIfAuthorized(usableAudio(), onRejected = {}, command = { it.id })
        assertEquals(GateExecution.Authorized(profile, "owner-1"), outcome)
    }

    @Test
    fun silentAudioIsRejectedBeforeTheVerifier() = runBlocking {
        val verifier = FakeVerifier(SpeakerVerificationResult.Accepted(profile, .99f))
        val result = CommandVerificationGate(verifier).authorize(ShortArray(32_000))
        assertTrue(result is SpeakerVerificationResult.Rejected)
        assertEquals(0, verifier.calls)
    }

    private fun usableAudio() = ShortArray(32_000) { 2_000 }

    private class FakeVerifier(var result: SpeakerVerificationResult) : SpeakerVerifier {
        var calls = 0
        override suspend fun enroll(profile: VoiceProfile, samplesPcm16: ShortArray, sampleRateHz: Int) = EnrollmentResult.Success(profile)
        override suspend fun verify(samplesPcm16: ShortArray, sampleRateHz: Int): SpeakerVerificationResult {
            calls++
            return result
        }
    }
}
