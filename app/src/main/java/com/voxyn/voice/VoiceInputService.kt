package com.voxyn.voice

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
import com.voxyn.apps.AppCatalog
import com.voxyn.engine.VoxynEngine
import com.voxyn.utils.VoxynLogger
import com.voxyn.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.vosk.Recognizer

/**
 * VoiceInputService — listens for a spoken command using the bundled offline
 * Vosk model and runs the engine.
 *
 * Google's SpeechRecognizer returns ERROR_TOO_MANY_REQUESTS on some devices, so
 * the command path uses Vosk directly (the same offline model as the wake word).
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
        const val CHANNEL_ID             = "voxyn_voice"
        const val NOTIFICATION_ID        = 3002

        private const val START_DELAY_MS          = 200L
        private const val SAMPLE_RATE             = 16000
        private const val SILENCE_TIMEOUT_MS      = 8_000L
        private const val ENDPOINT_SILENCE_MS     = 1_200L
        private const val REARM_DELAY_MS          = 1_000L
        private const val FIRST_COMMAND_MAX_ATTEMPTS = 2
        private const val MAX_CONSECUTIVE_FAILURES   = 2
        private const val PIPELINE_MAX_WAIT_MS    = 60_000L

        private val COMMAND_WORDS = listOf(
            "open", "launch", "start", "stop", "turn", "on", "off", "enable", "disable",
            "set", "change", "adjust", "increase", "decrease", "raise", "lower", "volume",
            "up", "down", "louder", "quieter", "mute", "silent", "vibrate", "ringer",
            "normal", "mode", "flashlight", "light", "torch", "wifi", "wi", "fi",
            "bluetooth", "brightness", "screen", "screenshot", "capture", "lock", "phone",
            "call", "message", "messages", "send", "play", "pause", "next", "previous",
            "music", "song", "track", "navigate", "navigation", "direction", "alarm",
            "timer", "minute", "minutes", "second", "seconds", "hour", "hours", "clock",
            "am", "pm", "date", "today", "time", "what", "who", "when", "where", "how",
            "why", "is", "are", "your", "you", "my", "name", "weather", "hello", "hi",
            "hey", "thanks", "thank", "welcome", "help", "please", "okay", "yes", "no",
            "cancel", "go", "back", "home", "scroll", "swipe", "read", "notifications",
            "remember", "save", "and", "then", "the", "for", "me", "to", "it", "that",
            "this", "with", "in", "of", "wanna", "want", "voxyn"
        )

        private val NUMBER_WORDS = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
            "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand",
            "percent", "degrees"
        )
    }

    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engine: VoxynEngine by inject()
    private val session: ConversationSession by inject()
    private val tts: TextToSpeechManager by inject()
    private val modelProvider: VoskModelProvider by inject()
    private val appCatalog: AppCatalog by inject()

    private var listenJob           : Job? = null
    private var audioManager        : AudioManager? = null
    private var audioFocusRequest   : AudioFocusRequest? = null
    private var conversationWatchdog: Runnable? = null
    private var consecutiveFailures = 0

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
                VoxynLogger.i("ACTION_START_LISTENING received", "VOICE")
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
        listenJob?.cancel()
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
        listenJob?.cancel()
        listenJob = scope.launch {
            val model = modelProvider.getModel()
            if (model == null) {
                mainHandler.post {
                    if (stopping) return@post
                    isListening = false
                    releaseAudioFocus()
                    updateNotification("VOXYN — voice model unavailable")
                    tts.speak("Voice model is not available.")
                    mainHandler.postDelayed({ resumeWakeAndStop() }, 1500L)
                }
                return@launch
            }

            var recognizer: Recognizer? = null
            var audioRecord: AudioRecord? = null
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
                    throw IllegalStateException("AudioRecord init failed")
                }

                val rec = Recognizer(model, SAMPLE_RATE.toFloat(), buildCommandGrammar())
                recognizer = rec
                record.startRecording()

                val buffer = ShortArray(bufferSize)
                val startTime = System.currentTimeMillis()
                var lastSpeechTime = startTime
                var hadSpeech = false
                var finalText: String? = null
                var lastPartial = ""

                mainHandler.post { updateNotification("Listening...") }
                VoxynLogger.i("Vosk command listening started (8s silence timeout)", "VOICE")

                while (isActive && isListening && !stopping) {
                    val read = record.read(buffer, 0, bufferSize)
                    if (read <= 0) continue
                    val now = System.currentTimeMillis()

                    if (rec.acceptWaveForm(buffer, read)) {
                        val text = JSONObject(rec.result).optString("text", "").trim()
                        if (text.isNotEmpty()) {
                            finalText = text
                            hadSpeech = true
                            lastSpeechTime = now
                            VoxynLogger.i("Vosk final: \"$text\"", "VOICE")
                            break
                        }
                    } else {
                        val partial = JSONObject(rec.getPartialResult()).optString("partial", "").trim()
                        if (partial.isNotEmpty()) {
                            lastPartial = partial
                            hadSpeech = true
                            lastSpeechTime = now
                            val shown = partial
                            mainHandler.post { updateNotification("VOXYN — \"$shown\"...") }
                        }
                    }

                    if (hadSpeech && now - lastSpeechTime >= ENDPOINT_SILENCE_MS) {
                        try { rec.result } catch (_: Exception) {}
                        break
                    }
                    if (!hadSpeech && now - startTime >= SILENCE_TIMEOUT_MS) {
                        break
                    }
                    delay(20L)
                }

                val best = finalText ?: lastPartial.takeIf { it.isNotEmpty() }
                mainHandler.post {
                    if (stopping) return@post
                    isListening = false
                    releaseAudioFocus()
                    if (best != null) {
                        // Vosk-only noise ("[unk]") is not a command — retry listening
                        // instead of feeding garbage to the engine.
                        val meaningful = best.replace("[unk]", "").trim()
                        if (meaningful.isEmpty()) {
                            retryOrGiveUp("no-speech", REARM_DELAY_MS)
                        } else {
                            handleResult(best)
                        }
                    } else {
                        handleSilence("no-speech")
                    }
                }
            } catch (se: SecurityException) {
                VoxynLogger.e("Microphone permission missing", tag = "VOICE")
                mainHandler.post {
                    if (stopping) return@post
                    isListening = false
                    releaseAudioFocus()
                    updateNotification("VOXYN — microphone permission needed")
                    tts.speak("I need microphone permission. Please allow it in app settings.")
                    resumeWakeAndStop()
                }
            } catch (e: Exception) {
                VoxynLogger.e("Vosk listening failed: ${e.message}", tag = "VOICE")
                mainHandler.post {
                    if (stopping) return@post
                    isListening = false
                    releaseAudioFocus()
                    handleSilence("error")
                }
            } finally {
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                try { recognizer?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * JSON grammar that biases the small Vosk model toward words VOXYN actually
     * understands: the installed app names, their common mis-hearings, command
     * verbs and numbers. This is what turns "open what's that" into "open whatsapp"
     * instead of free-form dictation garbage.
     */
    private fun buildCommandGrammar(): String = runCatching {
        val words = linkedSetOf<String>()
        words.add("[unk]")
        words.addAll(COMMAND_WORDS)
        words.addAll(NUMBER_WORDS)
        words.addAll(appCatalog.grammarTerms())
        VoxynLogger.i("Command grammar ready: ${words.size} words", "VOICE")
        val arr = JSONArray()
        words.forEach { arr.put(it) }
        arr.toString()
    }.getOrElse {
        VoxynLogger.e("Command grammar build failed: ${it.message}", tag = "VOICE")
        """["[unk]", "open", "turn", "on", "off", "set", "voxyn"]"""
    }

    private fun handleResult(text: String) {
        if (stopping) return
        isListening = false
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
        if (session.turns.isEmpty()) {
            // First listen after the wake phrase — 8s of silence returns to IDLE
            VoxynLogger.i("No command heard ($reason) — back to wake detection", "VOICE")
            updateNotification("VOXYN — say Hey VOXYN")
            resumeWakeAndStop()
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
                updateNotification("VOXYN — say Hey VOXYN")
                resumeWakeAndStop()
            } else {
                endSessionWithStandingBy()
            }
            return
        }

        VoxynLogger.w("Vosk $reason — retrying in ${backoffMs}ms (failure #$consecutiveFailures)", "VOICE")
        updateNotification("VOXYN — retrying...")
        mainHandler.postDelayed({ if (!stopping) beginListening() }, backoffMs)
    }

    private fun endSessionWithStandingBy() {
        if (stopping) return
        cancelWatchdog()
        session.end()
        updateNotification("VOXYN — say Hey VOXYN")
        tts.speak("Standing by.")
        resumeWakeAndStop()
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
        listenJob?.cancel()
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

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VOXYN Voice")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "VOXYN Voice Input",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
        }
    }
}
