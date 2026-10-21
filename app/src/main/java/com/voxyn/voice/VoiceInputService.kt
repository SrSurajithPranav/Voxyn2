package com.voxyn.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.voxyn.MainActivity
import com.voxyn.engine.VoxynEngine
import com.voxyn.utils.VoxynLogger
import com.voxyn.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * VoiceInputService — listens for a spoken command through the active
 * SpeechRecognizerEngine (sherpa-onnx/Parakeet, with Vosk as a fallback) and
 * runs the engine.
 *
 * After a command the conversation stays open for [ConversationSession.windowMs]:
 * the service re-arms listening for follow-up commands without the wake phrase.
 * When the window expires it says "Standing by." and asks WakeWordService to
 * resume wake-word detection.
 *
 * TTS never speaks while the microphone is open: audio focus is released before
 * the engine speaks and re-acquired before listening again.
 */
class VoiceInputService : Service() {

    companion object {
        const val ACTION_START_LISTENING = "com.voxyn.voice.START_LISTENING"
        const val ACTION_STOP_LISTENING  = "com.voxyn.voice.STOP_LISTENING"
        const val EXTRA_SESSION_ID       = "com.voxyn.voice.SESSION_ID"
        const val CHANNEL_ID             = "voxyn_voice"
        const val NOTIFICATION_ID        = 3002

        private const val START_DELAY_MS          = 200L
        private const val REARM_DELAY_MS          = 1_000L
        private const val FIRST_COMMAND_MAX_ATTEMPTS = 2
        private const val MAX_CONSECUTIVE_FAILURES   = 2
        private const val PIPELINE_MAX_WAIT_MS    = 60_000L
    }

    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engine: VoxynEngine by inject()
    private val session: ConversationSession by inject()
    private val tts: TextToSpeechManager by inject()
    private val engineProvider: SpeechRecognizerEngineProvider by inject()
    private val parakeetModelManager: ParakeetModelManager by inject()
    private val coordinator: VoiceSessionCoordinator by inject()
    private val diagnostics: VoiceDiagnosticsManager by inject()
    private val speakerProfileRepository: com.voxyn.security.SpeakerProfileRepository by inject()

    private var activeEngine      : SpeechRecognizerEngine? = null
    private var audioManager      : AudioManager? = null
    private var audioFocusRequest : AudioFocusRequest? = null
    private var conversationWatchdog: Runnable? = null
    private var consecutiveFailures = 0
    private var currentSessionId  : String = ""
    private var wakeLock          : android.os.PowerManager.WakeLock? = null

    @Volatile private var isListening = false
    @Volatile private var stopping    = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("VOXYN — Always On"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("VOXYN — Always On"))
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "voxyn:always_listening_wakelock")?.apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24-hour safe wake lock for 24x7 listening
        }
        VoxynLogger.i("VoiceInputService created (24x7 Always-Listening Mode)", "VOICE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        when (intent?.action) {
            ACTION_STOP_LISTENING -> {
                stopping = true
                stopListeningInternal()
                stopSelf()
            }
            else -> {
                val sid = intent?.getStringExtra(EXTRA_SESSION_ID)
                currentSessionId = if (!sid.isNullOrBlank()) sid else coordinator.onWakeDetected()
                VoxynLogger.i("ACTION_START_LISTENING received (session: $currentSessionId)", "VOICE")
                mainHandler.postDelayed({ beginListening() }, START_DELAY_MS)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        cancelWatchdog()
        stopListeningInternal()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
        scope.cancel()
        VoxynLogger.i("VoiceInputService destroyed", "VOICE")
        super.onDestroy()
    }

    // ─── Listening ────────────────────────────────────────────────────

    private fun beginListening() {
        if (stopping || isListening) return
        isListening = true
        updateNotification("VOXYN — Always On")

        requestAudioFocus()

        if (currentSessionId.isBlank() || coordinator.getState() == SessionState.IDLE) {
            currentSessionId = coordinator.onWakeDetected()
        }

        // First-time setup: if Parakeet isn't downloaded yet, fetch it in the
        // background (progressed in the notification) while Vosk handles commands.
        if (!parakeetModelManager.isModelPresent() && !parakeetModelManager.downloadState.value.downloading) {
            maybeDownloadAsrModel()
        }

        val recognizerEngine = engineProvider.currentEngine()
        activeEngine = recognizerEngine
        if (!recognizerEngine.start(engineListener)) {
            engineProvider.reportEngineFailure(recognizerEngine, "Engine start returned false")
            val fallbackEngine = engineProvider.getFallbackEngine()
            activeEngine = fallbackEngine
            if (!fallbackEngine.start(engineListener)) {
                mainHandler.post {
                    if (stopping) return@post
                    isListening = false
                    activeEngine = null
                    releaseAudioFocus()
                    updateNotification("VOXYN — voice engine unavailable")
                    mainHandler.postDelayed({ if (!stopping) beginListening() }, 3_000L)
                }
                return
            }
            VoxynLogger.i("Command listening started via fallback (${fallbackEngine::class.simpleName})", "VOICE")
            return
        }
        coordinator.onListeningStarted(currentSessionId, recognizerEngine::class.simpleName ?: "Engine")
        VoxynLogger.i("Always-listening active (${recognizerEngine::class.simpleName})", "VOICE")
    }

    private val engineListener: SpeechRecognizerEngine.Listener = object : SpeechRecognizerEngine.Listener {
        override fun onSpeechDetected() {
            // Keep completely silent & invisible: do not reveal or change notification until verified owner
        }

        override fun onPartial(text: String) {
            // Keep completely silent & invisible: do not reveal or change notification until verified owner
        }

        override fun onResult(text: String, audioDurationMs: Long, inferenceMs: Long, audioSamples: ShortArray?) {
            mainHandler.post {
                if (stopping) return@post
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                val isMediaPlaying = audioManager?.isMusicActive == true
                val gateOutcome = coordinator.onSpeechResult(currentSessionId, text, audioDurationMs, audioSamples, isMediaPlaying)
                val profiles = speakerProfileRepository.getAllProfiles()
                val isAuthorized = when (gateOutcome) {
                    is com.voxyn.security.GateResult.Authorized -> {
                        // If profiles are enrolled, ONLY respond if the speaker is the owner!
                        if (profiles.isNotEmpty()) gateOutcome.isOwner else true
                    }
                    else -> false
                }

                if (isAuthorized) {
                    handleResult(text)
                } else {
                    VoxynLogger.w("Voice command rejected or unverified speaker — silently dropping (no sound, no display)", "VOICE")
                    // If voice not recognized, won't say or show anything!
                    coordinator.onSessionEnded()
                    currentSessionId = ""
                    isListening = false
                    activeEngine?.stop()
                    activeEngine = null
                    releaseAudioFocus()
                    mainHandler.postDelayed({ if (!stopping) beginListening() }, 300L)
                }
            }
        }

        override fun onNoSpeech() {
            mainHandler.post {
                if (stopping) return@post
                handleSilence("no-speech")
            }
        }

        override fun onError(message: String) {
            val listenerInstance = this
            VoxynLogger.w("Command STT error: $message", "VOICE")
            mainHandler.post {
                if (stopping) return@post
                val current = activeEngine
                if (current is SherpaParakeetRecognizer) {
                    engineProvider.reportEngineFailure(current, message)
                    current.stop()
                    val fallback = engineProvider.getFallbackEngine()
                    activeEngine = fallback
                    VoxynLogger.i("Attempting in-session fallback to VoskCommandRecognizer after error: $message", "VOICE")
                    if (fallback.start(listenerInstance)) {
                        return@post
                    }
                }
                handleSilence("error")
            }
        }
    }

    private fun maybeDownloadAsrModel() {
        scope.launch {
            // Restore from Downloads / shared storage first — no network needed.
            if (parakeetModelManager.ensureAvailable()) {
                VoxynLogger.i("ASR Parakeet model restored — using offline STT now", "VOICE")
                return@launch
            }
            parakeetModelManager.download { progress ->
                val pct = (progress * 100).toInt()
                VoxynLogger.i("ASR model download $pct%", "VOICE")
                mainHandler.post {
                    if (!stopping) updateNotification("VOXYN — downloading speech model $pct% (first time only)")
                }
            }
        }
    }

    private fun handleResult(text: String) {
        if (stopping) return
        isListening = false
        activeEngine?.stop()
        activeEngine = null
        releaseAudioFocus()
        cancelWatchdog()
        consecutiveFailures = 0
        updateNotification("Processing: \"$text\"...")

        VoxynLogger.i("Executing command: \"$text\"", "VOICE")
        session.addTurn(ConversationTurn(userInput = text, action = "", response = ""))
        engine.process(text)
        waitForEngineThenContinue()
    }

    private fun waitForEngineThenContinue() {
        scope.launch {
            val deadline = System.currentTimeMillis() + PIPELINE_MAX_WAIT_MS
            while (System.currentTimeMillis() < deadline && (engine.isProcessing() || tts.isSpeaking())) {
                delay(200L)
            }
            mainHandler.post {
                if (stopping) return@post
                coordinator.onSessionEnded()
                currentSessionId = ""
                VoxynLogger.i("Command execution complete — resuming continuous listening", "VOICE")
                updateNotification("VOXYN — Always On")
                beginListening()
            }
        }
    }

    private fun handleSilence(reason: String) {
        if (stopping) return
        isListening = false
        activeEngine?.stop()
        activeEngine = null
        releaseAudioFocus()
        mainHandler.postDelayed({ if (!stopping) beginListening() }, 100L)
    }

    private fun stopListeningInternal() {
        isListening = false
        cancelWatchdog()
        activeEngine?.stop()
        activeEngine = null
        releaseAudioFocus()
        coordinator.onSessionEnded()
        currentSessionId = ""
    }

    private fun cancelWatchdog() {
        conversationWatchdog?.let { mainHandler.removeCallbacks(it) }
        conversationWatchdog = null
    }

    private fun resumeWakeAndStop() {
        if (stopping) return
        stopping = true
        stopListeningInternal()
        stopSelf()
    }

    // ─── Audio focus ──────────────────────────────────────────────────

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
            Intent(this, VoiceInputService::class.java).apply { action = ACTION_STOP_LISTENING },
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
