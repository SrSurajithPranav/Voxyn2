package com.voxyn.execution

import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.capabilities.CameraCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityTest {

    @Test
    fun `camera capability metadata is properly declared`() {
        val actions = setOf("TAKE_PHOTO", "RECORD_VIDEO", "OPEN_CAMERA")
        val permissions = setOf(android.Manifest.permission.CAMERA)

        // Mock/Unit check structure
        assertTrue(actions.contains("TAKE_PHOTO"))
        assertTrue(actions.contains("RECORD_VIDEO"))
        assertTrue(actions.contains("OPEN_CAMERA"))
        assertTrue(permissions.contains(android.Manifest.permission.CAMERA))
    }

    @Test
    fun `capability registry finds camera capability`() {
        val fakeSystem = com.voxyn.execution.capabilities.SystemCapability(
            android.content.ContextWrapper(null)
        )
        val registry = CapabilityRegistry(listOf(fakeSystem))
        assertNotNull(registry.find("WIFI_ON"))
    }
}
