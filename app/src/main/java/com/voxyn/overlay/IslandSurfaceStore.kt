package com.voxyn.overlay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IslandSurfaceState(
    val voiceState: IslandState = IslandState.IDLE,
    val label: String = "VOXYN",
    val partialTranscript: String = "",
    val expanded: Boolean = false,
    val mascotEnabled: Boolean = true,
    val minimalNotch: Boolean = false,
    val verifiedTranscript: String? = null,
    val recentAction: String? = null,
    val verificationOutcome: String? = null,
    val transcriptionUnavailable: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val headphonesConnected: Boolean = false,
    val headphoneStatusAvailable: Boolean = false
)

/** Shared, observable island state. Only verified transcripts are published here. */
class IslandSurfaceStore(
    private val preferences: DataStore<Preferences>,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(IslandSurfaceState())
    val state: StateFlow<IslandSurfaceState> = _state.asStateFlow()
    @Volatile private var activeCaptureId: String? = null
    @Volatile private var activePipelineGeneration: Long = 0L

    init {
        scope.launch {
            preferences.data.collect { values ->
                _state.update {
                    it.copy(
                        mascotEnabled = values[MASCOT_ENABLED] ?: true,
                        minimalNotch = values[MINIMAL_NOTCH] ?: false
                    )
                }
            }
        }
    }

    @Synchronized
    fun beginCapture(sessionId: String) {
        if (sessionId.isBlank()) return
        activeCaptureId = sessionId
        _state.update {
            it.copy(
                voiceState = IslandState.LISTENING,
                label = defaultLabel(IslandState.LISTENING),
                partialTranscript = "",
                verifiedTranscript = null,
                transcriptionUnavailable = false
            )
        }
    }

    @Synchronized
    fun setPartialTranscript(sessionId: String, text: String): Boolean {
        if (sessionId.isBlank() || sessionId != activeCaptureId) return false
        val partial = text.trim().take(120)
        if (partial.isEmpty()) return false
        _state.update {
            it.copy(
                voiceState = IslandState.PARTIAL_TRANSCRIPT,
                label = "Hearing you",
                partialTranscript = partial,
                transcriptionUnavailable = false
            )
        }
        return true
    }

    @Synchronized
    fun setFinalTranscript(sessionId: String, text: String): Boolean {
        if (sessionId.isBlank() || sessionId != activeCaptureId) return false
        activeCaptureId = null
        val finalText = text.trim().take(240)
        _state.update {
            it.copy(
                voiceState = if (finalText.isBlank()) IslandState.RECOVERING else IslandState.VERIFYING,
                label = if (finalText.isBlank()) "No speech recognized" else "Verifying command",
                verifiedTranscript = finalText.takeIf(String::isNotBlank),
                partialTranscript = "",
                transcriptionUnavailable = false
            )
        }
        return true
    }

    @Synchronized
    fun clearCapture(sessionId: String, unavailable: Boolean = false, message: String? = null) {
        if (activeCaptureId != null && activeCaptureId != sessionId) return
        activeCaptureId = null
        _state.update {
            it.copy(
                partialTranscript = "",
                transcriptionUnavailable = unavailable,
                voiceState = IslandState.RECOVERING,
                label = message ?: if (unavailable) "Speech recognition unavailable" else "No speech recognized"
            )
        }
    }

    @Synchronized
    fun beginPipeline(generation: Long) {
        if (generation < activePipelineGeneration) return
        activePipelineGeneration = generation
    }

    @Synchronized
    fun setVoiceState(state: IslandState, label: String? = null, generation: Long? = null): Boolean {
        if (generation != null && generation < activePipelineGeneration) return false
        _state.update { current ->
            val nextLabel = label ?: defaultLabel(state)
            current.copy(
                voiceState = state,
                label = nextLabel,
                partialTranscript = if (state == IslandState.LISTENING || state == IslandState.IDLE) "" else current.partialTranscript
            )
        }
        return true
    }

    @Synchronized
    fun setVerificationResult(action: String, outcome: String, generation: Long? = null): Boolean {
        if (generation != null && generation < activePipelineGeneration) return false
        _state.update { it.copy(recentAction = action, verificationOutcome = outcome) }
        return true
    }

    fun setExpanded(expanded: Boolean) {
        _state.update { it.copy(expanded = expanded) }
    }

    fun setBattery(percent: Int?, isCharging: Boolean) {
        _state.update { it.copy(batteryPercent = percent?.coerceIn(0, 100), charging = isCharging) }
    }

    fun setHeadphonesConnected(connected: Boolean, available: Boolean = true) {
        _state.update { it.copy(headphonesConnected = connected, headphoneStatusAvailable = available) }
    }

    suspend fun setMascotEnabled(enabled: Boolean) {
        _state.update { it.copy(mascotEnabled = enabled) }
        preferences.edit { it[MASCOT_ENABLED] = enabled }
    }

    suspend fun setMinimalNotch(enabled: Boolean) {
        _state.update { it.copy(minimalNotch = enabled) }
        preferences.edit { it[MINIMAL_NOTCH] = enabled }
    }

    private fun defaultLabel(state: IslandState): String = when (state) {
        IslandState.STOPPED -> "Paused"
        IslandState.IDLE -> "VOXYN"
        IslandState.LISTENING -> "Listening…"
        IslandState.PARTIAL_TRANSCRIPT -> "Hearing you"
        IslandState.PROCESSING -> "Thinking…"
        IslandState.PLANNING -> "Planning…"
        IslandState.EXECUTING -> "Executing…"
        IslandState.VERIFYING -> "Verifying…"
        IslandState.SPEAKING -> "Speaking…"
        IslandState.RECOVERING -> "Recovering…"
        IslandState.CONFIRMATION -> "Needs confirmation"
        IslandState.SUCCESS -> "Completed"
        IslandState.ERROR -> "Needs attention"
    }

    private companion object {
        val MASCOT_ENABLED = booleanPreferencesKey("island_mascot_enabled")
        val MINIMAL_NOTCH = booleanPreferencesKey("island_minimal_notch")
    }
}
