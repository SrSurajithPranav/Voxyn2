package com.voxyn.voice

/**
 * Provider-neutral contract for spoken replies. Implementations may be local
 * or remote; callers must not assume that a provider can clone a voice.
 */
interface VoiceGenerationProvider {
    fun speak(text: String, voiceId: String? = null, onComplete: (() -> Unit)? = null)
    fun stop()
    fun isAvailable(): Boolean
}

/** Explicit result used by future cloning providers so failures stay visible. */
sealed interface VoiceGenerationResult {
    data class Started(val voiceId: String?) : VoiceGenerationResult
    data class Unavailable(val reason: String) : VoiceGenerationResult
    data class Failed(val reason: String) : VoiceGenerationResult
}
