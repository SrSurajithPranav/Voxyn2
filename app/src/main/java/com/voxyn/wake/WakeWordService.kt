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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * WakeWordService
 * Always-on foreground mic service.
 * No wake phrase needed. Detects sustained speech energy → fires VoiceInputService.
 * After VoiceInputService finishes → ACTION_RESUME → resumes detection immediately.
 * Loop runs forever: detect → fire → resume → detect → fire → resume ...
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_START  = "com.voxyn.wake.START"
        const val ACTION_STOP   = "com.voxyn.wake.STOP"
        const val ACTION_RESUME = "com.voxyn.wake.RESUME"   // sent by VoiceInputService when done
        const val CHANNEL_ID    = "voxyn_wake"
        const val NOTIFICATION_ID = 3001
        private const val SAMPLE_RATE = 16000
    }

    private val scope        = CoroutineScope(Dispatchers.Default + Job())
    private var audioRecord  : AudioRecord? = null
    private var detectJob    : Job? = null
    private var isListening  = false
    private var isWaiting    = false   // true while VoiceInputService is active
    private val mainHandler  = Handler(Looper.getMainLooper())

    // ─── Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // CRITICAL: startForeground must be called immediately in onCreate
        startForeground(NOTIFICATION_ID, buildNotification("VOXYN — ready, speak anytime"))
        VoxynLogger.i("WakeWordService created", "WAKE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP   -> { stopDetection(); stopSelf() }
            ACTION_RESUME -> {
                // VoiceInputService finished — wait 1s then resume detection
                isWaiting = false
                mainHandler.postDelayed({
                    if (!isListening && !isWaiting) startDetection()
                }, 1000L)
                updateNotification("VOXYN — ready, speak anytime")
                VoxynLogger.i("Resuming detection after voice input", "WAKE")
            }
            else -> {
                // ACTION_START or null — start if not already running
                if (!isListening && !isWaiting) startDetection()
            }
        }
        return START_STICKY   // Android restarts this if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopDetection()
        scope.cancel()
        VoxynLogger.i("WakeWordService destroyed", "WAKE")
        super.onDestroy()
    }

    // ─── Detection loop ───────────────────────────────────────────────

    private fun startDetection() {
        if (isListening || isWaiting) return
        isListening = true
        updateNotification("VOXYN — listening...")

        detectJob?.cancel()
        detectJob = scope.launch {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
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
                isListening = false
                return@launch
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                VoxynLogger.e("AudioRecord not initialized", tag = "WAKE")
                record.release()
                isListening = false
                return@launch
            }

            record.startRecording()
            val buffer   = ShortArray(bufferSize)
            val detector = SpeechEnergyDetector()

            VoxynLogger.i("Energy detection loop running", "WAKE")

            while (isActive && isListening) {
                val read = record.read(buffer, 0, bufferSize)
                if (read > 0) {
                    val amplitude = rms(buffer, read)
                    if (detector.isSpeech(amplitude)) {
                        VoxynLogger.i("Speech detected (amplitude=$amplitude) — firing voice input", "WAKE")
                        isListening = false
                        isWaiting   = true
                        record.stop()
                        record.release()
                        audioRecord = null
                        triggerVoiceInput()
                        return@launch
                    }
                }
                delay(20L)   // 50Hz polling — easy on battery
            }

            try { record.stop() }    catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
            audioRecord = null
        }
    }

    private fun stopDetection() {
        isListening = false
        detectJob?.cancel()
        try { audioRecord?.stop() }    catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        VoxynLogger.i("Detection stopped", "WAKE")
    }

    private fun triggerVoiceInput() {
        updateNotification("VOXYN — listening to your command...")
        try {
            startForegroundService(
                Intent(this, VoiceInputService::class.java).apply {
                    action = VoiceInputService.ACTION_START_LISTENING
                }
            )
        } catch (e: Exception) {
            VoxynLogger.e("Failed to start VoiceInputService: ${e.message}", tag = "WAKE")
            // If voice fails, resume detection after 2s
            isWaiting = false
            mainHandler.postDelayed({ startDetection() }, 2000L)
        }
    }

    // ─── Audio math ───────────────────────────────────────────────────

    private fun rms(data: ShortArray, size: Int): Float {
        var sum = 0.0
        for (i in 0 until size) sum += data[i].toDouble() * data[i]
        return sqrt(sum / size).toFloat()
    }

    // ─── Notification ─────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VOXYN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPi)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopPi).build()
            )
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "VOXYN Assistant",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
    }
}

// ─── Speech energy detector ───────────────────────────────────────────────────

class SpeechEnergyDetector {
    private var baseline    = -1f
    private var calibCount  = 0
    private var consecHigh  = 0

    fun isSpeech(amplitude: Float): Boolean {
        // First 25 frames (~0.5s): calibrate ambient noise floor
        if (calibCount < 25) {
            calibCount++
            baseline = if (baseline < 0f) amplitude
                       else baseline * 0.85f + amplitude * 0.15f
            return false
        }

        // Dynamic threshold: 3x noise floor, min 500
        val threshold = (baseline * 3.0f).coerceAtLeast(500f)

        consecHigh = if (amplitude > threshold) consecHigh + 1
                     else (consecHigh - 1).coerceAtLeast(0)

        // Need ~0.3s sustained speech to trigger (avoids door slams, clicks)
        return if (consecHigh >= 8) {
            consecHigh = 0
            true
        } else false
    }

    fun reset() {
        baseline   = -1f
        calibCount = 0
        consecHigh = 0
    }
}
