package com.voxyn.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceStartupPolicyTest {
    @Test
    fun bootRequiresPermissionBeforeAnyStartAttempt() {
        assertEquals(
            BootMicrophoneDecision.MICROPHONE_PERMISSION_REQUIRED,
            VoiceStartupPolicy.bootMicrophoneDecision(false, deviceSdk = 36, targetSdk = 34)
        )
    }

    @Test
    fun android14OrNewerRequiresVisibleUserEntryForTarget34Apps() {
        assertEquals(
            BootMicrophoneDecision.USER_MUST_OPEN_APP,
            VoiceStartupPolicy.bootMicrophoneDecision(true, deviceSdk = 36, targetSdk = 34)
        )
    }

    @Test
    fun bootStartRemainsEligibleWherePlatformPolicyAllowsIt() {
        assertEquals(
            BootMicrophoneDecision.START_ALLOWED,
            VoiceStartupPolicy.bootMicrophoneDecision(true, deviceSdk = 33, targetSdk = 34)
        )
    }

    @Test
    fun startupFailuresAreClassifiedWithoutDependingOnExceptionMessages() {
        assertEquals(
            "ANDROID_START_DENIED",
            VoiceStartupPolicy.startupFailureCategory("ForegroundServiceStartNotAllowedException")
        )
        assertEquals(
            "ANDROID_START_DENIED",
            VoiceStartupPolicy.startupFailureCategory("SecurityException")
        )
        assertEquals(
            "SERVICE_START_FAILED",
            VoiceStartupPolicy.startupFailureCategory("IllegalStateException")
        )
    }
}
