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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.voxyn.MainActivity
import com.voxyn.wake.WakeWordService
import com.voxyn.engine.VoxynEngine
import com.voxyn.utils.VoxynLogger
import org.koin.android.ext.android.inject

class VoiceInputService : Service() {

    companion object {
        const val ACTION_START_LISTENING = "com.voxyn.voice.START_LISTENING"
        const val ACTION_STOP_LISTENING  = "com.voxyn.voice.STOP_LISTENING"
        const val CHANNEL_ID             = "voxyn_voice"
        const val NOTIFICATION_ID        = 3002
        const val EXTRA_RESUME_WAKE_AFTER_FINISH = "extra_resume_wake_after_finish"
        const val EXTRA_FOLLOW_UP_MODE = "extra_follow_up_mode"
        const val EXTRA_INITIAL_TIMEOUT_MS = "extra_initial_timeout_ms"
        const val EXTRA_SILENCE_COMPLETE_MS = "extra_silence_complete_ms"
    }

    private val engine: VoxynEngine by inject()
    private val tts: TextToSpeechManager by inject()
    private var recognizer: SpeechRecognizer? = null
    private var isActive = false
    private val handler = Handler(Looper.getMainLooper())
<<<<<<< HEAD
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
=======
    private var resumeWakeAfterFinish = true
    private var followUpMode = false
    private var initialTimeoutMs = 5_000L
    private var completeSilenceMs = 1_500L
    private var speechHeard = false

    private val initialTimeoutRunnable = Runnable {
        if (!speechHeard && isActive) {
            VoxynLogger.i("Initial silence timeout reached", "VOICE")
            finishAndHandoff(null)
        }
    }
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Listening..."))
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        VoxynLogger.i("VoiceInputService created", "VOICE")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        resumeWakeAfterFinish = intent?.getBooleanExtra(EXTRA_RESUME_WAKE_AFTER_FINISH, true) ?: true
        followUpMode = intent?.getBooleanExtra(EXTRA_FOLLOW_UP_MODE, false) ?: false
        initialTimeoutMs = intent?.getLongExtra(EXTRA_INITIAL_TIMEOUT_MS, 5_000L) ?: 5_000L
        completeSilenceMs = intent?.getLongExtra(EXTRA_SILENCE_COMPLETE_MS, 1_500L) ?: 1_500L

        when (intent?.action) {
            ACTION_START_LISTENING -> handler.postDelayed({ startListening() }, 200L)
            ACTION_STOP_LISTENING  -> finishAndHandoff(null)
            else                   -> handler.postDelayed({ startListening() }, 200L)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
<<<<<<< HEAD

    override fun onDestroy() {
        releaseAudioFocus()
        destroyRecognizer()
        super.onDestroy()
        VoxynLogger.i("VoiceInputService destroyed", "VOICE")
    }

    // ─── Core ─────────────────────────────────────────────────────────

    private fun startListening() {
        if (isActive) return

=======
    override fun onDestroy() {
        destroyRecognizer()
        handler.removeCallbacks(initialTimeoutRunnable)
        super.onDestroy()
    }

    private fun startListening() {
        if (isActive) return
        tts.stop()
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            VoxynLogger.e("SpeechRecognizer unavailable", tag = "VOICE")
            finishAndHandoff(null)
            return
        }

        requestAudioFocus()
        destroyRecognizer()
<<<<<<< HEAD

        // Use the device-configured recognizer. Hard-coding Google's private
        // component makes voice input fail on non-Google Android distributions.
        recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            VoxynLogger.e("Unable to create speech recognizer: ${e.message}", tag = "VOICE")
            finishAndHandoff(null)
            return
=======
        speechHeard = false
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
        }

        recognizer?.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
<<<<<<< HEAD
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
=======
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
        }

        try {
            recognizer?.startListening(intent)
            isActive = true
            handler.removeCallbacks(initialTimeoutRunnable)
            handler.postDelayed(initialTimeoutRunnable, initialTimeoutMs)
            updateNotification("Listening... speak your command")
            VoxynLogger.i("Recognition started", "VOICE")
        } catch (e: Exception) {
            VoxynLogger.e("startListening failed: ${e.message}", tag = "VOICE")
            finishAndHandoff(null)
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .build()
            audioManager?.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
    }

    private fun destroyRecognizer() {
<<<<<<< HEAD
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() }       catch (_: Exception) {}
        recognizer = null
        isActive   = false
    }

    private fun finishAndHandoff(result: String?) {
        // The recognizer owns transient audio focus. Release it before the
        // pipeline can produce its spoken response, and do not restart it.
        releaseAudioFocus()
        destroyRecognizer()
<<<<<<< HEAD
=======
        handler.removeCallbacks(initialTimeoutRunnable)
        try { recognizer?.stopListening(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null; isActive = false
    }

    private fun finishAndHandoff(result: String?) {
        handler.removeCallbacks(initialTimeoutRunnable)
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
        if (result != null) {
            VoxynLogger.i("Voice → Engine: \"$result\"", "VOICE")
            engine.process(result)
        }
<<<<<<< HEAD
        startForegroundService(Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        })
=======
        destroyRecognizer()
        if (followUpMode && result.isNullOrBlank()) {
            engine.onFollowUpTimeout()
        } else if (resumeWakeAfterFinish) {
            try {
                startService(Intent(this, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_RESUME
                })
            } catch (e: Exception) {
                VoxynLogger.e("Resume WakeWordService failed: ${e.message}", tag = "VOICE")
            }
        }
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
        stopSelf()
=======
        if (result == null) {
            resumeWakeWordService()
            stopSelf()
            return
        }

        VoxynLogger.i("Voice → Engine: \"$result\"", "VOICE")
        engine.process(result).invokeOnCompletion {
            resumeWakeWordService()
            stopSelf()
        }
    }

    private fun resumeWakeWordService() {
        startForegroundService(Intent(this, WakeWordService::class.java).apply {
            action = WakeWordService.ACTION_START
        })
>>>>>>> 3c8c811 (fixed)
    }

    // ─── Listener ─────────────────────────────────────────────────────

    private val listener = object : RecognitionListener {
<<<<<<< HEAD
        override fun onReadyForSpeech(params: Bundle?) {
            VoxynLogger.d("Ready for speech", "VOICE")
        }
        override fun onBeginningOfSpeech() { updateNotification("Hearing you...") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
=======
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {
            speechHeard = true
            handler.removeCallbacks(initialTimeoutRunnable)
            updateNotification("Hearing you...")
        }
        override fun onRmsChanged(r: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
        override fun onEndOfSpeech() { updateNotification("Processing..."); isActive = false }
        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH           -> "No speech detected"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT     -> "Timed out"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY    -> "Recognizer busy"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT    -> "Network error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Need mic permission"
                else -> "Error $error"
            }
            VoxynLogger.w("Voice error: $msg", "VOICE")
            finishAndHandoff(null)
        }
        override fun onResults(results: Bundle?) {
            isActive = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            VoxynLogger.i("Result: \"$text\"", "VOICE")
            finishAndHandoff(text)
        }
        override fun onPartialResults(partial: Bundle?) {
            val text = partial
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return
            updateNotification("\"$text\"...")
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ─── Notification ─────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VOXYN Voice").setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true).setShowWhen(false).build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "VOXYN Voice Input", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false); setSound(null, null) }
                )
        }
    }
}
