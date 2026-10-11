package com.voxyn.receiver

/** Decisions for microphone foreground-service startup before any Android service call. */
internal enum class BootMicrophoneDecision {
    MICROPHONE_PERMISSION_REQUIRED,
    USER_MUST_OPEN_APP,
    START_ALLOWED
}

internal object VoiceStartupPolicy {
    fun bootMicrophoneDecision(
        microphonePermissionGranted: Boolean,
        deviceSdk: Int,
        targetSdk: Int
    ): BootMicrophoneDecision = when {
        !microphonePermissionGranted -> BootMicrophoneDecision.MICROPHONE_PERMISSION_REQUIRED
        deviceSdk >= 34 && targetSdk >= 34 -> BootMicrophoneDecision.USER_MUST_OPEN_APP
        else -> BootMicrophoneDecision.START_ALLOWED
    }

    /** Keep diagnostics useful while excluding platform exception messages and user data. */
    fun startupFailureCategory(exceptionType: String): String = when (exceptionType) {
        "ForegroundServiceStartNotAllowedException",
        "SecurityException" -> "ANDROID_START_DENIED"
        else -> "SERVICE_START_FAILED"
    }
}
