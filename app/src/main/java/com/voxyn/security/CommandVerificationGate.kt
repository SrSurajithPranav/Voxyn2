package com.voxyn.security

import com.voxyn.utils.VoxynLogger

enum class InputSource {
    VOICE,
    TEXT
}

sealed class GateResult {
    data class Authorized(
        val speakerName: String,
        val isOwner: Boolean,
        val source: InputSource,
        val confidence: Float = 1.0f
    ) : GateResult()

    data class Denied(
        val reason: String,
        val userFeedback: String
    ) : GateResult()
}

/**
 * CommandVerificationGate — Security and authorization enforcement seam.
 *
 * Distinguishes ASR output from speaker authorization.
 * All voice commands MUST pass speaker verification against the enrolled profiles.
 * Interactive text input from the app UI is authorized as TEXT with high-risk
 * confirmation policies preserved.
 */
class CommandVerificationGate(
    private val speakerVerifier: SpeakerVerifier,
    private val repository: SpeakerProfileRepository
) {

    fun verifyAndAuthorize(
        source: InputSource,
        audioSamples: ShortArray?,
        rawTranscript: String,
        isMediaActive: Boolean = false
    ): GateResult {
        if (rawTranscript.isBlank()) {
            return GateResult.Denied(
                reason = "EMPTY_TRANSCRIPT",
                userFeedback = "I didn't catch that command."
            )
        }

        // Interactive text input in the app UI
        if (source == InputSource.TEXT) {
            VoxynLogger.i("Gate: Text command authorized for interactive session", "GATE")
            return GateResult.Authorized(
                speakerName = repository.getOwnerProfile()?.name ?: "Interactive User",
                isOwner = true,
                source = InputSource.TEXT
            )
        }

        // Voice commands require speaker verification
        when (val outcome = speakerVerifier.verify(audioSamples, isMediaActive)) {
            is VerificationResult.Verified -> {
                VoxynLogger.i("Gate: Voice command authorized for ${outcome.profile.name} (confidence=${outcome.similarity})", "GATE")
                return GateResult.Authorized(
                    speakerName = outcome.profile.name,
                    isOwner = outcome.profile.isOwner,
                    source = InputSource.VOICE,
                    confidence = outcome.similarity
                )
            }
            is VerificationResult.RejectedUnknownSpeaker -> {
                VoxynLogger.w("Gate: Voice command rejected — unknown speaker", "GATE")
                return GateResult.Denied(
                    reason = "UNKNOWN_SPEAKER",
                    userFeedback = "Voice not recognized. Only enrolled speakers can issue commands."
                )
            }
            is VerificationResult.NoProfilesEnrolled -> {
                VoxynLogger.i("Gate: Voice command authorized in open guest mode — no profiles enrolled", "GATE")
                return GateResult.Authorized(
                    speakerName = "Guest",
                    isOwner = false,
                    source = InputSource.VOICE,
                    confidence = 0.6f
                )
            }
            is VerificationResult.PoorAudioQuality -> {
                VoxynLogger.w("Gate: Voice command rejected — audio quality (${outcome.reason})", "GATE")
                return GateResult.Denied(
                    reason = "POOR_AUDIO_QUALITY",
                    userFeedback = "I couldn't hear your voice clearly enough to verify you."
                )
            }
            is VerificationResult.Error -> {
                VoxynLogger.e("Gate: Speaker verification error (${outcome.message})", tag = "GATE")
                return GateResult.Denied(
                    reason = "VERIFICATION_ERROR",
                    userFeedback = "Voice verification failed. Please try again."
                )
            }
        }
    }
}
