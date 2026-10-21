package com.voxyn.voice

import com.voxyn.wake.WakeWordService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTriggerTest {

    @Test
    fun `genuine wake phrases trigger activation`() {
        assertTrue(WakeWordService.isWakeTrigger("hey voxyn"))
        assertTrue(WakeWordService.isWakeTrigger("hi voxyn"))
        assertTrue(WakeWordService.isWakeTrigger("okay voxyn"))
        assertTrue(WakeWordService.isWakeTrigger("ok voxyn"))
        assertTrue(WakeWordService.isWakeTrigger("voxyn"))
        assertTrue(WakeWordService.isWakeTrigger("hey voxyn open youtube"))
        assertTrue(WakeWordService.isWakeTrigger("voxyn turn on bluetooth"))
        assertTrue(WakeWordService.isWakeTrigger("Okay Voxyn what is the weather"))
    }

    @Test
    fun `isolated common words do not trigger false wake`() {
        assertFalse(WakeWordService.isWakeTrigger("hey"))
        assertFalse(WakeWordService.isWakeTrigger("hi"))
        assertFalse(WakeWordService.isWakeTrigger("okay"))
        assertFalse(WakeWordService.isWakeTrigger("ok"))
        assertFalse(WakeWordService.isWakeTrigger("they"))
        assertFalse(WakeWordService.isWakeTrigger("today"))
        assertFalse(WakeWordService.isWakeTrigger("okay mom what is for dinner"))
        assertFalse(WakeWordService.isWakeTrigger("talking to my friend hey what's up"))
        assertFalse(WakeWordService.isWakeTrigger("hi there how are you"))
        assertFalse(WakeWordService.isWakeTrigger(""))
        assertFalse(WakeWordService.isWakeTrigger("   "))
    }
}
