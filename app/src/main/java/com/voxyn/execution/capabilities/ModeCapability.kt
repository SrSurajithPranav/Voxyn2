package com.voxyn.execution.capabilities

import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.mode.ModeEngine
import com.voxyn.utils.VoxynLogger

class ModeCapability(
    private val modeEngine: ModeEngine
) : Capability {

    override val id: String = "mode"

    override val supportedActions: Set<String> = setOf("MODE_ACTIVATE")

    override val riskLevel: CapabilityRisk = CapabilityRisk.LOW

    override val requiredPermissions: Set<String> = emptySet()

    override val requiresNetwork: Boolean = false

    override suspend fun execute(step: ActionStep): PhaseResult<String> {
        val modeName = step.params["mode_name"] ?: "normal"
        VoxynLogger.i("ModeCapability activating mode: '$modeName'", "CAPABILITY")

        return try {
            val result = modeEngine.activateMode(modeName)
            PhaseResult.Success(result)
        } catch (e: Exception) {
            VoxynLogger.e("Mode capability failed: ${e.message}", e, "CAPABILITY")
            PhaseResult.Failure(e.message ?: "Mode activation failed", canFallback = true)
        }
    }

    override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        return VerificationOutcome.Success
    }
}
