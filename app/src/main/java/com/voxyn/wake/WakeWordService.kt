package com.voxyn.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.TextToSpeechManager
import com.voxyn.voice.VoiceInputService
<<<<<<< HEAD
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
=======
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.vosk.Model
import org.vosk.Recognizer
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)

/** Low-power, offline wake listener. It never sends text directly to the engine. */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START = "com.voxyn.wake.START"
        const val ACTION_STOP = "com.voxyn.wake.STOP"
        private const val CHANNEL_ID = "voxyn_wake"
        private const val NOTIFICATION_ID = 3001
        private const val WAKE_PHRASE = "hey voxyn"
    }

<<<<<<< HEAD
    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var triggered = false
=======
    private val scope       = CoroutineScope(Dispatchers.Default + Job())
    private val modelManager: VoskModelManager by inject()
    private val tts: TextToSpeechManager by inject()
    private var audioRecord : AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var detectJob   : Job? = null
    private var isListening = false
    private var isWaiting   = false
    private val handler     = Handler(Looper.getMainLooper())
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)

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

<<<<<<< HEAD
    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VOXYN wake word", NotificationManager.IMPORTANCE_LOW))
=======
        detectJob?.cancel()
        detectJob = scope.launch {
            modelManager.ensureModelAvailable()
            val state = modelManager.modelState.first {
                it is VoskModelState.Ready || it is VoskModelState.Error
            }

            val modelPath = when (state) {
                is VoskModelState.Ready -> state.modelPath
                is VoskModelState.Error -> {
                    VoxynLogger.e("Wake model unavailable: ${state.message}", tag = "WAKE")
                    isListening = false
                    updateNotification("Offline wake model failed")
                    return@launch
                }
                else -> return@launch
            }

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

            val localModel = try {
                Model(modelPath).also { model = it }
            } catch (e: Exception) {
                VoxynLogger.e("Failed to load Vosk model: ${e.message}", tag = "WAKE")
                isListening = false
                record.release()
                updateNotification("Wake model load failed")
                return@launch
            }

            val grammar = "[\"hey voxyn\", \"voxyn\", \"jarvis\", \"[unk]\"]"
            val localRecognizer = try {
                Recognizer(localModel, SAMPLE_RATE.toFloat(), grammar).also { recognizer = it }
            } catch (e: Exception) {
                VoxynLogger.e("Failed to init recognizer: ${e.message}", tag = "WAKE")
                isListening = false
                try { localModel.close() } catch (_: Exception) {}
                model = null
                record.release()
                return@launch
            }

            record.startRecording()
            val buffer = ByteArray(bufferSize)

            while (isListening) {
                val read = record.read(buffer, 0, bufferSize)
                if (read > 0) {
                    val accepted = localRecognizer.acceptWaveForm(buffer, read)
                    val resultText = if (accepted) localRecognizer.result else localRecognizer.partialResult
                    val phrase = parseRecognizedText(resultText)
                    if (phrase.contains("hey voxyn") || phrase.contains("jarvis") || phrase == "voxyn") {
                        VoxynLogger.i("Wake phrase detected: $phrase", "WAKE")
                        tts.stop()
                        isListening = false
                        isWaiting = true
                        triggerVoiceInput()
                    }
                }
                delay(10)
            }
            try { record.stop(); record.release() } catch (_: Exception) {}
            audioRecord = null
            try { localRecognizer.close() } catch (_: Exception) {}
            recognizer = null
            try { localModel.close() } catch (_: Exception) {}
            model = null
        }
    }

    private fun stopDetection() {
        isListening = false
        detectJob?.cancel()
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
    }

    private fun triggerVoiceInput() {
        updateNotification("Listening to your command...")
        tts.stop()
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

    private fun parseRecognizedText(resultJson: String): String {
        if (resultJson.isBlank()) return ""
        return runCatching {
            val obj = JSONObject(resultJson)
            obj.optString("text", obj.optString("partial", "")).trim().lowercase()
        }.getOrDefault("")
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
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
    }
}
