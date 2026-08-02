package com.voxyn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxyn.utils.VoxynLogger
import com.voxyn.wake.WakeWordService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        VoxynLogger.i("Boot complete — restarting VOXYN", "BOOT")
        try {
            context.startForegroundService(
                Intent(context, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_START
                }
            )
        } catch (e: Exception) {
            VoxynLogger.e("Boot start failed: ${e.message}", tag = "BOOT")
        }
    }
}
