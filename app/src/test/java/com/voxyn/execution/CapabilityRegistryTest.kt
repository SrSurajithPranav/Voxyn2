package com.voxyn.execution

import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityRegistryTest {

    private class MockCapability(
        override val id: String,
        override val supportedActions: Set<String>
    ) : Capability {
        override val riskLevel: CapabilityRisk = CapabilityRisk.LOW
        override val requiredPermissions: Set<String> = emptySet()
        override val requiresNetwork: Boolean = false

        override suspend fun execute(step: ActionStep): PhaseResult<String> {
            return PhaseResult.Success("Executed ${step.actionType}")
        }

        override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
            return VerificationOutcome.Success
        }
    }

    @Test
    fun `finds capability by supported action`() {
        val cap1 = MockCapability("sys", setOf("WIFI_ON", "WIFI_OFF"))
        val cap2 = MockCapability("cam", setOf("TAKE_PHOTO"))
        val registry = CapabilityRegistry(listOf(cap1, cap2))

        val found = registry.find("WIFI_ON")
        assertNotNull(found)
        assertEquals("sys", found?.id)

        val foundCam = registry.find("TAKE_PHOTO")
        assertNotNull(foundCam)
        assertEquals("cam", foundCam?.id)

        val notFound = registry.find("UNKNOWN_ACTION")
        assertNull(notFound)
    }

    @Test
    fun `lists all capabilities and supported actions`() {
        val cap1 = MockCapability("sys", setOf("WIFI_ON"))
        val cap2 = MockCapability("app", setOf("OPEN_APP"))
        val registry = CapabilityRegistry(listOf(cap1, cap2))

        assertEquals(2, registry.all().size)
        assertEquals(setOf("WIFI_ON", "OPEN_APP"), registry.supportedActions())
    }
}
