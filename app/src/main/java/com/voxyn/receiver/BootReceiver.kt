package com.voxyn.receiver

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.voxyn.MainActivity
import com.voxyn.overlay.OverlayService
import com.voxyn.utils.VoxynLogger
import com.voxyn.overlay.IslandActivityRepository
import org.koin.core.context.GlobalContext

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        VoxynLogger.i("Boot complete — restarting VOXYN services", "BOOT")
        runCatching { GlobalContext.get().get<IslandActivityRepository>().reschedulePending() }
            .onFailure { VoxynLogger.w("Could not restore scheduled VOXYN activities: ${it.message}", "BOOT") }

        // Android 14+ forbids starting a microphone FGS from BOOT_COMPLETED for this app.
        // Let the user resume from a visible Activity; continuous listening remains unchanged
        // once started and still auto-starts on versions where the platform allows it.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                postResumeListeningNotification(context)
            } else {
                try {
                    context.startForegroundService(
                        Intent(context, com.voxyn.voice.VoiceInputService::class.java).apply {
                            action = com.voxyn.voice.VoiceInputService.ACTION_START_LISTENING
                        }
                    )
                    VoxynLogger.i("VoiceInputService started on boot (24x7 Always-Listening Mode)", "BOOT")
                } catch (e: Exception) {
                    VoxynLogger.e("Boot VoiceInputService start failed: ${e.message}", tag = "BOOT")
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

    private fun postResumeListeningNotification(context: Context) {
        val channelId = "voxyn_resume_listening"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Resume VOXYN listening", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            42,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            manager.notify(
                3004,
                Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("VOXYN is ready to listen")
                    .setContentText("Tap to resume continuous voice listening")
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .build()
            )
            VoxynLogger.i("Posted user action to resume microphone service after boot", "BOOT")
        } catch (e: SecurityException) {
            VoxynLogger.w("Resume notification unavailable; open VOXYN to resume listening: ${e.message}", "BOOT")
        }
    }
}
