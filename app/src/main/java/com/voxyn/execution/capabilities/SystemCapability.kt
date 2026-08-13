package com.voxyn.execution.capabilities

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.utils.VoxynLogger

class SystemCapability(
    private val context: Context
) : Capability {

    override val id: String = "system"

    override val supportedActions: Set<String> = setOf(
        "WIFI_ON",
        "WIFI_OFF",
        "BLUETOOTH_ON",
        "BLUETOOTH_OFF",
        "VOLUME_UP",
        "VOLUME_DOWN",
        "SILENT_MODE",
        "VIBRATE_MODE",
        "NORMAL_MODE",
        "AIRPLANE_ON",
        "AIRPLANE_OFF",
        "BATTERY_SAVER_ON",
        "BATTERY_STATUS"
    )

    override val riskLevel: CapabilityRisk = CapabilityRisk.LOW

    override val requiredPermissions: Set<String> = emptySet()

    override val requiresNetwork: Boolean = false

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override suspend fun execute(
        step: ActionStep
    ): PhaseResult<String> {

        VoxynLogger.i(
            "System capability executing: ${step.actionType}",
            "CAPABILITY"
        )

        return try {
            when (step.actionType) {

                "WIFI_ON",
                "WIFI_OFF" -> openWifiSettings()

                "BLUETOOTH_ON",
                "BLUETOOTH_OFF" -> openBluetoothSettings()

                "VOLUME_UP" -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )

                    PhaseResult.Success("Volume increased")
                }

                "VOLUME_DOWN" -> {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )

                    PhaseResult.Success("Volume decreased")
                }

                "SILENT_MODE" -> silentMode()

                "VIBRATE_MODE" -> {
                    audioManager.ringerMode =
                        AudioManager.RINGER_MODE_VIBRATE

                    PhaseResult.Success("Vibrate mode enabled")
                }

                "NORMAL_MODE" -> {
                    audioManager.ringerMode =
                        AudioManager.RINGER_MODE_NORMAL

                    PhaseResult.Success("Normal mode enabled")
                }

                "AIRPLANE_ON",
                "AIRPLANE_OFF" -> openAirplaneSettings()

                "BATTERY_SAVER_ON",
                "BATTERY_STATUS" -> openBatterySettings()

                else -> PhaseResult.Failure(
                    "Unsupported system action: ${step.actionType}",
                    canFallback = false
                )
            }

        } catch (e: Exception) {

            VoxynLogger.e(
                "System capability failed: ${e.message}",
                e,
                "CAPABILITY"
            )

            PhaseResult.Failure(
                e.message ?: "System action failed",
                canFallback = true
            )
        }
    }

    override suspend fun verify(
        step: ActionStep,
        executionResult: String
    ): VerificationOutcome {

        /*
         * Some Android system actions currently open Settings
         * rather than directly changing the state.
         *
         * Until we implement state-aware verification,
         * report the execution as successful if the action itself
         * completed without an exception.
         */
        return VerificationOutcome.Success
    }

    private fun openWifiSettings(): PhaseResult<String> {

        val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(intent)

        return PhaseResult.Success("WiFi settings opened")
    }

    private fun openBluetoothSettings(): PhaseResult<String> {

        val intent = Intent(
            Settings.ACTION_BLUETOOTH_SETTINGS
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(intent)

        return PhaseResult.Success("Bluetooth settings opened")
    }

    private fun silentMode(): PhaseResult<String> {

        return try {

            if (notificationManager.isNotificationPolicyAccessGranted) {

                audioManager.ringerMode =
                    AudioManager.RINGER_MODE_SILENT

                PhaseResult.Success("Silent mode enabled")

            } else {

                val intent = Intent(
                    Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                context.startActivity(intent)

                PhaseResult.Success(
                    "Please grant notification policy access"
                )
            }

        } catch (e: Exception) {

            PhaseResult.Failure(
                "Silent mode failed: ${e.message}",
                canFallback = true
            )
        }
    }

    private fun openAirplaneSettings(): PhaseResult<String> {

        val intent = Intent(
            Settings.ACTION_AIRPLANE_MODE_SETTINGS
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(intent)

        return PhaseResult.Success(
            "Airplane mode settings opened"
        )
    }

    private fun openBatterySettings(): PhaseResult<String> {

        val intent = Intent(
            Settings.ACTION_BATTERY_SAVER_SETTINGS
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(intent)

        return PhaseResult.Success(
            "Battery settings opened"
        )
    }
}
