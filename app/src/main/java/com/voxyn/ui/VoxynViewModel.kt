package com.voxyn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxyn.ai.LocalModelManager
import com.voxyn.voice.AsrDownloadState
import com.voxyn.voice.ParakeetModelManager
import com.voxyn.core.PipelineEvent
import com.voxyn.core.PipelinePhase
import com.voxyn.engine.VoxynEngine
import com.voxyn.utils.VoxynLogger
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
    private val parakeetModelManager: ParakeetModelManager
) : ViewModel() {

    private val _state = MutableStateFlow(VoxynUiState())
    val state: StateFlow<VoxynUiState> = _state.asStateFlow()

    private val _localAi = MutableStateFlow(LocalAiUiState())
    val localAi: StateFlow<LocalAiUiState> = _localAi.asStateFlow()

    private val _storagePrompt = MutableStateFlow(false)
    val storagePrompt: StateFlow<Boolean> = _storagePrompt.asStateFlow()

    val asrState: StateFlow<AsrDownloadState> = parakeetModelManager.downloadState

    init {
        viewModelScope.launch {
            engine.events.collect { event -> handleEvent(event) }
        }
        refreshLocalAi()
        refreshAsr()
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
