package com.voxyn.overlay

/** Presentation states mirror explicit pipeline events; they are not a second assistant pipeline. */
enum class IslandState {
    STOPPED,
    IDLE,
    LISTENING,
    PARTIAL_TRANSCRIPT,
    PROCESSING,
    PLANNING,
    EXECUTING,
    VERIFYING,
    SPEAKING,
    RECOVERING,
    CONFIRMATION,
    SUCCESS,
    ERROR
}

/** Maps only known backend events. Unknown values must be logged and ignored, never shown as idle. */
fun islandStateFromPipeline(value: String): IslandState? = when (value.trim().uppercase()) {
    "STOPPED" -> IslandState.STOPPED
    "IDLE" -> IslandState.IDLE
    "LISTENING" -> IslandState.LISTENING
    "PARTIAL_TRANSCRIPT", "TRANSCRIBING" -> IslandState.PARTIAL_TRANSCRIPT
    "PROCESSING", "THINKING" -> IslandState.PROCESSING
    "PLANNING", "PLAN" -> IslandState.PLANNING
    "EXECUTING", "EXECUTE" -> IslandState.EXECUTING
    "VERIFYING", "VERIFY" -> IslandState.VERIFYING
    "SPEAKING" -> IslandState.SPEAKING
    "RECOVERING" -> IslandState.RECOVERING
    "CONFIRMATION", "CLARIFICATION" -> IslandState.CONFIRMATION
    "SUCCESS" -> IslandState.SUCCESS
    "ERROR", "WARNING" -> IslandState.ERROR
    else -> null
}
