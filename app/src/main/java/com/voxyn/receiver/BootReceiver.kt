package com.voxyn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.voxyn.overlay.OverlayService
import com.voxyn.utils.VoxynLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        VoxynLogger.i("Boot complete — restarting VOXYN services", "BOOT")

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
