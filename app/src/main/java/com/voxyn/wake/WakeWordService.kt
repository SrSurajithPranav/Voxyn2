package com.voxyn.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.VoiceInputService
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/** Low-power, offline wake listener. It never sends text directly to the engine. */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START = "com.voxyn.wake.START"
        const val ACTION_STOP = "com.voxyn.wake.STOP"
        private const val CHANNEL_ID = "voxyn_wake"
        private const val NOTIFICATION_ID = 3001
        private const val WAKE_PHRASE = "hey voxyn"
    }

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var triggered = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Say ‘Hey Voxyn’"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> if (speechService == null && model == null) loadModel()
        }
        return START_STICKY
    }

    private fun loadModel() {
        StorageService.unpack(
            this,
            "model-en-us",
            "voxyn-wake-model",
            { loaded ->
                if (isDestroyed) return@unpack
                model = loaded
                val recognizer = Recognizer(loaded, 16_000f, "[\"$WAKE_PHRASE\", \"[unk]\"]")
                speechService = SpeechService(recognizer, 16_000f).also { it.startListening(this) }
                VoxynLogger.i("Offline wake listener started", "WAKE")
            },
            { error ->
                VoxynLogger.e("Wake model unavailable: ${error.message}", tag = "WAKE")
                stopSelf()
            }
        )
    }

    override fun onPartialResult(hypothesis: String?) = inspect(hypothesis)
    override fun onResult(hypothesis: String?) = inspect(hypothesis)
    override fun onFinalResult(hypothesis: String?) = inspect(hypothesis)

    private fun inspect(hypothesis: String?) {
        if (triggered || !hypothesis.orEmpty().contains(WAKE_PHRASE, ignoreCase = true)) return
        triggered = true
        VoxynLogger.i("Wake phrase detected", "WAKE")
        speechService?.stop()
        startForegroundService(Intent(this, VoiceInputService::class.java).apply {
            action = VoiceInputService.ACTION_START_LISTENING
        })
        stopSelf()
    }

    override fun onError(exception: Exception?) {
        VoxynLogger.e("Wake listener error: ${exception?.message}", tag = "WAKE")
        stopSelf()
    }

    override fun onTimeout() = Unit
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("VOXYN")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .build()

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VOXYN wake word", NotificationManager.IMPORTANCE_LOW))
    }
}
