package com.voxyn.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CommandVerificationGateTest {

    private lateinit var tempFolder: File
    private lateinit var repository: SpeakerProfileRepository
    private lateinit var verifier: SpeakerVerifier
    private lateinit var gate: CommandVerificationGate

    @Before
    fun setup() {
        tempFolder = File.createTempFile("test_gate_dir", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }
        repository = SpeakerProfileRepository(baseDir = tempFolder)
        verifier = SpeakerVerifier(repository)
        gate = CommandVerificationGate(verifier, repository)
    }

    @Test
    fun `typed text input is authorized without biometric audio`() {
        val result = gate.verifyAndAuthorize(
            source = InputSource.TEXT,
            audioSamples = null,
            rawTranscript = "open youtube"
        )
        assertTrue(result is GateResult.Authorized)
        val auth = result as GateResult.Authorized
        assertEquals("Interactive User", auth.speakerName)
        assertTrue(auth.isOwner)
    }

    @Test
    fun `voice input without audio samples is denied`() {
        val result = gate.verifyAndAuthorize(
            source = InputSource.VOICE,
            audioSamples = null,
            rawTranscript = "open youtube"
        )
        assertTrue(result is GateResult.Denied)
        val denied = result as GateResult.Denied
        assertTrue(denied.reason.contains("AUDIO") || denied.reason.contains("QUALITY"))
    }

    @Test
    fun `voice input from verified enrolled speaker is authorized`() {
        val tone = ShortArray(16000) { index ->
            (Math.sin(2.0 * Math.PI * 220.0 * index / 16000.0) * 8000).toInt().toShort()
        }
        val vp = verifier.extractVoiceprint(tone)
        repository.enrollProfile(SpeakerProfile(id = "owner_id", name = "Surya", isOwner = true, voiceprint = vp))

        val result = gate.verifyAndAuthorize(
            source = InputSource.VOICE,
            audioSamples = tone,
            rawTranscript = "open youtube"
        )
        assertTrue(result is GateResult.Authorized)
        assertEquals("Surya", (result as GateResult.Authorized).speakerName)
        assertTrue(result.isOwner)
    }

    @Test
    fun `voice input from unverified speaker is denied with clear feedback`() {
        val toneA = ShortArray(16000) { index ->
            (Math.sin(2.0 * Math.PI * 180.0 * index / 16000.0) * 8000).toInt().toShort()
        }
        val vp = verifier.extractVoiceprint(toneA)
        repository.enrollProfile(SpeakerProfile(id = "owner_id", name = "Surya", isOwner = true, voiceprint = vp))

        val impostorTone = ShortArray(16000) { index ->
            (Math.sin(2.0 * Math.PI * 1200.0 * index / 16000.0) * 8000).toInt().toShort()
        }
        val result = gate.verifyAndAuthorize(
            source = InputSource.VOICE,
            audioSamples = impostorTone,
            rawTranscript = "turn on bluetooth"
        )
        assertTrue(result is GateResult.Denied)
        val denied = result as GateResult.Denied
        assertEquals("UNKNOWN_SPEAKER", denied.reason)
        assertTrue(denied.userFeedback.contains("Voice not recognized"))
    }

    @Test
    fun `voice input when zero profiles enrolled is authorized in open guest mode`() {
        val validAudio = ShortArray(16000) { index ->
            (Math.sin(2.0 * Math.PI * 200.0 * index / 16000.0) * 8000).toInt().toShort()
        }
        val result = gate.verifyAndAuthorize(
            source = InputSource.VOICE,
            audioSamples = validAudio,
            rawTranscript = "what is the weather"
        )
        assertTrue(result is GateResult.Authorized)
        val auth = result as GateResult.Authorized
        assertEquals("Guest", auth.speakerName)
        org.junit.Assert.assertFalse(auth.isOwner)
    }
}
