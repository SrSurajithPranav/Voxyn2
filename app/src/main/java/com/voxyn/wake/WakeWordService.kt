package com.voxyn.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.voxyn.MainActivity
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.VoiceInputService

class WakeWordService : Service() {

    companion object {
        const val CHANNEL_ID      = "voxyn_wake"
        const val NOTIFICATION_ID = 3001
        const val ACTION_START    = "com.voxyn.wake.START"
        const val ACTION_STOP     = "com.voxyn.wake.STOP"
        const val ACTION_RESUME   = "com.voxyn.wake.RESUME"
        const val ACTION_TRIGGER  = "com.voxyn.wake.TRIGGER_NOW"
        private const val RESTART_DELAY_MS = 800L
        private const val BATTERY_PAUSE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isListeningLoopActive = false
    private var isPausedForBattery = false

    private val screenOffRunnable = Runnable {
        VoxynLogger.i("Screen off > 5 mins: Pausing voice listening for battery optimization", "WAKE")
        isPausedForBattery = true
        updateNotification("VOXYN paused (Screen off)")
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    VoxynLogger.d("Screen OFF detected — scheduling battery pause in 5 mins", "WAKE")
                    handler.postDelayed(screenOffRunnable, BATTERY_PAUSE_TIMEOUT_MS)
                }
                Intent.ACTION_SCREEN_ON -> {
                    VoxynLogger.d("Screen ON detected — cancelling battery pause", "WAKE")
                    handler.removeCallbacks(screenOffRunnable)
                    if (isPausedForBattery) {
                        isPausedForBattery = false
                        VoxynLogger.i("Screen turned ON — resuming continuous listening", "WAKE")
                        updateNotification("VOXYN listening active")
                        startVoiceInput()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("VOXYN continuous listening active"))
        registerScreenReceiver()
        VoxynLogger.i("WakeWordService created — continuous listening enabled", "WAKE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isListeningLoopActive = true
                isPausedForBattery = false
                startVoiceInput()
            }
            ACTION_STOP -> {
                isListeningLoopActive = false
                handler.removeCallbacksAndMessages(null)
                stopSelf()
            }
            ACTION_RESUME -> {
                if (isListeningLoopActive && !isPausedForBattery) {
                    handler.postDelayed({ startVoiceInput() }, RESTART_DELAY_MS)
                }
            }
            ACTION_TRIGGER -> {
                isPausedForBattery = false
                startVoiceInput()
            }
            else -> {
                if (!isListeningLoopActive) {
                    isListeningLoopActive = true
                    startVoiceInput()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isListeningLoopActive = false
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
        VoxynLogger.i("WakeWordService destroyed", "WAKE")
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun startVoiceInput() {
        if (!isListeningLoopActive || isPausedForBattery) return
        updateNotification("Listening...")
        try {
            startForegroundService(
                Intent(this, VoiceInputService::class.java).apply {
                    action = VoiceInputService.ACTION_START_LISTENING
                }
            )
        } catch (e: Exception) {
            VoxynLogger.e("Failed to start VoiceInputService: ${e.message}", tag = "WAKE")
            handler.postDelayed({ startVoiceInput() }, 2000L)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VOXYN").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .setOngoing(true).setShowWhen(false).build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "VOXYN Assistant", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false); setSound(null, null); enableVibration(false) }
                )
        }
    }
}
