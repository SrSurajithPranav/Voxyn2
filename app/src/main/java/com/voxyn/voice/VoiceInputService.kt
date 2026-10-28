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
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.voxyn.MainActivity
import com.voxyn.core.VoxynConstants
import com.voxyn.engine.VoxynEngine
import com.voxyn.overlay.OverlayService
import com.voxyn.overlay.IslandSurfaceStore
import com.voxyn.utils.VoxynLogger
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
 * The service re-arms listening after every command and silence interval. There
 * is no wake phrase; the foreground service owns the continuous listening loop.
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
    private val islandSurfaceStore: IslandSurfaceStore by inject()

    private var activeEngine      : SpeechRecognizerEngine? = null
    private var audioManager      : AudioManager? = null
    private var audioFocusRequest : AudioFocusRequest? = null
    private var consecutiveFailures = 0
    private var currentSessionId  : String = ""
    @Volatile private var captureGeneration = 0L
    @Volatile private var activeCaptureId: String? = null
    private var wakeLock          : android.os.PowerManager.WakeLock? = null

    // ─── Phase 1: Audio hygiene effects (attached after AudioRecord starts) ──
    private var echoCanceler : AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl  : AutomaticGainControl? = null

    // ─── Phase 1: Call interruption ───────────────────────────────────────────
    private var callWatcher  : CallWatcher? = null
    @Volatile private var micPausedForCall = false

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

        // Phase 1: Register call watcher — pauses mic on incoming/active calls.
        callWatcher = CallWatcher(this) { shouldPause ->
            micPausedForCall = shouldPause
            if (shouldPause) {
                VoxynLogger.i("Call detected — pausing microphone", "VOICE")
                OverlayService.instance?.setState("RECOVERING", "Microphone paused for call")
                stopListeningInternal()
            } else {
                VoxynLogger.i("Call ended — resuming microphone", "VOICE")
                if (!stopping) mainHandler.postDelayed({ beginListening() }, VoxynConstants.MIC_SETTLE_MS)
            }
        }
        callWatcher?.register()

        VoxynLogger.i("VoiceInputService created (24x7 Always-Listening Mode)", "VOICE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        when (intent?.action) {
            ACTION_STOP_LISTENING -> {
                stopping = true
                stopListeningInternal()
                OverlayService.instance?.setState("STOPPED")
                stopSelf()
            }
            else -> {
                val sid = intent?.getStringExtra(EXTRA_SESSION_ID)
                currentSessionId = if (!sid.isNullOrBlank()) sid else coordinator.onWakeDetected()
                VoxynLogger.i("ACTION_START_LISTENING received (session: $currentSessionId)", "VOICE")
                mainHandler.postDelayed({ beginListening() }, VoxynConstants.START_DELAY_MS)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        stopListeningInternal()
        callWatcher?.unregister()
        callWatcher = null
        releaseAudioEffects()
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
        if (stopping || isListening || micPausedForCall) return
        isListening = true
        updateNotification("VOXYN — Always On")

        requestAudioFocus()

        if (currentSessionId.isBlank() || coordinator.getState() == SessionState.IDLE) {
            currentSessionId = coordinator.onWakeDetected()
        }
        val sessionId = currentSessionId
        val captureId = "$sessionId:${++captureGeneration}"
        activeCaptureId = captureId
        islandSurfaceStore.beginCapture(captureId)
        val mayShowPartial = speakerProfileRepository.getAllProfiles().isEmpty()
        val listener = createEngineListener(sessionId, captureId, captureGeneration, mayShowPartial)

        // First-time setup: if Parakeet isn't downloaded yet, fetch it in the
        // background (progressed in the notification) while Vosk handles commands.
        if (!parakeetModelManager.isModelPresent() && !parakeetModelManager.downloadState.value.downloading) {
            maybeDownloadAsrModel()
        }

        val recognizerEngine = engineProvider.currentEngine()
        activeEngine = recognizerEngine
        if (!recognizerEngine.start(listener)) {
            engineProvider.reportEngineFailure(recognizerEngine, "Engine start returned false")
            val fallbackEngine = engineProvider.getFallbackEngine()
            activeEngine = fallbackEngine
            if (!fallbackEngine.start(listener)) {
                mainHandler.post {
                    if (stopping) return@post
                    isListening = false
                    activeEngine = null
                    releaseAudioFocus()
                    islandSurfaceStore.clearCapture(captureId, unavailable = true)
                    activeCaptureId = null
                    OverlayService.instance?.setState("RECOVERING", "Restarting voice input")
                    updateNotification("VOXYN — voice engine unavailable")
                    mainHandler.postDelayed({ if (!stopping) beginListening() }, 3_000L)
                }
                return
            }
            islandSurfaceStore.setVoiceState(com.voxyn.overlay.IslandState.LISTENING)
            OverlayService.instance?.setState("LISTENING")
            VoxynLogger.i("Command listening started via fallback (${fallbackEngine::class.simpleName})", "VOICE")
            return
        }
        islandSurfaceStore.setVoiceState(com.voxyn.overlay.IslandState.LISTENING)
        OverlayService.instance?.setState("LISTENING")
        coordinator.onListeningStarted(currentSessionId, recognizerEngine::class.simpleName ?: "Engine")

        // Phase 1: Attach hardware audio effects to the active audio session.
        // getAudioSessionId() is available on SherpaParakeetRecognizer and
        // VoskCommandRecognizer — fall back gracefully if the method is absent.
        val audioSessionId = when (recognizerEngine) {
            is SherpaParakeetRecognizer -> recognizerEngine.getAudioSessionId()
            is VoskCommandRecognizer    -> recognizerEngine.getAudioSessionId()
            else                        -> -1
        }
        attachAudioEffects(audioSessionId)

        VoxynLogger.i("Always-listening active (${recognizerEngine::class.simpleName})", "VOICE")
    }

    private fun createEngineListener(
        sessionId: String,
        captureId: String,
        generation: Long,
        mayShowPartial: Boolean
    ): SpeechRecognizerEngine.Listener = object : SpeechRecognizerEngine.Listener {
        private var lastPartial = ""
        private var lastPartialAt = 0L

        private fun isCurrentCapture(): Boolean = !stopping && generation == captureGeneration

        override fun onSpeechDetected() {
            if (isCurrentCapture()) islandSurfaceStore.setVoiceState(com.voxyn.overlay.IslandState.LISTENING)
        }

        override fun onPartial(text: String) {
            if (!mayShowPartial || !isCurrentCapture()) return
            val normalized = text.trim().take(120)
            val now = android.os.SystemClock.uptimeMillis()
            if (normalized.isEmpty() || normalized == lastPartial || now - lastPartialAt < 120L) return
            lastPartial = normalized
            lastPartialAt = now
            islandSurfaceStore.setPartialTranscript(captureId, normalized)
        }

        override fun onAudioLevel(rmsDb: Float) {
            if (isCurrentCapture()) OverlayService.instance?.setMicrophoneLevel(rmsDb)
        }

        override fun onResult(text: String, audioDurationMs: Long, inferenceMs: Long, audioSamples: ShortArray?) {
            mainHandler.post {
                if (!isCurrentCapture()) return@post
                islandSurfaceStore.setVoiceState(com.voxyn.overlay.IslandState.VERIFYING, "Verifying speaker")
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                val isMediaPlaying = audioManager?.isMusicActive == true
                val gateOutcome = coordinator.onSpeechResult(sessionId, text, audioDurationMs, audioSamples, isMediaPlaying)
                val profiles = speakerProfileRepository.getAllProfiles()
                val isAuthorized = when (gateOutcome) {
                    is com.voxyn.security.GateResult.Authorized -> {
                        // If profiles are enrolled, ONLY respond if the speaker is the owner!
                        if (profiles.isNotEmpty()) gateOutcome.isOwner else true
                    }
                    else -> false
                }

                if (isAuthorized) {
                    islandSurfaceStore.setFinalTranscript(captureId, text)
                    activeCaptureId = null
                    handleResult(text)
                } else {
                    VoxynLogger.w("Voice command rejected or unverified speaker — silently dropping (no sound, no display)", "VOICE")
                    // If voice not recognized, won't say or show anything!
                    islandSurfaceStore.clearCapture(captureId, message = "Voice not verified")
                    activeCaptureId = null
                    coordinator.onSessionEnded()
                    currentSessionId = ""
                    isListening = false
                    activeEngine?.stop()
                    activeEngine = null
                    releaseAudioEffects()
                    releaseAudioFocus()
                    mainHandler.postDelayed({ if (!stopping) beginListening() }, VoxynConstants.MIC_SETTLE_MS)
                }
            }
        }

        override fun onNoSpeech() {
            mainHandler.post {
                if (!isCurrentCapture()) return@post
                islandSurfaceStore.clearCapture(captureId, message = "No speech recognized")
                activeCaptureId = null
                handleSilence("no-speech")
            }
        }

        override fun onError(message: String) {
            if (!isCurrentCapture()) return
            val listenerInstance = this
            VoxynLogger.w("Command STT error: $message", "VOICE")
            mainHandler.post {
                if (!isCurrentCapture()) return@post
                val current = activeEngine
                if (current is SherpaParakeetRecognizer) {
                    engineProvider.reportEngineFailure(current, message)
                    current.stop()
                    val fallback = engineProvider.getFallbackEngine()
                    activeEngine = fallback
                    VoxynLogger.i("Attempting in-session fallback to VoskCommandRecognizer after error: $message", "VOICE")
                    if (fallback.start(listenerInstance)) {
                        OverlayService.instance?.setState("RECOVERING", "Switching voice engine")
                        return@post
                    }
                }
                islandSurfaceStore.clearCapture(captureId, unavailable = true)
                activeCaptureId = null
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
        isListening = false
        activeEngine?.stop()
        activeEngine = null
        releaseAudioFocus()
        islandSurfaceStore.setVoiceState(com.voxyn.overlay.IslandState.PROCESSING, "Processing command")
        consecutiveFailures = 0
        updateNotification("Processing: \"$text\"...")

        VoxynLogger.i("Executing command: \"$text\"", "VOICE")
        session.addTurn(ConversationTurn(userInput = text, action = "", response = ""))
        engine.process(text)
        waitForEngineThenContinue()
    }

    private fun waitForEngineThenContinue() {
        scope.launch {
            val deadline = System.currentTimeMillis() + VoxynConstants.PIPELINE_MAX_WAIT_MS
            while (System.currentTimeMillis() < deadline && (engine.isProcessing() || tts.isSpeaking())) {
                delay(200L)
            }
            // Phase 1: Apply MIC_SETTLE_MS before re-arming the AudioRecord
            // session to avoid HAL race conditions after TTS playback finishes.
            delay(VoxynConstants.MIC_SETTLE_MS)
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
        releaseAudioEffects()
        releaseAudioFocus()
        OverlayService.instance?.setState("RECOVERING", "Listening again")
        mainHandler.postDelayed({ if (!stopping) beginListening() }, VoxynConstants.SILENCE_REARM_DELAY_MS)
    }

    private fun stopListeningInternal() {
        captureGeneration++ // Invalidate callbacks from the recognizer being stopped.
        activeCaptureId?.let { islandSurfaceStore.clearCapture(it, message = if (stopping) "Listening stopped" else "Listening paused") }
        activeCaptureId = null
        isListening = false
        activeEngine?.stop()
        activeEngine = null
        releaseAudioEffects()
        releaseAudioFocus()
        coordinator.onSessionEnded()
        currentSessionId = ""
    }

    // ─── Phase 1: Hardware audio effects ─────────────────────────────────────

    /**
     * Attach AcousticEchoCanceler, NoiseSuppressor, and AutomaticGainControl
     * to the AudioRecord's audio session. All three run at HAL level — they
     * process PCM before it reaches Vosk or Parakeet, so they don't add
     * CPU overhead to our IO work.
     *
     * Each effect is guarded by isAvailable() because not every device
     * exports these effects (some low-end SOCs omit AEC in the HAL).
     */
    private fun attachAudioEffects(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            VoxynLogger.w("AudioSessionId unavailable — skipping hardware audio effects", "VOICE")
            return
        }
        releaseAudioEffects() // clear any stale effects from previous session

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            VoxynLogger.i("AcousticEchoCanceler attached (sessionId=$audioSessionId)", "VOICE")
        } else {
            VoxynLogger.w("AcousticEchoCanceler not available on this device", "VOICE")
        }

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            VoxynLogger.i("NoiseSuppressor attached", "VOICE")
        } else {
            VoxynLogger.w("NoiseSuppressor not available on this device", "VOICE")
        }

        if (AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            VoxynLogger.i("AutomaticGainControl attached", "VOICE")
        } else {
            VoxynLogger.w("AutomaticGainControl not available on this device", "VOICE")
        }
    }

    /** Release all active hardware audio effects. Safe to call when none attached. */
    private fun releaseAudioEffects() {
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        gainControl?.release()
        gainControl = null
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
