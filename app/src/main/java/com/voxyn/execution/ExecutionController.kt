package com.voxyn.execution

import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.CancellationException

class ExecutionController(
    val capabilityRegistry: CapabilityRegistry
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VoxynLogger.e("Execution error for ${step.actionType}: ${e.message}", e, "EXEC")
            PhaseResult.Failure(e.message ?: "Execution failed", canFallback = true)
        }
    }

    suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        val capability = capabilityRegistry.find(step.actionType) ?: return VerificationOutcome.Unverified
        return try {
            capability.verify(step, executionResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VoxynLogger.e("Verification error for ${step.actionType}: ${e.message}", e, "VERIFY")
            VerificationOutcome.Unverified
        }
    }
}
