package com.voxyn.execution.capabilities

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.utils.VoxynLogger

class ClockCapability(
    private val context: Context
) : Capability {

    override val id: String = "clock"

    override val supportedActions: Set<String> = setOf(
        "SET_ALARM",
        "DISMISS_ALARM",
        "SNOOZE_ALARM",
        "SHOW_ALARMS",
        "SET_TIMER",
        "DISMISS_TIMER",
        "SHOW_TIMERS"
    )

    override val riskLevel: CapabilityRisk = CapabilityRisk.LOW

    override val requiredPermissions: Set<String> = emptySet()

    override val requiresNetwork: Boolean = false

    override suspend fun execute(step: ActionStep): PhaseResult<String> {
        VoxynLogger.i("ClockCapability executing: ${step.actionType}", "CAPABILITY")

        return try {
            when (step.actionType) {
                "SET_ALARM"     -> setAlarm(step.params)
                "DISMISS_ALARM" -> dismissAlarm()
                "SNOOZE_ALARM"  -> snoozeAlarm()
                "SHOW_ALARMS"   -> showAlarms()
                "SET_TIMER"     -> setTimer(step.params)
                "DISMISS_TIMER" -> dismissTimer()
                "SHOW_TIMERS"   -> showTimers()
                else            -> PhaseResult.Failure("Unsupported clock action: ${step.actionType}", canFallback = false)
            }
        } catch (e: Exception) {
            VoxynLogger.e("Clock capability failed: ${e.message}", e, "CAPABILITY")
            PhaseResult.Failure(e.message ?: "Clock action failed", canFallback = true)
        }
    }

    override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        return VerificationOutcome.Success
    }

    private fun setAlarm(params: Map<String, String>): PhaseResult<String> {
        val hour = params["hour"]?.toIntOrNull() ?: 7
        val minutes = params["minutes"]?.toIntOrNull() ?: 0
        val message = params["message"] ?: "VOXYN Alarm"

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            val timeStr = String.format("%d:%02d", hour, minutes)
            PhaseResult.Success("Alarm set for $timeStr")
        } else {
            PhaseResult.Failure("No clock app available to set alarm")
        }
    }

    private fun dismissAlarm(): PhaseResult<String> {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            PhaseResult.Success("Alarm dismissed")
        } else {
            // Fallback: open alarms screen so user or accessibility can dismiss
            showAlarms()
        }
    }

    private fun snoozeAlarm(): PhaseResult<String> {
        val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            PhaseResult.Success("Alarm snoozed")
        } else {
            PhaseResult.Failure("Clock app does not support snooze intent")
        }
    }

    private fun showAlarms(): PhaseResult<String> {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            PhaseResult.Success("Opened alarms")
        } else {
            PhaseResult.Failure("No clock app available")
        }
    }

    private fun setTimer(params: Map<String, String>): PhaseResult<String> {
        val seconds = params["length_seconds"]?.toIntOrNull()
            ?: (params["minutes"]?.toIntOrNull()?.times(60))
            ?: 300
        val message = params["message"] ?: "VOXYN Timer"

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            val min = seconds / 60
            val sec = seconds % 60
            val durationStr = if (min > 0) "$min minute${if (min > 1) "s" else ""}" else "$sec seconds"
            PhaseResult.Success("Timer set for $durationStr")
        } else {
            PhaseResult.Failure("No clock app available to set timer")
        }
    }

    private fun dismissTimer(): PhaseResult<String> {
        val intent = Intent(AlarmClock.ACTION_DISMISS_TIMER).apply {
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            PhaseResult.Success("Timer stopped")
        } else {
            showTimers()
        }
    }

    private fun showTimers(): PhaseResult<String> {
        val intent = Intent(AlarmClock.ACTION_SHOW_TIMERS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            PhaseResult.Success("Opened timers")
        } else {
            PhaseResult.Failure("No clock app available")
        }
    }
}
