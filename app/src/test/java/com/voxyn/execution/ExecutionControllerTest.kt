package com.voxyn.execution

import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionControllerTest {

    private class MockTestCapability(
        override val id: String,
        override val supportedActions: Set<String>,
        var verificationOutcome: VerificationOutcome = VerificationOutcome.Success
    ) : Capability {
        var verifiedResult: String? = null

        override val riskLevel: CapabilityRisk = CapabilityRisk.LOW
        override val requiredPermissions: Set<String> = emptySet()
        override val requiresNetwork: Boolean = false

        override suspend fun execute(step: ActionStep): PhaseResult<String> {
            return if (step.actionType == "FAIL_ACTION") {
                PhaseResult.Failure("Intentional failure")
            } else {
                PhaseResult.Success("Executed ${step.actionType}")
            }
        }

        override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
            verifiedResult = executionResult
            return verificationOutcome
        }
    }

    @Test
    fun `executes registered capability action successfully`() = runBlocking {
        val cap = MockTestCapability("mock", setOf("CUSTOM_ACTION"))
        val registry = CapabilityRegistry(listOf(cap))
        val controller = ExecutionController(registry)

        val step = ActionStep("s1", "description", "CUSTOM_ACTION", emptyMap())
        val result = controller.execute(step)

        assertTrue(result is PhaseResult.Success)
        assertEquals("Executed CUSTOM_ACTION", (result as PhaseResult.Success).value)
    }

    @Test
    fun `returns failure when capability fails`() = runBlocking {
        val cap = MockTestCapability("mock", setOf("FAIL_ACTION"))
        val registry = CapabilityRegistry(listOf(cap))
        val controller = ExecutionController(registry)

        val step = ActionStep("s1", "description", "FAIL_ACTION", emptyMap())
        val result = controller.execute(step)

        assertTrue(result is PhaseResult.Failure)
        assertEquals("Intentional failure", (result as PhaseResult.Failure).error)
    }

    @Test
    fun `returns failure when action is not registered in capability registry`() = runBlocking {
        val registry = CapabilityRegistry(emptyList())
        val controller = ExecutionController(registry)

        val step = ActionStep("s1", "description", "NONEXISTENT", emptyMap())
        val result = controller.execute(step)

        assertTrue(result is PhaseResult.Failure)
        assertTrue((result as PhaseResult.Failure).error.contains("Unknown action"))
    }

    @Test
    fun `verification delegates to the capability and preserves unverified outcome`() = runBlocking {
        val cap = MockTestCapability("mock", setOf("CUSTOM_ACTION"), VerificationOutcome.Unverified)
        val controller = ExecutionController(CapabilityRegistry(listOf(cap)))
        val step = ActionStep("s1", "description", "CUSTOM_ACTION")

        val outcome = controller.verify(step, "Handed off to Android")

        assertEquals(VerificationOutcome.Unverified, outcome)
        assertEquals("Handed off to Android", cap.verifiedResult)
    }
}
