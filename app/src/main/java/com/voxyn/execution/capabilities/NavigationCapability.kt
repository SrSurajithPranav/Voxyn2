package com.voxyn.execution.capabilities

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.utils.VoxynLogger

class NavigationCapability(
    private val context: Context
) : Capability {

    override val id: String = "navigation"

    override val supportedActions: Set<String> = setOf("NAVIGATE")

    override val riskLevel: CapabilityRisk = CapabilityRisk.LOW

    override val requiredPermissions: Set<String> = emptySet()

    override val requiresNetwork: Boolean = true

    override suspend fun execute(step: ActionStep): PhaseResult<String> {
        val destination = step.params["destination"] ?: step.params["destinations"] ?: ""
        VoxynLogger.i("NavigationCapability navigating to: '$destination'", "CAPABILITY")

        if (destination.isBlank()) {
            return PhaseResult.Failure("Destination is missing")
        }

        return try {
            val navUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
            val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (mapIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mapIntent)
                PhaseResult.Success("Navigating to $destination")
            } else {
                val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
                val fallbackIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (fallbackIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(fallbackIntent)
                    PhaseResult.Success("Opening maps to $destination")
                } else {
                    PhaseResult.Failure("No maps app installed to handle navigation")
                }
            }
        } catch (e: Exception) {
            VoxynLogger.e("Navigation capability failed: ${e.message}", e, "CAPABILITY")
            PhaseResult.Failure(e.message ?: "Navigation failed", canFallback = true)
        }
    }

    override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        return VerificationOutcome.Success
    }
}
