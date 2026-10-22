package com.voxyn.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class VoiceChoice(val displayName: String, val pitch: Float, val speechRate: Float, val voiceFilter: String?) {
    SYSTEM_DEFAULT("System Default", 1.0f, 1.0f, null),
    JARVIS("Jarvis (Warm Male)", 0.88f, 1.0f, "male"),
    FRIDAY("Friday (Crisp Female)", 1.15f, 1.05f, "female"),
    CADENCE("Cadence (Smooth)", 0.98f, 0.95f, null),
    EXPRESSIVE("Dynamic (Expressive)", 1.08f, 1.10f, null);

    companion object {
        fun fromOrdinal(ordinal: Int): VoiceChoice =
            values().getOrElse(ordinal) { SYSTEM_DEFAULT }

        fun fromName(name: String): VoiceChoice =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }
                ?: SYSTEM_DEFAULT
    }
}

data class VoiceOption(
    val id: String,
    val displayName: String,
    val pitch: Float,
    val speechRate: Float,
    val timbre: String?,
    val isCloned: Boolean = false,
    val profileId: String? = null
)

/**
 * TextToSpeechManager — robust TTS playback with reliable completion callbacks,
 * single-speaker enforcement, voice cloning parameter replication, and selection
 * among built-in presets and custom enrolled friend/owner voices.
 */
class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false
    private var pendingSpeech: String? = null
    private var pendingCallback: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isCurrentlySpeaking = AtomicBoolean(false)
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val utteranceSequence = AtomicLong()
    private val activeUtteranceId = AtomicReference<String?>(null)
    @Volatile private var currentVoiceChoice: VoiceChoice = VoiceChoice.SYSTEM_DEFAULT
    @Volatile private var activeVoiceOption: VoiceOption = VoiceOption(
        id = VoiceChoice.SYSTEM_DEFAULT.name,
        displayName = VoiceChoice.SYSTEM_DEFAULT.displayName,
        pitch = VoiceChoice.SYSTEM_DEFAULT.pitch,
        speechRate = VoiceChoice.SYSTEM_DEFAULT.speechRate,
        timbre = VoiceChoice.SYSTEM_DEFAULT.voiceFilter
    )

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                applyVoiceSettings(activeVoiceOption.pitch, activeVoiceOption.speechRate, activeVoiceOption.timbre)
                setupProgressListener()
                isReady = true
                VoxynLogger.i("TTS ready (voice: ${activeVoiceOption.displayName})", "TTS")
                pendingSpeech?.let { text ->
                    val cb = pendingCallback
                    pendingSpeech = null
                    pendingCallback = null
                    speakNow(text, cb)
                }
            } else {
                VoxynLogger.e("TTS init failed: $status", tag = "TTS")
                completeCallbacks()
            }
        }
    }

    fun getAvailableVoices(): List<VoiceChoice> = VoiceChoice.values().toList()

    fun getVoiceChoice(): VoiceChoice = currentVoiceChoice

    fun getActiveVoiceOption(): VoiceOption = activeVoiceOption

    fun setVoiceChoice(choice: VoiceChoice) {
        currentVoiceChoice = choice
        activeVoiceOption = VoiceOption(
            id = choice.name,
            displayName = choice.displayName,
            pitch = choice.pitch,
            speechRate = choice.speechRate,
            timbre = choice.voiceFilter
        )
        applyVoiceSettings(choice.pitch, choice.speechRate, choice.voiceFilter)
        VoxynLogger.i("TTS voice switched to preset: ${choice.displayName}", "TTS")
    }

    fun setVoiceOption(option: VoiceOption) {
        activeVoiceOption = option
        currentVoiceChoice = VoiceChoice.fromName(option.id)
        applyVoiceSettings(option.pitch, option.speechRate, option.timbre)
        VoxynLogger.i("TTS voice switched to: ${option.displayName} (pitch=${option.pitch}, rate=${option.speechRate}, timbre=${option.timbre})", "TTS")
    }

    fun setClonedVoice(profile: com.voxyn.security.SpeakerProfile) {
        val label = if (profile.isOwner) "My Voice (${profile.name})" else "${profile.name}'s Voice"
        val option = VoiceOption(
            id = "cloned_${profile.id}",
            displayName = label,
            pitch = profile.pitch,
            speechRate = profile.speechRate,
            timbre = profile.timbre,
            isCloned = true,
            profileId = profile.id
        )
        setVoiceOption(option)
    }

    private fun applyVoiceSettings(pitch: Float, rate: Float, filter: String?) {
        val engine = tts ?: return
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
        try {
            val available = engine.voices ?: emptySet()
            if (filter != null) {
                val match = available.firstOrNull { v ->
                    v.locale.language == Locale.US.language &&
                    v.name.contains(filter, ignoreCase = true) &&
                    !v.isNetworkConnectionRequired
                } ?: available.firstOrNull { v ->
                    v.name.contains(filter, ignoreCase = true)
                }
                if (match != null) {
                    engine.voice = match
                    VoxynLogger.d("Matched Android TTS voice: ${match.name}", "TTS")
                }
            }
        } catch (e: Exception) {
            VoxynLogger.w("Could not set specific voice: ${e.message}", "TTS")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == activeUtteranceId.get()) isCurrentlySpeaking.set(true)
                VoxynLogger.d("TTS started: $utteranceId", "TTS")
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != activeUtteranceId.get()) return
                activeUtteranceId.set(null)
                isCurrentlySpeaking.set(false)
                VoxynLogger.d("TTS completed: $utteranceId", "TTS")
                if (utteranceId != null) {
                    val cb = callbacks.remove(utteranceId)
                    cb?.let { mainHandler.post(it) }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != activeUtteranceId.get()) return
                activeUtteranceId.set(null)
                isCurrentlySpeaking.set(false)
                VoxynLogger.w("TTS error on: $utteranceId", "TTS")
                if (utteranceId != null) {
                    val cb = callbacks.remove(utteranceId)
                    cb?.let { mainHandler.post(it) }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != activeUtteranceId.get()) return
                activeUtteranceId.set(null)
                isCurrentlySpeaking.set(false)
                VoxynLogger.w("TTS error ($errorCode) on: $utteranceId", "TTS")
                if (utteranceId != null) {
                    val cb = callbacks.remove(utteranceId)
                    cb?.let { mainHandler.post(it) }
                }
            }
        })
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }
        if (!isReady) {
            pendingSpeech = text
            pendingCallback = onComplete
            VoxynLogger.d("TTS not ready; response queued", "TTS")
            return
        }
        speakNow(text, onComplete)
    }

    suspend fun speakAndWait(text: String) {
        if (text.isBlank()) return
        val deferred = CompletableDeferred<Unit>()
        speak(text) { deferred.complete(Unit) }
        deferred.await()
    }

    private fun speakNow(text: String, onComplete: (() -> Unit)?) {
        // QUEUE_FLUSH alone is not enough to make stale completion callbacks safe.
        // Complete any replaced request so speakAndWait callers cannot hang.
        tts?.stop()
        activeUtteranceId.set(null)
        isCurrentlySpeaking.set(false)
        completeCallbacks()
        val utteranceId = "voxyn_${utteranceSequence.incrementAndGet()}"
        activeUtteranceId.set(utteranceId)
        if (onComplete != null) {
            callbacks[utteranceId] = onComplete
        }
        val cleanText = sanitizeForSpeech(text)
        if (cleanText.isBlank()) {
            onComplete?.invoke()
            return
        }
        VoxynLogger.i("TTS: \"$cleanText\"", "TTS")
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun sanitizeForSpeech(raw: String): String {
        return raw
            // Strip any leading AI persona prefix like "VOXYN:", "VOXYN -", "Assistant:", etc.
            .replaceFirst(Regex("^(?:VOXYN|VOXIN|Assistant|AI|Bot)\\s*[:\\-–—]?\\s*", RegexOption.IGNORE_CASE), "")
            // Replace all-caps VOXYN so TTS doesn't spell it out as V-O-X-Y-N
            .replace(Regex("\\bVOXYN\\b", RegexOption.IGNORE_CASE), "Voxyn")
            .replace(Regex("\\bV-O-X-Y-N\\b", RegexOption.IGNORE_CASE), "Voxyn")
            .replace(Regex("\\bV O X Y N\\b", RegexOption.IGNORE_CASE), "Voxyn")
            // Clean up Markdown formatting
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("\\*(.*?)\\*"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("https?://\\S+"), "link")
            .replace(Regex("[#_~]"), " ")
            .trim()
    }

    fun isSpeaking(): Boolean = isCurrentlySpeaking.get() || (tts?.isSpeaking == true)

    fun stop() {
        completeCallbacks()
        activeUtteranceId.set(null)
        isCurrentlySpeaking.set(false)
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
        pendingSpeech = null
        pendingCallback = null
    }

    private fun completeCallbacks() {
        val pending = callbacks.values.toList()
        callbacks.clear()
        pending.forEach { callback -> mainHandler.post(callback) }
    }
}
