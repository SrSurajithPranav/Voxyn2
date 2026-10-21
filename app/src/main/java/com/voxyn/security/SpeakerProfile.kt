package com.voxyn.security

/**
 * An enrolled speaker profile.
 * VOXYN supports up to 5 enrolled profiles (including the primary owner).
 */
data class SpeakerProfile(
    val id: String,
    val name: String,
    val isOwner: Boolean,
    val voiceprint: FloatArray,
    val enrolledAtMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeakerProfile) return false
        return id == other.id && name == other.name && isOwner == other.isOwner
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + isOwner.hashCode()
        return result
    }
}
