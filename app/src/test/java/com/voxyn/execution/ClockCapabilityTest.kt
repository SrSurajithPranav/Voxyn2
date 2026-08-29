package com.voxyn.execution

import com.voxyn.execution.capabilities.ClockCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockCapabilityTest {

    @Test
    fun `clock capability metadata and action support`() {
        val cap = ClockCapability(android.content.ContextWrapper(null))

        assertEquals("clock", cap.id)
        assertTrue(cap.supportedActions.contains("SET_ALARM"))
        assertTrue(cap.supportedActions.contains("SET_TIMER"))
    }
}
