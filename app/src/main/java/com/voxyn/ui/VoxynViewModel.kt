package com.voxyn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxyn.ai.LocalModelManager
import com.voxyn.core.PipelineEvent
import com.voxyn.core.PipelinePhase
import com.voxyn.engine.VoxynEngine
import com.voxyn.security.SpeakerProfile
import com.voxyn.security.SpeakerProfileRepository
import com.voxyn.security.VerificationResult
import com.voxyn.security.VoiceEnrollmentManager
import com.voxyn.security.VoiceEnrollmentState
import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.AsrDownloadState
import com.voxyn.voice.ParakeetModelManager
import com.voxyn.voice.TextToSpeechManager
import com.voxyn.voice.VoiceChoice
import com.voxyn.voice.VoiceDiagnosticsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoxynUiState(
    val logs: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val currentPhase: PipelinePhase? = null,
    val partialText: String = ""
)

data class LocalAiUiState(
    val modelPresent: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val message: String = ""
)

class VoxynViewModel(
    private val engine: VoxynEngine,
    private val localModelManager: LocalModelManager,
    private val parakeetModelManager: ParakeetModelManager,
    private val tts: TextToSpeechManager? = null,
    private val diagnostics: VoiceDiagnosticsManager? = null,
    private val speakerRepo: SpeakerProfileRepository? = null,
    private val enrollmentManager: VoiceEnrollmentManager? = null
) : ViewModel() {

    private val _state = MutableStateFlow(VoxynUiState())
    val state: StateFlow<VoxynUiState> = _state.asStateFlow()

    private val _localAi = MutableStateFlow(LocalAiUiState())
    val localAi: StateFlow<LocalAiUiState> = _localAi.asStateFlow()

    private val _storagePrompt = MutableStateFlow(false)
    val storagePrompt: StateFlow<Boolean> = _storagePrompt.asStateFlow()

    private val _enrolledProfiles = MutableStateFlow<List<SpeakerProfile>>(emptyList())
    val enrolledProfiles: StateFlow<List<SpeakerProfile>> = _enrolledProfiles.asStateFlow()

    private val _enrollmentState = MutableStateFlow<VoiceEnrollmentState>(VoiceEnrollmentState.Idle)
    val enrollmentState: StateFlow<VoiceEnrollmentState> = _enrollmentState.asStateFlow()

    val asrState: StateFlow<AsrDownloadState> = parakeetModelManager.downloadState

    val currentVoice: VoiceChoice get() = tts?.getVoiceChoice() ?: VoiceChoice.SYSTEM_DEFAULT

    fun cycleVoice(): VoiceChoice {
        val manager = tts ?: return VoiceChoice.SYSTEM_DEFAULT
        val voices = manager.getAvailableVoices()
        val nextIdx = (voices.indexOf(manager.getVoiceChoice()) + 1) % voices.size
        val nextVoice = voices[nextIdx]
        manager.setVoiceChoice(nextVoice)
        return nextVoice
    }

    fun exportDiagnosticReport(): String =
        diagnostics?.exportSanitizedReport() ?: "Diagnostics not initialized"

    fun refreshProfiles() {
        _enrolledProfiles.value = speakerRepo?.getAllProfiles() ?: emptyList()
    }

    fun startEnrollment(name: String, isOwner: Boolean) {
        val manager = enrollmentManager ?: run {
            _enrollmentState.value = VoiceEnrollmentState.Error("Voice enrollment service unavailable")
            return
        }
        viewModelScope.launch {
            _enrollmentState.value = VoiceEnrollmentState.Recording(VoiceEnrollmentManager.ENROLLMENT_DURATION_SEC, 0f)
            val result = manager.enrollVoice(name, isOwner) { secondsRemaining, progress ->
                _enrollmentState.value = VoiceEnrollmentState.Recording(secondsRemaining, progress)
            }
            result.fold(
                onSuccess = { profile ->
                    refreshProfiles()
                    _enrollmentState.value = VoiceEnrollmentState.Success(
                        profile.name,
                        "Voice ID enrolled for \"${profile.name}\"!"
                    )
                },
                onFailure = { error ->
                    _enrollmentState.value = VoiceEnrollmentState.Error(
                        error.message ?: "Voice enrollment failed"
                    )
                }
            )
        }
    }

    fun cancelEnrollment() {
        enrollmentManager?.cancel()
        _enrollmentState.value = VoiceEnrollmentState.Idle
    }

    fun clearEnrollmentStatus() {
        _enrollmentState.value = VoiceEnrollmentState.Idle
    }

    fun deleteProfile(id: String) {
        speakerRepo?.removeProfile(id)
        refreshProfiles()
    }

    fun testVoiceMatch() {
        val manager = enrollmentManager ?: run {
            _enrollmentState.value = VoiceEnrollmentState.Error("Speaker verification service unavailable")
            return
        }
        viewModelScope.launch {
            _enrollmentState.value = VoiceEnrollmentState.Recording(VoiceEnrollmentManager.TEST_DURATION_SEC, 0f)
            val result = manager.testVerification { secondsRemaining, progress ->
                _enrollmentState.value = VoiceEnrollmentState.Recording(secondsRemaining, progress)
            }
            result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is VerificationResult.Verified -> {
                            val matchPct = (outcome.similarity * 100).toInt()
                            _enrollmentState.value = VoiceEnrollmentState.TestResult(
                                verified = true,
                                summary = "Voice Verified: ${outcome.profile.name}",
                                details = "Acoustic match $matchPct% (Owner=${outcome.profile.isOwner})"
                            )
                        }
                        is VerificationResult.RejectedUnknownSpeaker -> {
                            val matchPct = (outcome.bestSimilarity * 100).toInt()
                            _enrollmentState.value = VoiceEnrollmentState.TestResult(
                                verified = false,
                                summary = "Voice Rejected (Unknown Speaker)",
                                details = "Similarity $matchPct% below threshold (${(outcome.threshold * 100).toInt()}%)"
                            )
                        }
                        is VerificationResult.PoorAudioQuality -> {
                            _enrollmentState.value = VoiceEnrollmentState.Error(outcome.reason)
                        }
                        is VerificationResult.NoProfilesEnrolled -> {
                            _enrollmentState.value = VoiceEnrollmentState.Error("No enrolled profiles to compare against")
                        }
                        is VerificationResult.Error -> {
                            _enrollmentState.value = VoiceEnrollmentState.Error(outcome.message)
                        }
                    }
                },
                onFailure = { error ->
                    _enrollmentState.value = VoiceEnrollmentState.Error(error.message ?: "Voice test failed")
                }
            )
        }
    }

    init {
        viewModelScope.launch {
            engine.events.collect { event -> handleEvent(event) }
        }
        refreshLocalAi()
        refreshAsr()
        refreshProfiles()
    }

    fun submit(input: String) {
        if (input.isBlank()) return
        _state.update { it.copy(isRunning = true, logs = emptyList()) }
        engine.process(input)
    }

    fun interrupt() {
        engine.interrupt("User cancelled")
        _state.update { it.copy(isRunning = false) }
    }

    fun refreshLocalAi() {
        viewModelScope.launch {
            _localAi.update { it.copy(modelPresent = localModelManager.isModelPresent(), message = "") }
            if (!_localAi.value.modelPresent) {
                _localAi.update { it.copy(message = "Checking Downloads for a saved AI model...") }
                val restored = localModelManager.ensureModelAvailable()
                _localAi.update {
                    it.copy(
                        modelPresent = restored,
                        message = if (restored) "Model restored from Downloads — ready offline." else ""
                    )
                }
            } else {
                // Model already on device (e.g. upgraded from an older build):
                // make sure a copy exists in Downloads so it survives uninstall/reinstall.
                localModelManager.backupIfMissing()
            }
        }
    }

    fun refreshAsr() {
        viewModelScope.launch {
            parakeetModelManager.ensureAvailable()
        }
    }

    fun downloadAsrModel() {
        viewModelScope.launch {
            parakeetModelManager.download()
        }
    }

    /** True only when both offline engines already have their models. */
    fun modelsPresent(): Boolean =
        parakeetModelManager.isModelPresent() && localModelManager.isModelPresent()

    /** True when a restorable copy is visible in the MediaStore Downloads collection. */
    fun hasDownloadsBackup(): Boolean =
        parakeetModelManager.hasMediaStoreBackup() || localModelManager.hasMediaStoreBackup()

    /** Restore-only pass (no network): called when All-files access gets granted. */
    fun restoreModelsFromStorage() {
        viewModelScope.launch {
            val asrOk = parakeetModelManager.ensureAvailable()
            val llmOk = localModelManager.ensureModelAvailable(allowDownload = false)
            if (llmOk) {
                _localAi.update { it.copy(modelPresent = true, message = "Model restored from Downloads — ready offline.") }
            }
            VoxynLogger.i(
                "Manual restore: ASR=$asrOk LLM=$llmOk",
                "UI"
            )
        }
    }

    fun showStoragePrompt(show: Boolean) {
        _storagePrompt.value = show
    }

    fun downloadLocalModel() {
        if (_localAi.value.downloading) return
        viewModelScope.launch {
            _localAi.update { it.copy(downloading = true, progress = 0f, message = "Downloading AI model (~0.6 GB)...") }
            val outcome = localModelManager.downloadModel { progress ->
                _localAi.update { it.copy(progress = progress) }
            }
            _localAi.update {
                it.copy(downloading = false, modelPresent = outcome.ok, message = outcome.message)
            }
        }
    }

    private fun handleEvent(event: PipelineEvent) {
        val log = when (event) {
            is PipelineEvent.PhaseStarted            -> "▶ ${event.phase.name}"
            is PipelineEvent.PhaseCompleted          -> "✓ ${event.phase.name}: ${event.summary}"
            is PipelineEvent.PhaseFailed             -> "✗ ${event.phase.name}: ${event.error}"
            is PipelineEvent.DecisionMade            -> "⚡ Decision: ${event.state::class.simpleName}"
            is PipelineEvent.ExecutionStepStarted    -> "→ ${event.step.description}"
            is PipelineEvent.ExecutionStepCompleted   -> "✓ ${event.result}"
            is PipelineEvent.VerificationResult      -> "🔍 ${event.outcome::class.simpleName}"
            is PipelineEvent.EngineInterrupted       -> "⛔ ${event.reason}"
            is PipelineEvent.ClarificationRequired   -> "❓ ${event.missing.firstOrNull()}"
            is PipelineEvent.ConfirmationRequired     -> "⚠️ Confirm: ${event.intent.action}"
            is PipelineEvent.TrustScoreUpdated        -> "📊 Trust[${event.action}]: ${event.score}"
            is PipelineEvent.PipelineComplete         -> "🎯 Done"
        }

        _state.update { s ->
            s.copy(
                logs = s.logs + log,
                isRunning = event !is PipelineEvent.PipelineComplete
                    && event !is PipelineEvent.EngineInterrupted
                    && event !is PipelineEvent.ClarificationRequired
                    && event !is PipelineEvent.ConfirmationRequired,
                currentPhase = when (event) {
                    is PipelineEvent.PhaseStarted -> event.phase
                    is PipelineEvent.PipelineComplete, is PipelineEvent.EngineInterrupted -> null
                    else -> s.currentPhase
                }
            )
        }
    }
}
