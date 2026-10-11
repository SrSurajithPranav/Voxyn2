package com.voxyn.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.voxyn.overlay.OverlayService
import com.voxyn.utils.VoxynLogger
import com.voxyn.wake.WakeWordService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        VoxynLogger.i("BOOT_RECEIVED action=${intent.action}", "BOOT")

        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val decision = VoiceStartupPolicy.bootMicrophoneDecision(
            microphonePermissionGranted = micGranted,
            deviceSdk = Build.VERSION.SDK_INT,
            targetSdk = context.applicationInfo.targetSdkVersion
        )
        when (decision) {
            BootMicrophoneDecision.MICROPHONE_PERMISSION_REQUIRED -> {
                VoxynLogger.w("LISTENING_NOT_ELIGIBLE reason=MICROPHONE_PERMISSION_REQUIRED", "BOOT")
            }
            BootMicrophoneDecision.USER_MUST_OPEN_APP -> {
                context.getSharedPreferences(STARTUP_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_BOOT_REQUIRES_VISIBLE_START, true)
                    .apply()
                VoxynLogger.i(
                    "LISTENING_NOT_STARTED reason=BOOT_MICROPHONE_FGS_RESTRICTED next=OPEN_VOXYN_OR_USER_INITIATED_START",
                    "BOOT"
                )
            }
            BootMicrophoneDecision.START_ALLOWED -> {
                VoxynLogger.i("LISTENING_ELIGIBLE source=BOOT", "BOOT")
                try {
                    context.startForegroundService(
                        Intent(context, WakeWordService::class.java).apply {
                            action = WakeWordService.ACTION_START
                        }
                    )
                    VoxynLogger.i("MICROPHONE_SERVICE_START_REQUESTED source=BOOT", "BOOT")
                } catch (error: Exception) {
                    val category = VoiceStartupPolicy.startupFailureCategory(error.javaClass.simpleName)
                    VoxynLogger.e(
                        "$category source=BOOT exception=${error.javaClass.simpleName}; open VOXYN to start listening",
                        tag = "BOOT"
                    )
                }
            }
        }

        if (Settings.canDrawOverlays(context)) {
            try {
                context.startForegroundService(Intent(context, OverlayService::class.java))
                VoxynLogger.i("OverlayService started on boot", "BOOT")
            } catch (e: Exception) {
                VoxynLogger.e("Boot OverlayService start failed: ${e.message}", tag = "BOOT")
            }
        }
    }

    companion object {
        const val STARTUP_PREFS = "voice_startup"
        const val KEY_BOOT_REQUIRES_VISIBLE_START = "boot_requires_visible_start"
    }
}
