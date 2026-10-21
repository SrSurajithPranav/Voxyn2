package com.voxyn.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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

    private var activeEngine      : SpeechRecognizerEngine? = null
    private var audioManager      : AudioManager? = null
    private var audioFocusRequest : AudioFocusRequest? = null
    private var conversationWatchdog: Runnable? = null
    private var consecutiveFailures = 0
    private var currentSessionId  : String = ""

    @Volatile private var isListening = false
    @Volatile private var stopping    = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("VOXYN — speak your command"))
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        VoxynLogger.i("VoiceInputService created", "VOICE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                val sid = intent.getStringExtra(EXTRA_SESSION_ID)
                currentSessionId = if (!sid.isNullOrBlank()) sid else coordinator.onWakeDetected()
                VoxynLogger.i("ACTION_START_LISTENING received (session: $currentSessionId)", "VOICE")
                mainHandler.postDelayed({ beginListening() }, START_DELAY_MS)
            }
            ACTION_STOP_LISTENING -> resumeWakeAndStop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        cancelWatchdog()
        activeEngine?.stop()
        activeEngine = null
        releaseAudioFocus()
        scope.cancel()
        VoxynLogger.i("VoiceInputService destroyed", "VOICE")
        super.onDestroy()
    }

    // ─── Listening ────────────────────────────────────────────────────

    private fun beginListening() {
        if (stopping || isListening) return
        isListening = true
        updateNotification("VOXYN — speak your command")

        requestAudioFocus()

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
                    updateNotification("VOXYN — voice unavailable")
                    tts.speak("My speech engine is not ready yet.")
                    mainHandler.postDelayed({ resumeWakeAndStop() }, 1_500L)
                }
                return
            }
            VoxynLogger.i("Command listening started via fallback (${fallbackEngine::class.simpleName})", "VOICE")
            return
        }
        coordinator.onListeningStarted(currentSessionId, recognizerEngine::class.simpleName ?: "Engine")
        VoxynLogger.i("Command listening started (${recognizerEngine::class.simpleName})", "VOICE")
    }

    private val engineListener: SpeechRecognizerEngine.Listener = object : SpeechRecognizerEngine.Listener {
        override fun onSpeechDetected() {
            mainHandler.post {
                if (!stopping) updateNotification("VOXYN — listening...")
            }
        }

        override fun onPartial(text: String) {
            if (text.isBlank()) return
            mainHandler.post {
                if (!stopping) updateNotification("VOXYN — \"$text\"...")
            }
        }

        override fun onResult(text: String, audioDurationMs: Long, inferenceMs: Long, audioSamples: ShortArray?) {
            mainHandler.post {
                if (stopping) return@post
                val gateOutcome = coordinator.onSpeechResult(currentSessionId, text, audioDurationMs, audioSamples)
                when (gateOutcome) {
                    is com.voxyn.security.GateResult.Authorized -> {
                        handleResult(text)
                    }
                    is com.voxyn.security.GateResult.Denied -> {
                        VoxynLogger.w("Voice command denied: ${gateOutcome.reason}", "VOICE")
                        updateNotification("VOXYN — voice not recognized")
                        tts.speak(gateOutcome.userFeedback) {
                            endSessionWithStandingBy()
                        }
                    }
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
        cancelWatchdog()
        consecutiveFailures = 0
        updateNotification("Processing...")

        VoxynLogger.i("Command: \"$text\"", "VOICE")
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
                if (session.isWithinWindow()) {
                    VoxynLogger.i("Conversation window open — listening for follow-up", "VOICE")
                    updateNotification("VOXYN — go on, I'm listening")
                    startWindowWatchdog()
                    beginListening()
                } else {
                    VoxynLogger.i("Conversation window expired — standing by", "VOICE")
                    endSessionWithStandingBy()
                }
            }
        }
    }

    private fun handleSilence(reason: String) {
        if (stopping) return
        isListening = false
        activeEngine?.stop()
        activeEngine = null
        if (session.turns.isEmpty()) {
            // First listen after the wake phrase — retry once before returning to IDLE
            // so a slow start or a VAD hiccup doesn't end the conversation prematurely.
            retryOrGiveUp(reason, REARM_DELAY_MS)
        } else if (session.isWithinWindow()) {
            // Follow-up listen: re-arm once; the second consecutive failure ends the session
            retryOrGiveUp(reason, REARM_DELAY_MS)
        } else {
            VoxynLogger.i("Silence after window — standing by", "VOICE")
            endSessionWithStandingBy()
        }
    }

    /**
     * Counts a failed listen attempt. Retries once with a backoff, then gives up:
     * a first-command failure returns to wake detection, a follow-up failure
     * ends the conversation with "Standing by.".
     */
    private fun retryOrGiveUp(reason: String, backoffMs: Long) {
        if (stopping) return
        consecutiveFailures++
        val maxAttempts = if (session.turns.isEmpty()) {
            FIRST_COMMAND_MAX_ATTEMPTS
        } else {
            MAX_CONSECUTIVE_FAILURES
        }

        if (consecutiveFailures >= maxAttempts) {
            VoxynLogger.w("Giving up after $consecutiveFailures failed listens ($reason)", "VOICE")
            if (session.turns.isEmpty()) {
                VoxynLogger.i("No command heard after $maxAttempts attempts — back to wake detection", "VOICE")
                updateNotification("VOXYN — say Hey VOXYN")
                resumeWakeAndStop()
            } else {
                endSessionWithStandingBy()
            }
            return
        }

        VoxynLogger.w("No speech — retrying in ${backoffMs}ms (failure #$consecutiveFailures)", "VOICE")
        updateNotification("VOXYN — retrying...")
        mainHandler.postDelayed({ if (!stopping) beginListening() }, backoffMs)
    }

    private fun endSessionWithStandingBy() {
        if (stopping) return
        cancelWatchdog()
        session.end()
        coordinator.onSessionEnded()
        updateNotification("VOXYN — say Hey VOXYN")
        // Do not resume the wake recognizer until the spoken status is finished,
        // otherwise it can hear VOXYN's own voice as a wake phrase.
        tts.speak("Standing by.") { resumeWakeAndStop() }
    }

    private fun startWindowWatchdog() {
        cancelWatchdog()
        val runnable = Runnable {
            VoxynLogger.i("30s conversation window elapsed — standing by", "VOICE")
            if (session.isActive) {
                endSessionWithStandingBy()
            }
        }
        conversationWatchdog = runnable
        mainHandler.postDelayed(runnable, session.windowMs)
    }

    private fun cancelWatchdog() {
        conversationWatchdog?.let { mainHandler.removeCallbacks(it) }
        conversationWatchdog = null
    }

    private fun resumeWakeAndStop() {
        if (stopping) return
        stopping = true
        cancelWatchdog()
        activeEngine?.stop()
        activeEngine = null
        releaseAudioFocus()
        try {
            startService(
                Intent(this, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_RESUME
                }
            )
            VoxynLogger.i("WakeWordService resumed", "VOICE")
        } catch (e: Exception) {
            VoxynLogger.e("Failed to resume WakeWordService: ${e.message}", tag = "VOICE")
        }
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
