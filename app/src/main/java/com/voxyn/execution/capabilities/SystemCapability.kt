package com.voxyn.execution.capabilities

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome
import com.voxyn.execution.Capability
import com.voxyn.execution.CapabilityRisk
import com.voxyn.perception.VoxynAccessibilityService
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
        "SET_VOLUME",
        "SILENT_MODE",
        "VIBRATE_MODE",
        "NORMAL_MODE",
        "FLASHLIGHT_ON",
        "FLASHLIGHT_OFF",
        "BRIGHTNESS_UP",
        "BRIGHTNESS_DOWN",
        "AIRPLANE_ON",
        "AIRPLANE_OFF",
        "BATTERY_SAVER_ON",
        "BATTERY_STATUS",
        "SCREENSHOT",
        "LOCK_PHONE",
        "VOLUME_MAX",
        "VOLUME_MIN",
        "MUTE",
        "UNMUTE",
        "OPEN_SETTINGS",
        "OPEN_WIFI_SETTINGS",
        "OPEN_BLUETOOTH_SETTINGS",
        "HOTSPOT_ON",
        "HOTSPOT_OFF"
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

    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    override suspend fun execute(step: ActionStep): PhaseResult<String> {
        VoxynLogger.i("System capability executing: ${step.actionType}", "CAPABILITY")

        return try {
            when (step.actionType) {
                "WIFI_ON", "WIFI_OFF" -> openWifiSettings()
                "BLUETOOTH_ON", "BLUETOOTH_OFF" -> openBluetoothSettings()

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

                "SET_VOLUME" -> {
                    val rawLevel = step.params["level"] ?: step.params["percent"] ?: "50"
                    val percent = when (rawLevel.lowercase()) {
                        "zero" -> 0
                        "ten" -> 10
                        "twenty" -> 20
                        "thirty" -> 30
                        "forty" -> 40
                        "fifty" -> 50
                        "sixty" -> 60
                        "seventy" -> 70
                        "eighty" -> 80
                        "ninety" -> 90
                        "hundred" -> 100
                        else -> rawLevel.toIntOrNull() ?: 50
                    }
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = ((percent / 100f) * max).toInt().coerceIn(0, max)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    PhaseResult.Success("Volume set to $percent percent")
                }

                "SILENT_MODE" -> silentMode()

                "VIBRATE_MODE" -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    PhaseResult.Success("Vibrate mode enabled")
                }

                "NORMAL_MODE" -> {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    PhaseResult.Success("Normal mode enabled")
                }

                "VOLUME_MAX" -> {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                    PhaseResult.Success("Volume set to maximum")
                }

                "VOLUME_MIN" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, AudioManager.FLAG_SHOW_UI)
                    PhaseResult.Success("Volume set to minimum")
                }

                "MUTE" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    PhaseResult.Success("Volume muted")
                }

                "UNMUTE" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    PhaseResult.Success("Volume unmuted")
                }

                "OPEN_SETTINGS" -> {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(intent)
                    PhaseResult.Success("Settings opened")
                }

                "OPEN_WIFI_SETTINGS" -> openWifiSettings()
                "OPEN_BLUETOOTH_SETTINGS" -> openBluetoothSettings()

                "FLASHLIGHT_ON" -> {
                    val cameraId = cameraManager.cameraIdList.firstOrNull()
                        ?: return PhaseResult.Failure("No camera flash available")
                    cameraManager.setTorchMode(cameraId, true)
                    PhaseResult.Success("Flashlight turned on")
                }

                "FLASHLIGHT_OFF" -> {
                    val cameraId = cameraManager.cameraIdList.firstOrNull()
                        ?: return PhaseResult.Failure("No camera flash available")
                    cameraManager.setTorchMode(cameraId, false)
                    PhaseResult.Success("Flashlight turned off")
                }

                "BRIGHTNESS_UP" -> {
                    val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                    val newLevel = (current + 50).coerceAtMost(255)
                    if (Settings.System.canWrite(context)) {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, newLevel)
                        PhaseResult.Success("Brightness increased")
                    } else {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        PhaseResult.Success("Please grant write settings permission for brightness")
                    }
                }

                "BRIGHTNESS_DOWN" -> {
                    val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                    val newLevel = (current - 50).coerceAtLeast(20)
                    if (Settings.System.canWrite(context)) {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, newLevel)
                        PhaseResult.Success("Brightness decreased")
                    } else {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        PhaseResult.Success("Please grant write settings permission for brightness")
                    }
                }

                "AIRPLANE_ON", "AIRPLANE_OFF" -> openAirplaneSettings()
                "BATTERY_SAVER_ON" -> openBatterySettings()
                "HOTSPOT_ON", "HOTSPOT_OFF" -> {
                    val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    PhaseResult.Success("Tethering & Hotspot settings opened")
                }

                "BATTERY_STATUS" -> {
                    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    if (level >= 0) {
                        PhaseResult.Success("Battery is at $level%")
                    } else {
                        openBatterySettings()
                    }
                }

                "SCREENSHOT" -> {
                    val accessibility = VoxynAccessibilityService.instance
                    if (accessibility != null) {
                        val success = accessibility.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                        if (success) PhaseResult.Success("Screenshot taken")
                        else PhaseResult.Failure("Screenshot action failed")
                    } else {
                        PhaseResult.Failure("Accessibility Service needed for screenshot")
                    }
                }

                "LOCK_PHONE" -> {
                    val accessibility = VoxynAccessibilityService.instance
                    if (accessibility != null) {
                        val success = accessibility.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                        if (success) PhaseResult.Success("Phone locked")
                        else PhaseResult.Failure("Lock screen action failed")
                    } else {
                        PhaseResult.Failure("Accessibility Service needed to lock phone")
                    }
                }

                else -> PhaseResult.Failure("Unsupported system action: ${step.actionType}", canFallback = false)
            }
        } catch (e: Exception) {
            VoxynLogger.e("System capability failed: ${e.message}", e, "CAPABILITY")
            PhaseResult.Failure(e.message ?: "System action failed", canFallback = true)
        }
    }

    override suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome {
        return runCatching {
            when (step.actionType) {
                "VOLUME_UP", "VOLUME_DOWN", "SET_VOLUME" -> {
                    VerificationOutcome.Unverified
                }
                "SILENT_MODE" -> {
                    if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) VerificationOutcome.Success
                    else VerificationOutcome.PartialSuccess
                }
                "VIBRATE_MODE" -> {
                    if (audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE) VerificationOutcome.Success
                    else VerificationOutcome.PartialSuccess
                }
                "NORMAL_MODE" -> {
                    if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) VerificationOutcome.Success
                    else VerificationOutcome.PartialSuccess
                }
                "BATTERY_STATUS", "FLASHLIGHT_ON", "FLASHLIGHT_OFF" -> VerificationOutcome.Unverified
                "WIFI_ON", "WIFI_OFF" -> {
                    val enabled = wifiManager?.isWifiEnabled
                    if (enabled != null) {
                        if (step.actionType == "WIFI_ON" && enabled) VerificationOutcome.Success
                        else if (step.actionType == "WIFI_OFF" && !enabled) VerificationOutcome.Success
                        else VerificationOutcome.Unverified
                    } else VerificationOutcome.Unverified
                }
                "BLUETOOTH_ON", "BLUETOOTH_OFF" -> {
                    @Suppress("DEPRECATION")
                    val bt = BluetoothAdapter.getDefaultAdapter()?.isEnabled
                    if (bt != null) {
                        if (step.actionType == "BLUETOOTH_ON" && bt) VerificationOutcome.Success
                        else if (step.actionType == "BLUETOOTH_OFF" && !bt) VerificationOutcome.Success
                        else VerificationOutcome.Unverified
                    } else VerificationOutcome.Unverified
                }
                "SCREENSHOT", "LOCK_PHONE" -> VerificationOutcome.Unverified
                else -> VerificationOutcome.Unverified
            }
        }.getOrDefault(VerificationOutcome.Unverified)
    }

    private fun openWifiSettings(): PhaseResult<String> {
        val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return PhaseResult.Success("WiFi settings opened")
    }

    private fun openBluetoothSettings(): PhaseResult<String> {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return PhaseResult.Success("Bluetooth settings opened")
    }

    private fun silentMode(): PhaseResult<String> {
        return try {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                PhaseResult.Success("Silent mode enabled")
            } else {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                PhaseResult.Success("Please grant notification policy access")
            }
        } catch (e: Exception) {
            PhaseResult.Failure("Silent mode failed: ${e.message}", canFallback = true)
        }
    }

    private fun openAirplaneSettings(): PhaseResult<String> {
        val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return PhaseResult.Success("Airplane mode settings opened")
    }

    private fun openBatterySettings(): PhaseResult<String> {
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return PhaseResult.Success("Battery settings opened")
    }
}
