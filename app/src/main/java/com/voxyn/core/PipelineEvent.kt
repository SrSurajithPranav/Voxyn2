package com.voxyn.core

sealed class PipelineEvent {
    data class PhaseStarted(val phase: PipelinePhase) : PipelineEvent()
    data class PhaseCompleted(val phase: PipelinePhase, val summary: String) : PipelineEvent()
    data class PhaseFailed(val phase: PipelinePhase, val error: String, val canFallback: Boolean) : PipelineEvent()
    data class DecisionMade(val state: DecisionState) : PipelineEvent()
    data class ExecutionStepStarted(val step: ActionStep) : PipelineEvent()
    data class ExecutionStepCompleted(val step: ActionStep, val result: String) : PipelineEvent()
    data class VerificationResult(val outcome: VerificationOutcome) : PipelineEvent()
    data class EngineInterrupted(val reason: String) : PipelineEvent()
    data class ClarificationRequired(val missing: List<String>) : PipelineEvent()
    data class ConfirmationRequired(val intent: IntentResult) : PipelineEvent()
    data class TrustScoreUpdated(val action: String, val score: Float) : PipelineEvent()
    data class AnswerProvenance(val source: String, val url: String?, val retrievedAtEpochMs: Long) : PipelineEvent()
    object PipelineComplete : PipelineEvent()
}
