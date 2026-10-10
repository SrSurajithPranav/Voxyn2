package com.voxyn.ui

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxyn.voice.EnrollmentResult
import com.voxyn.voice.VoiceEnrollmentManager
import com.voxyn.voice.VoiceProfile
import com.voxyn.voice.VoiceProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VoiceStudioState(
    val profiles: List<VoiceProfile> = emptyList(),
    val recordingSlot: Int? = null,
    val message: String = "",
    val selectedReplyVoiceId: String? = null
)

class VoiceStudioViewModel(
    private val repository: VoiceProfileRepository,
    private val enrollment: VoiceEnrollmentManager
) : ViewModel() {
    private val _state = MutableStateFlow(VoiceStudioState(
        profiles = repository.list(), selectedReplyVoiceId = repository.selectedReplyVoiceId()
    ))
    val state: StateFlow<VoiceStudioState> = _state.asStateFlow()
    private var player: MediaPlayer? = null

    fun record(slot: Int, label: String) {
        if (_state.value.recordingSlot != null) return
        _state.value = _state.value.copy(recordingSlot = slot, message = "Recording Voice ${slot + 1} for six seconds…")
        viewModelScope.launch {
            val result = enrollment.record(slot, label)
            _state.value = when (result) {
                is EnrollmentResult.Success -> VoiceStudioState(repository.list(), message = "Saved ${result.profile.label}.", selectedReplyVoiceId = repository.selectedReplyVoiceId())
                is EnrollmentResult.Rejected -> _state.value.copy(recordingSlot = null, profiles = repository.list(), message = result.reason)
                is EnrollmentResult.Failure -> _state.value.copy(recordingSlot = null, profiles = repository.list(), message = result.reason)
            }
        }
    }

    fun selectReplyVoice(slot: Int) {
        val profile = repository.list().getOrNull(slot) ?: return
        if (!profile.enrolled) return
        repository.setSelectedReplyVoice(profile.id)
        _state.value = _state.value.copy(selectedReplyVoiceId = profile.id, message = "Reply voice set to ${profile.label}.")
    }

    fun delete(slot: Int) {
        stopPlayback()
        repository.clear(slot)
        _state.value = _state.value.copy(profiles = repository.list(), message = "Voice ${slot + 1} cleared.")
    }

    fun play(slot: Int) {
        val profile = repository.list().getOrNull(slot) ?: return
        val path = profile.samplePath ?: return
        stopPlayback()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
        }.getOrNull()
        _state.value = _state.value.copy(message = if (player != null) "Playing ${profile.label}." else "Playback failed.")
    }

    fun stopPlayback() {
        runCatching { player?.stop() }
        player?.release()
        player = null
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
