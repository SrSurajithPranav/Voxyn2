package com.voxyn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxyn.core.PipelineEvent
import com.voxyn.core.PipelinePhase
import com.voxyn.engine.VoxynEngine
import com.voxyn.wake.VoskModelManager
import com.voxyn.wake.VoskModelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoxynUiState(
    val logs: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val currentPhase: PipelinePhase? = null,
<<<<<<< HEAD
    val partialText: String = ""
=======
    val partialText: String = "",
    val awaitingConfirmation: Boolean = false,
    val modelState: VoskModelState = VoskModelState.Uninitialized
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
)

class VoxynViewModel(
    private val engine: VoxynEngine,
    private val modelManager: VoskModelManager
) : ViewModel() {

    private val _state = MutableStateFlow(VoxynUiState())
    val state: StateFlow<VoxynUiState> = _state.asStateFlow()

    init {
        modelManager.ensureModelAvailable()

        viewModelScope.launch {
            engine.events.collect { event -> handleEvent(event) }
        }

        viewModelScope.launch {
            modelManager.modelState.collect { state ->
                _state.update { it.copy(modelState = state) }
            }
        }
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

    fun retryModelDownload() {
        modelManager.retryModelSetup()
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
