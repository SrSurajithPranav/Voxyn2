package com.voxyn.execution.capabilities

import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.AppLaunchResolver
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.utils.VoxynLogger

class AppCapability(
    private val appLaunchResolver: AppLaunchResolver
) : Capability {

    override val id: String = "app"

    override val supportedActions: Set<String> = setOf("OPEN_APP")

    override val riskLevel: CapabilityRisk = CapabilityRisk.LOW

    override val requiredPermissions: Set<String> = emptySet()

    override val requiresNetwork: Boolean = false

    override suspend fun execute(step: ActionStep): PhaseResult<String> {
        val appName = step.params["app_name"] ?: ""
        VoxynLogger.i("AppCapability launching: '$appName'", "CAPABILITY")
        return appLaunchResolver.launch(appName)
    }

    override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        return if (executionResult.startsWith("Opened", ignoreCase = true)) {
            VerificationOutcome.Success
        } else {
            VerificationOutcome.Failure
        }
    }
}
