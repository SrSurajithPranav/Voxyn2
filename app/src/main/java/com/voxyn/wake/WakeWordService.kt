package com.voxyn.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.voxyn.MainActivity
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.VoiceInputService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class WakeWordService : Service() {

    companion object {
        const val CHANNEL_ID      = "voxyn_wake"
        const val NOTIFICATION_ID = 3001
        const val ACTION_START    = "com.voxyn.wake.START"
        const val ACTION_STOP     = "com.voxyn.wake.STOP"
        const val ACTION_RESUME   = "com.voxyn.wake.RESUME"
        const val ACTION_TRIGGER  = "com.voxyn.wake.TRIGGER_NOW"
        const val SAMPLE_RATE     = 16000
    }

    private val scope       = CoroutineScope(Dispatchers.Default + Job())
    private var audioRecord : AudioRecord? = null
    private var detectJob   : Job? = null
    private var isListening = false
    private var isWaiting   = false
    private val handler     = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("VOXYN ready — speak to activate"))
        VoxynLogger.i("WakeWordService created", "WAKE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START   -> if (!isListening) startDetection()
            ACTION_STOP    -> { stopDetection(); stopSelf() }
            ACTION_RESUME  -> { isWaiting = false; handler.postDelayed({ startDetection() }, 1500L) }
            ACTION_TRIGGER -> triggerVoiceInput()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopDetection()
        scope.cancel()
        super.onDestroy()
    }

    private fun startDetection() {
        if (isListening || isWaiting) return
        isListening = true
        updateNotification("Waiting for you to speak...")

        detectJob?.cancel()
        detectJob = scope.launch {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 4
                ).also { audioRecord = it }
            } catch (e: Exception) {
                VoxynLogger.e("AudioRecord failed: ${e.message}", tag = "WAKE")
                isListening = false; return@launch
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                isListening = false; record.release(); return@launch
            }

            record.startRecording()
            val buffer   = ShortArray(bufferSize)
            val detector = SpeechEnergyDetector()

            while (isListening) {
                val read = record.read(buffer, 0, bufferSize)
                if (read > 0 && detector.isSpeech(rms(buffer, read))) {
                    isListening = false
                    isWaiting   = true
                    triggerVoiceInput()
                }
                delay(20)
            }
            try { record.stop(); record.release() } catch (_: Exception) {}
            audioRecord = null
        }
    }

    private fun stopDetection() {
        isListening = false
        detectJob?.cancel()
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun triggerVoiceInput() {
        updateNotification("Listening to your command...")
        try {
            startForegroundService(
                Intent(this, VoiceInputService::class.java).apply {
                    action = VoiceInputService.ACTION_START_LISTENING
                }
            )
        } catch (e: Exception) {
            VoxynLogger.e("Failed to start VoiceInputService: ${e.message}", tag = "WAKE")
            isWaiting = false
            handler.postDelayed({ startDetection() }, 2000L)
        }
    }

    private fun rms(data: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) sum += data[i].toDouble() * data[i]
        return sqrt(sum / size).toFloat()
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

class SpeechEnergyDetector {
    private var baseline   = -1f
    private var consecHigh = 0
    private var calibCount = 0

    fun isSpeech(amplitude: Float): Boolean {
        if (calibCount < 20) {
            calibCount++
            baseline = if (baseline < 0f) amplitude else (baseline * 0.85f + amplitude * 0.15f)
            return false
        }
        val threshold = (baseline * 3.0f).coerceAtLeast(500f)
        consecHigh = if (amplitude > threshold) consecHigh + 1 else (consecHigh - 1).coerceAtLeast(0)
        return if (consecHigh >= 8) { consecHigh = 0; true } else false
    }
}
