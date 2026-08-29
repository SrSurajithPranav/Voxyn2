package com.voxyn.execution

import com.voxyn.execution.capabilities.NavigationCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCapabilityTest {

    @Test
    fun `navigation capability metadata and action support`() {
        val cap = NavigationCapability(android.content.ContextWrapper(null))

        assertEquals("navigation", cap.id)
        assertTrue(cap.supportedActions.contains("NAVIGATE"))
        assertTrue(cap.requiresNetwork)
    }
}
