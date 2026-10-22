package com.voxyn.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SpeakerVerifierTest {

    private lateinit var tempFolder: File
    private lateinit var repository: SpeakerProfileRepository
    private lateinit var verifier: SpeakerVerifier

    @Before
    fun setup() {
        tempFolder = File.createTempFile("test_speaker_dir", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }
        repository = SpeakerProfileRepository(baseDir = tempFolder)
        verifier = SpeakerVerifier(repository)
    }

    @Test
    fun `short audio fails audio quality gate`() {
        val shortAudio = ShortArray(1000) { 1000 } // < 3200 samples
        val result = verifier.verify(shortAudio)
        assertTrue(result is VerificationResult.PoorAudioQuality)
        assertTrue((result as VerificationResult.PoorAudioQuality).reason.contains("short"))
    }

    @Test
    fun `silent audio fails audio quality gate`() {
        val silentAudio = ShortArray(16000) { 0 } // Absolute silence
        val result = verifier.verify(silentAudio)
        assertTrue(result is VerificationResult.PoorAudioQuality)
        assertTrue((result as VerificationResult.PoorAudioQuality).reason.contains("quiet"))
    }

    @Test
    fun `clipped audio fails audio quality gate`() {
        // More than 15% samples at peak scale
        val clippedAudio = ShortArray(16000) { index ->
            if (index % 4 == 0) 32750.toShort() else 1000.toShort()
        }
        val result = verifier.verify(clippedAudio)
        assertTrue(result is VerificationResult.PoorAudioQuality)
        assertTrue((result as VerificationResult.PoorAudioQuality).reason.contains("clipped"))
    }

    @Test
    fun `verification returns NoProfilesEnrolled when repository empty`() {
        val validAudio = ShortArray(16000) { index ->
            (Math.sin(2.0 * Math.PI * 200.0 * index / 16000.0) * 8000).toInt().toShort()
        }
        val result = verifier.verify(validAudio)
        assertEquals(VerificationResult.NoProfilesEnrolled, result)
    }

    @Test
    fun `enrolled voice verified with matching voiceprint`() {
        val validAudio = ShortArray(16000) { index ->
            (Math.sin(2.0 * Math.PI * 200.0 * index / 16000.0) * 8000).toInt().toShort()
        }
        val voiceprint = verifier.extractVoiceprint(validAudio)
        val profile = SpeakerProfile(id = "profile_owner", name = "Owner", isOwner = true, voiceprint = voiceprint)
        val enrolled = repository.enrollProfile(profile)
        assertTrue(enrolled)

        val verifyResult = verifier.verify(validAudio)
        assertTrue(verifyResult is VerificationResult.Verified)
        val verified = verifyResult as VerificationResult.Verified
        assertEquals("Owner", verified.profile.name)
        assertTrue(verified.profile.isOwner)
        assertTrue(verified.similarity >= SpeakerVerifier.SIMILARITY_THRESHOLD)
    }

    @Test
    fun `non enrolled voice with distinct voiceprint is rejected`() {
        val toneA = ShortArray(16000) { index ->
            (Math.sin(2.0 * Math.PI * 150.0 * index / 16000.0) * 8000).toInt().toShort()
        }
        val voiceprintA = verifier.extractVoiceprint(toneA)
        repository.enrollProfile(SpeakerProfile(id = "profile_owner", name = "Owner", isOwner = true, voiceprint = voiceprintA))

        // A totally different frequency / harmonic structure
        val toneB = ShortArray(16000) { index ->
            (Math.sin(2.0 * Math.PI * 2500.0 * index / 16000.0) * 8000).toInt().toShort()
        }
        val verifyResult = verifier.verify(toneB)
        assertTrue(verifyResult is VerificationResult.RejectedUnknownSpeaker)
    }

    @Test
    fun `maximum 25 speaker profiles enforced`() {
        repeat(25) { i ->
            val tone = ShortArray(16000) { idx ->
                (Math.sin(2.0 * Math.PI * (100.0 + i * 20) * idx / 16000.0) * 8000).toInt().toShort()
            }
            val vp = verifier.extractVoiceprint(tone)
            val res = repository.enrollProfile(SpeakerProfile(id = "profile_$i", name = "Speaker $i", isOwner = i == 0, voiceprint = vp))
            assertTrue(res)
        }
        assertEquals(25, repository.getAllProfiles().size)

        // Attempting to enroll 26th profile should be rejected
        val excessTone = ShortArray(16000) { idx ->
            (Math.sin(2.0 * Math.PI * 700.0 * idx / 16000.0) * 8000).toInt().toShort()
        }
        val excessVp = verifier.extractVoiceprint(excessTone)
        val excessRes = repository.enrollProfile(SpeakerProfile(id = "profile_26", name = "Speaker 26", isOwner = false, voiceprint = excessVp))
        assertFalse(excessRes)
    }

    @Test
    fun `profile with multiple samples matches candidate from any sample`() {
        val sample1Audio = ShortArray(16000) { idx ->
            (Math.sin(2.0 * Math.PI * 180.0 * idx / 16000.0) * 8000).toInt().toShort()
        }
        val sample2Audio = ShortArray(16000) { idx ->
            (Math.sin(2.0 * Math.PI * 240.0 * idx / 16000.0) * 8000).toInt().toShort()
        }

        val vp1 = verifier.extractVoiceprint(sample1Audio)
        val vp2 = verifier.extractVoiceprint(sample2Audio)

        val profile = SpeakerProfile(id = "owner", name = "Owner", isOwner = true, voiceprint = vp1)
        repository.enrollProfile(profile)
        repository.addSampleToProfile("owner", vp2)

        val updated = repository.getProfile("owner")
        assertEquals(2, updated?.sampleVoiceprints?.size)

        // Candidate matching sample 2 should verify successfully
        val result = verifier.verify(sample2Audio)
        assertTrue(result is VerificationResult.Verified)
    }
}
