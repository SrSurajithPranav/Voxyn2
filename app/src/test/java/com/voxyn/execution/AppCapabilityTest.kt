package com.voxyn.execution

import com.voxyn.core.ActionStep
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.capabilities.AppCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCapabilityTest {

    @Test
    fun `app capability metadata and verification`() = runBlocking {
        val appCapability = AppCapability(
            AppLaunchResolver(
                android.content.ContextWrapper(null),
                com.voxyn.apps.AppCatalog(android.content.ContextWrapper(null))
            )
        )

        assertEquals("app", appCapability.id)
        assertTrue(appCapability.supportedActions.contains("OPEN_APP"))

        val step = ActionStep("s1", "open whatsapp", "OPEN_APP", mapOf("app_name" to "whatsapp"))
        val successOutcome = appCapability.verify(step, "Opened WhatsApp")
        assertEquals(VerificationOutcome.Success, successOutcome)

        val failOutcome = appCapability.verify(step, "I couldn't find an installed app named Zoomly")
        assertEquals(VerificationOutcome.Failure, failOutcome)
    }
}
