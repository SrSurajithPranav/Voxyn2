package com.voxyn.voice

/** Metadata for one of VOXYN's fixed five speaker slots. */
data class VoiceProfile(
    val slot: Int,
    val id: String,
    val label: String,
    val enrolled: Boolean,
    val sampleCount: Int,
    val samplePath: String?,
    val updatedAt: Long?,
    val speakerEnrolled: Boolean = false
)

sealed class EnrollmentResult {
    data class Success(val profile: VoiceProfile) : EnrollmentResult()
    data class Rejected(val reason: String) : EnrollmentResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : EnrollmentResult()
}
