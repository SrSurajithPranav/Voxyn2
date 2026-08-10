package com.voxyn.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.voxyn.MainActivity
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.ConversationSession
import com.voxyn.voice.ParakeetModelManager
import com.voxyn.voice.TextToSpeechManager
import com.voxyn.voice.VoiceInputService
import com.voxyn.voice.VoskModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * WakeWordService — always-on microphone foreground service.
 *
 * Uses Vosk (offline, from assets/model-en-us) to listen continuously for
 * the "Hey VOXYN" wake phrase. When detected it speaks an activation line,
 * then hands the mic to VoiceInputService for the actual command.
 * VoiceInputService sends ACTION_RESUME when it is done, which restarts
 * Vosk detection. START_STICKY so Android restarts the service if killed.
 */
class WakeWordService : Service() {

    companion object {
        const val ACTION_START           = "com.voxyn.wake.START"
        const val ACTION_STOP            = "com.voxyn.wake.STOP"
        const val ACTION_RESUME          = "com.voxyn.wake.RESUME"
        const val ACTION_START_LISTENING = "com.voxyn.wake.START_LISTENING"
        const val CHANNEL_ID             = "voxyn_wake"
        const val NOTIFICATION_ID        = 3001

        private const val SAMPLE_RATE     = 16000
        private const val MODEL_RETRY_MS  = 10_000L
        private const val TTS_MAX_WAIT_MS = 5_000L

        private const val GRAMMAR = """["hey voxyn", "hi voxyn", "okay voxyn", "voxyn", "[unk]"]"""

        private val WAKE_VARIANTS = listOf("hey voxyn", "hi voxyn", "okay voxyn", "voxyn")
        private val WAKE_TRIGGER_WORDS = listOf("hey", "hi", "okay", "voxyn")
        private val ACTIVATION_RESPONSES = listOf("Yes?", "Listening.", "What do you need?", "Go ahead.", "I'm here.")
    }

    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tts: TextToSpeechManager by inject()
    private val session: ConversationSession by inject()
    private val modelProvider: VoskModelProvider by inject()
    private val parakeetModelManager: ParakeetModelManager by inject()

    private var model        : Model? = null
    private var recognizer   : Recognizer? = null
    private var audioRecord  : AudioRecord? = null
    private var detectJob    : Job? = null
    private var audioManager : AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile private var isDetecting = false
    @Volatile private var stopped     = false
    @Volatile private var permissionBlocked = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("VOXYN — say Hey VOXYN"))
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        VoxynLogger.i("WakeWordService created", "WAKE")
        loadModelAndStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopped = true
                stopAll()
                stopSelf()
            }
            ACTION_START_LISTENING -> {
                // In-app mic button — skip the wake phrase, go straight to the command
                stopped = false
                session.end()
                stopDetection()
                startVoiceInput()
            }
            ACTION_RESUME -> {
                // VoiceInputService finished — resume wake word detection
                stopped = false
                VoxynLogger.i("ACTION_RESUME — restarting Vosk detection", "WAKE")
                startDetection()
            }
            else -> {
                // ACTION_START or null (START_STICKY restart)
                stopped = false
                startDetection()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAll()
        scope.cancel()
        VoxynLogger.i("WakeWordService destroyed", "WAKE")
        super.onDestroy()
    }

    // ─── Model loading ────────────────────────────────────────────────

    private fun loadModelAndStart() {
        scope.launch {
            val m = modelProvider.getModel()
            if (m != null) {
                if (stopped) return@launch
                model = m
                VoxynLogger.i("Vosk model loaded", "WAKE")
                mainHandler.post { startDetection() }
            } else {
                VoxynLogger.e("Vosk model load failed", tag = "WAKE")
                updateNotification("VOXYN — wake word starting, retrying")
                if (!stopped) {
                    delay(MODEL_RETRY_MS)
                    loadModelAndStart()
                }
            }
        }
    }

    // ─── Vosk detection loop ──────────────────────────────────────────

    private fun startDetection() {
        val m = model ?: return
        if (stopped || isDetecting || session.isActive) return
        permissionBlocked = false
        isDetecting = true
        updateNotification("VOXYN — say Hey VOXYN")

        detectJob?.cancel()
        detectJob = scope.launch {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 4
                ).also { audioRecord = it }

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    VoxynLogger.e("AudioRecord not initialized", tag = "WAKE")
                    record.release()
                    audioRecord = null
                    onDetectionFailed()
                    return@launch
                }

                val rec = Recognizer(m, SAMPLE_RATE.toFloat(), GRAMMAR)
                recognizer = rec
                record.startRecording()

                val buffer = ShortArray(bufferSize)
                VoxynLogger.i("Vosk wake-word detection running", "WAKE")

                while (isActive && isDetecting && !stopped) {
                    val read = record.read(buffer, 0, bufferSize)
                    if (read > 0 && rec.acceptWaveForm(buffer, read)) {
                        val text = runCatching {
                            JSONObject(rec.result).optString("text", "").lowercase()
                        }.getOrDefault("")
                        if (text.isNotEmpty()) {
                            VoxynLogger.i("Vosk heard: \"$text\"", "WAKE")
                        }
                        if (isWakeTrigger(text)) {
                            VoxynLogger.i("Wake phrase detected — waking up", "WAKE")
                            isDetecting = false
                            releaseAudioRecord()
                            mainHandler.post { onWakeTriggered() }
                            return@launch
                        }
                    }
                    delay(20L)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                VoxynLogger.i("Vosk detection cancelled", "WAKE")
            } catch (se: SecurityException) {
                permissionBlocked = true
                VoxynLogger.e("Microphone permission missing — wake word disabled", tag = "WAKE")
                updateNotification("VOXYN — microphone permission needed. Open the app and allow it.")
            } catch (e: Exception) {
                VoxynLogger.e("Vosk detection loop failed: ${e.message}", tag = "WAKE")
                onDetectionFailed()
            } finally {
                releaseAudioRecord()
                try { recognizer?.close() } catch (_: Exception) {}
                recognizer = null
                if (isDetecting) isDetecting = false
            }
        }
    }

    /**
     * The bundled small Vosk model can't hear "voxyn" — with the wake grammar, the
     * only tokens it can ever emit are hey/hi/okay/voxyn plus [unk] for out-of-
     * vocabulary speech. Any result containing a trigger word is a wake attempt.
     */
    private fun isWakeTrigger(text: String): Boolean {
        if (WAKE_VARIANTS.any { text.contains(it) }) return true
        return WAKE_TRIGGER_WORDS.any { text.contains(it) }
    }

    private fun onDetectionFailed() {
        isDetecting = false
        if (stopped || permissionBlocked) return
        VoxynLogger.w("Detection failed — retrying in ${MODEL_RETRY_MS}ms", "WAKE")
        mainHandler.postDelayed({ startDetection() }, MODEL_RETRY_MS)
    }

    private fun onWakeTriggered() {
        session.end()
        updateNotification("VOXYN — waking up...")
        // Pre-load the command recognizer while the activation line is spoken so
        // the mic is ready from the user's very first word.
        scope.launch {
            runCatching { parakeetModelManager.getRecognizer() }
                .onSuccess { VoxynLogger.i("Parakeet recognizer pre-loaded on wake", "WAKE") }
        }
        speakActivationThenListen()
    }

    private fun speakActivationThenListen() {
        requestAudioFocus()
        val phrase = ACTIVATION_RESPONSES.random()
        VoxynLogger.i("Activation: \"$phrase\"", "WAKE")
        tts.speak(phrase)
        scope.launch {
            val deadline = System.currentTimeMillis() + TTS_MAX_WAIT_MS
            while (System.currentTimeMillis() < deadline && tts.isSpeaking()) {
                delay(100L)
            }
            releaseAudioFocus()
            startVoiceInput()
        }
    }

    private fun startVoiceInput() {
        try {
            startForegroundService(
                Intent(this, VoiceInputService::class.java).apply {
                    action = VoiceInputService.ACTION_START_LISTENING
                }
            )
            VoxynLogger.i("VoiceInputService started", "WAKE")
        } catch (e: Exception) {
            VoxynLogger.e("Failed to start VoiceInputService: ${e.message}", tag = "WAKE")
            mainHandler.postDelayed({ startDetection() }, 2_000L)
        }
    }

    private fun stopDetection() {
        isDetecting = false
        detectJob?.cancel()
        releaseAudioRecord()
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        VoxynLogger.i("Vosk detection stopped", "WAKE")
    }

    private fun stopAll() {
        stopDetection()
        releaseAudioFocus()
        try { model?.close() } catch (_: Exception) {}
        model = null
        VoxynLogger.i("All wake activity stopped", "WAKE")
    }

    // ─── Audio plumbing ───────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
            audioManager?.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    private fun releaseAudioRecord() {
        try { audioRecord?.stop() }    catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
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
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
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
