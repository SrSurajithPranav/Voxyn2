package com.voxyn.gesture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.voxyn.utils.VoxynLogger

class GestureListenerService : Service() {

    companion object {
        const val CHANNEL_ID = "voxyn_gesture"
        const val NOTIF_ID   = 3004
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        VoxynLogger.i("GestureListenerService started (stub — Phase 4 builds gestures)", "GESTURE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VOXYN").setContentText("Gesture detection active")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "VOXYN Gestures", NotificationManager.IMPORTANCE_MIN)
                        .apply { setShowBadge(false) }
                )
        }
    }
}
