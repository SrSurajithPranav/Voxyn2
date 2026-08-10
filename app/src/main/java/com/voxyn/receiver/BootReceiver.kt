package com.voxyn.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.voxyn.overlay.OverlayService
import com.voxyn.utils.VoxynLogger
import com.voxyn.wake.WakeWordService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        VoxynLogger.i("Boot complete — restarting VOXYN services", "BOOT")

        // Restart the always-on wake service (BOOT_COMPLETED is an allowed FGS start)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            try {
                context.startForegroundService(
                    Intent(context, WakeWordService::class.java).apply {
                        action = WakeWordService.ACTION_START
                    }
                )
                VoxynLogger.i("WakeWordService started on boot", "BOOT")
            } catch (e: Exception) {
                VoxynLogger.e("Boot WakeWordService start failed: ${e.message}", tag = "BOOT")
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
}
