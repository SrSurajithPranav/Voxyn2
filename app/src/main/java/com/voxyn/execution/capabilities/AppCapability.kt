package com.voxyn.execution.capabilities

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.AppLaunchResolver
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.perception.VoxynAccessibilityService
import com.voxyn.utils.VoxynLogger

class AppCapability(
    private val context: Context,
    private val appLaunchResolver: AppLaunchResolver
) : Capability {

    override val id: String = "app"

    override val supportedActions: Set<String> = setOf("OPEN_APP", "CLOSE_APP")

    override val riskLevel: CapabilityRisk = CapabilityRisk.LOW

    override val requiredPermissions: Set<String> = emptySet()

    override val requiresNetwork: Boolean = false
    override val retrySafe: Boolean = true

    override suspend fun execute(step: ActionStep): PhaseResult<String> {
        val appName = step.params["app_name"] ?: ""
        return when (step.actionType) {
            "OPEN_APP" -> {
                VoxynLogger.i("AppCapability launching: '$appName'", "CAPABILITY")
                appLaunchResolver.launch(appName)
            }
            "CLOSE_APP" -> {
                VoxynLogger.i("AppCapability closing: '$appName'", "CAPABILITY")
                closeApp(appName)
            }
            else -> PhaseResult.Failure("Unsupported app action: ${step.actionType}")
        }
    }

    private fun closeApp(appName: String): PhaseResult<String> {
        // Step 1: Immediately take the user back to Home screen so the app leaves foreground
        val a11y = VoxynAccessibilityService.instance
        if (a11y != null) {
            a11y.performGoHome()
        } else {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
        }

        // Step 2: If a specific app was requested, kill background processes for that package
        val target = appName.trim()
        if (target.isNotBlank() && !target.equals("this", ignoreCase = true) && !target.equals("app", ignoreCase = true) && !target.equals("current", ignoreCase = true)) {
            val pkg = appLaunchResolver.resolvePackage(target)
            if (pkg != null) {
                runCatching {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    am?.killBackgroundProcesses(pkg)
                }
                return PhaseResult.Success("Closed $target")
            }
        }
        return PhaseResult.Success("Closed app")
    }

    override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        return if (executionResult.startsWith("Opened", ignoreCase = true) || executionResult.startsWith("Closed", ignoreCase = true)) {
            VerificationOutcome.Success
        } else {
            VerificationOutcome.Failure
        }
    }
}
