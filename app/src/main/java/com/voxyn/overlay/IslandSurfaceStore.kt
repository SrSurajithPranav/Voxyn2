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
    val expanded: Boolean = false,
    val mascotEnabled: Boolean = true,
    val minimalNotch: Boolean = false,
    val verifiedTranscript: String? = null,
    val recentAction: String? = null,
    val verificationOutcome: String? = null,
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

    fun setVoiceState(state: IslandState, label: String? = null) {
        _state.update { current ->
            val nextLabel = label ?: defaultLabel(state)
            current.copy(
                voiceState = state,
                label = nextLabel,
                recentAction = if (state == IslandState.SUCCESS || state == IslandState.ERROR) current.verifiedTranscript ?: current.recentAction else current.recentAction,
                verificationOutcome = when (state) {
                    IslandState.SUCCESS -> "Verified · $nextLabel"
                    IslandState.ERROR -> "Failed · $nextLabel"
                    else -> current.verificationOutcome
                }
            )
        }
    }

    fun setExpanded(expanded: Boolean) {
        _state.update { it.copy(expanded = expanded) }
    }

    fun setVerifiedTranscript(text: String) {
        val safeText = text.trim().take(240)
        if (safeText.isNotEmpty()) _state.update { it.copy(verifiedTranscript = safeText) }
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
        IslandState.PROCESSING -> "Thinking…"
        IslandState.EXECUTING -> "Executing…"
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
