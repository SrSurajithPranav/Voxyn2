package com.voxyn.execution

import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.utils.VoxynLogger

class ExecutionController(
    private val capabilityRegistry: CapabilityRegistry
) {

    suspend fun execute(step: ActionStep): PhaseResult<String> {
        VoxynLogger.i("Executing action: ${step.actionType}", "EXEC")

        val capability = capabilityRegistry.find(step.actionType)
            ?: run {
                VoxynLogger.w("No registered capability for action: ${step.actionType}", "EXEC")
                return PhaseResult.Failure("Unknown action: ${step.actionType}", canFallback = true)
            }

        return try {
            capability.execute(step)
        } catch (e: Exception) {
            VoxynLogger.e("Execution error for ${step.actionType}: ${e.message}", e, "EXEC")
            PhaseResult.Failure(e.message ?: "Execution failed", canFallback = true)
        }
    }
}
