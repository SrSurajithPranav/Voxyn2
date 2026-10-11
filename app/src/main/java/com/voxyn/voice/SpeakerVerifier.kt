package com.voxyn.voice

import com.voxyn.utils.VoxynLogger
import kotlin.math.abs

/** Fail-closed contract for local speaker verification before command execution. */
interface SpeakerVerifier {
    suspend fun enroll(profile: VoiceProfile, samplesPcm16: ShortArray, sampleRateHz: Int): EnrollmentResult
    suspend fun verify(samplesPcm16: ShortArray, sampleRateHz: Int): SpeakerVerificationResult
}

sealed interface SpeakerVerificationResult {
    data class Accepted(val profile: VoiceProfile, val score: Float) : SpeakerVerificationResult
    data class Uncertain(val reason: String, val score: Float? = null) : SpeakerVerificationResult
    data class Rejected(val reason: String, val score: Float? = null) : SpeakerVerificationResult
    data class Unavailable(val reason: String) : SpeakerVerificationResult
}

/** Used until a tested on-device embedding model is installed. Never accepts audio. */
class UnavailableSpeakerVerifier : SpeakerVerifier {
    override suspend fun enroll(profile: VoiceProfile, samplesPcm16: ShortArray, sampleRateHz: Int): EnrollmentResult =
        EnrollmentResult.Failure("Speaker verification model is not installed")

    override suspend fun verify(samplesPcm16: ShortArray, sampleRateHz: Int): SpeakerVerificationResult =
        SpeakerVerificationResult.Unavailable("Speaker verification model is not installed")
}

/** A single fail-closed boundary shared by every command submission route. */
class CommandVerificationGate(private val verifier: SpeakerVerifier) {
    suspend fun authorize(samplesPcm16: ShortArray?, sampleRateHz: Int = 16_000): SpeakerVerificationResult {
        // Ordinary short voice commands are often under two seconds. A one-second minimum
        // avoids rejecting them solely by duration; the embedding model and score thresholds
        // still decide identity, and unavailable/uncertain results remain fail-closed.
        if (samplesPcm16 == null || sampleRateHz !in 8_000..48_000 || samplesPcm16.size < sampleRateHz) {
            VoxynLogger.w("Command verification rejected before model: sample missing/short or invalid rate (rate=$sampleRateHz, samples=${samplesPcm16?.size ?: 0})", "SPEAKER")
            return SpeakerVerificationResult.Rejected("A valid one-second microphone sample is required")
        }
        val nonSilent = samplesPcm16.count { abs(it.toInt()) >= 500 }
        val clipped = samplesPcm16.count { abs(it.toInt()) >= 32_000 }
        if (nonSilent < samplesPcm16.size / 100 || clipped > samplesPcm16.size / 20) {
            VoxynLogger.w("Command verification rejected for audio quality (active=${nonSilent * 100 / samplesPcm16.size}%, clipped=${clipped * 100 / samplesPcm16.size}%)", "SPEAKER")
            return SpeakerVerificationResult.Rejected("Command audio quality is insufficient")
        }
        val result = runCatching { verifier.verify(samplesPcm16, sampleRateHz) }
            .getOrElse { error ->
                VoxynLogger.e("Command speaker verifier threw ${error.javaClass.simpleName}: ${error.message}", error, "SPEAKER")
                SpeakerVerificationResult.Unavailable("Speaker verification failed")
            }
        when (result) {
            is SpeakerVerificationResult.Accepted -> VoxynLogger.i("Command speaker accepted: slot=${result.profile.slot + 1}, score=${"%.3f".format(result.score)}", "SPEAKER")
            is SpeakerVerificationResult.Uncertain -> VoxynLogger.w("Command speaker uncertain: score=${result.score?.let { "%.3f".format(it) } ?: "none"}; ${result.reason}", "SPEAKER")
            is SpeakerVerificationResult.Rejected -> VoxynLogger.w("Command speaker rejected: score=${result.score?.let { "%.3f".format(it) } ?: "none"}; ${result.reason}", "SPEAKER")
            is SpeakerVerificationResult.Unavailable -> VoxynLogger.e("Command speaker verification unavailable: ${result.reason}", tag = "SPEAKER")
        }
        return result
    }

    suspend fun <T> runIfAuthorized(
        samplesPcm16: ShortArray?,
        sampleRateHz: Int = 16_000,
        onRejected: suspend (SpeakerVerificationResult) -> Unit,
        command: suspend (VoiceProfile) -> T
    ): GateExecution<T> {
        val result = authorize(samplesPcm16, sampleRateHz)
        return if (result is SpeakerVerificationResult.Accepted) {
            GateExecution.Authorized(result.profile, command(result.profile))
        } else {
            onRejected(result)
            GateExecution.Blocked(result)
        }
    }
}

sealed interface GateExecution<out T> {
    data class Authorized<T>(val profile: VoiceProfile, val value: T) : GateExecution<T>
    data class Blocked(val reason: SpeakerVerificationResult) : GateExecution<Nothing>
}
