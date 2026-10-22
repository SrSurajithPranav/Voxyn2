package com.voxyn.execution

import com.voxyn.execution.capabilities.MediaCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCapabilityTest {

    @Test
    fun `media capability metadata and action support`() {
        val cap = MediaCapability(android.content.ContextWrapper(null))

        assertEquals("media", cap.id)
        assertTrue(cap.supportedActions.contains("PLAY_MUSIC"))
        assertTrue(cap.supportedActions.contains("PAUSE_MUSIC"))
        assertTrue(cap.supportedActions.contains("NEXT_TRACK"))
        assertTrue(cap.supportedActions.contains("PREV_TRACK"))
    }
}
