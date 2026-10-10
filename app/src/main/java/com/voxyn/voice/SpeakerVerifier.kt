package com.voxyn.voice

/** Fail-closed contract for local speaker verification before command execution. */
interface SpeakerVerifier {
    suspend fun verify(samplesPcm16: ShortArray, sampleRateHz: Int): SpeakerVerificationResult
}

sealed interface SpeakerVerificationResult {
    data class Accepted(val profile: VoiceProfile, val score: Float) : SpeakerVerificationResult
    data class Rejected(val reason: String, val score: Float? = null) : SpeakerVerificationResult
    data class Unavailable(val reason: String) : SpeakerVerificationResult
}

/** Used until a tested on-device embedding model is installed. Never accepts audio. */
class UnavailableSpeakerVerifier : SpeakerVerifier {
    override suspend fun verify(samplesPcm16: ShortArray, sampleRateHz: Int): SpeakerVerificationResult =
        SpeakerVerificationResult.Unavailable("Speaker verification model is not installed")
}
