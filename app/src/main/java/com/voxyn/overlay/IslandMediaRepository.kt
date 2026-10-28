package com.voxyn.overlay

import android.media.session.MediaController
import android.media.session.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IslandMediaState(
    val accessGranted: Boolean = false,
    val sessionAvailable: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val canTogglePlayback: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false
)

/** Read-only metadata and controls for sessions exposed through Android Notification Access. */
class IslandMediaRepository {
    private val _state = MutableStateFlow(IslandMediaState())
    val state: StateFlow<IslandMediaState> = _state.asStateFlow()
    @Volatile private var controller: MediaController? = null

    fun updateAccess(granted: Boolean) {
        if (!granted) controller = null
        _state.value = if (granted) _state.value.copy(accessGranted = true) else IslandMediaState()
    }

    fun updateSession(next: MediaController?) {
        controller = next
        val metadata = next?.metadata
        val playback = next?.playbackState
        val actions = playback?.actions ?: 0L
        updateSnapshot(IslandMediaState(
            accessGranted = _state.value.accessGranted,
            sessionAvailable = next != null,
            title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank),
            artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank),
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            canTogglePlayback = actions and (if (playback?.state == PlaybackState.STATE_PLAYING) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY) != 0L,
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        ))
    }

    internal fun updateSnapshot(snapshot: IslandMediaState) {
        _state.value = snapshot.copy(accessGranted = _state.value.accessGranted)
    }

    fun togglePlayback(): Boolean = runCatching {
        val current = controller ?: return false
        val snapshot = _state.value
        if (!snapshot.canTogglePlayback) return false
        if (snapshot.isPlaying) current.transportControls.pause() else current.transportControls.play()
        true
    }.getOrDefault(false)

    fun skipNext(): Boolean = runCatching {
        if (!_state.value.canSkipNext) return false
        (controller ?: return false).transportControls.skipToNext()
        true
    }.getOrDefault(false)

    fun skipPrevious(): Boolean = runCatching {
        if (!_state.value.canSkipPrevious) return false
        (controller ?: return false).transportControls.skipToPrevious()
        true
    }.getOrDefault(false)
}
