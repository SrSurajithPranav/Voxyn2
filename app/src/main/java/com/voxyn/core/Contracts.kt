package com.voxyn.core

// ─── Intent ───────────────────────────────────────────────────────────────────

data class IntentResult(
    val action: String,
    val params: Map<String, String> = emptyMap(),
    val confidence: Float = 0f,
    val rawInput: String = ""
)

// ─── Decision ─────────────────────────────────────────────────────────────────

sealed class DecisionState {
    object Execute : DecisionState()
    data class AwaitingConfirmation(val intent: IntentResult) : DecisionState()
    data class AwaitingClarification(val missing: List<String>) : DecisionState()
    object Rejected : DecisionState()
}

// ─── Verification ─────────────────────────────────────────────────────────────

sealed class VerificationOutcome {
    object Success : VerificationOutcome()
    object PartialSuccess : VerificationOutcome()
    object Failure : VerificationOutcome()
    object NeedsRollback : VerificationOutcome()
}

// ─── Execution Plan ───────────────────────────────────────────────────────────

data class ActionStep(
    val id: String,
    val description: String,
    val actionType: String,
    val params: Map<String, String> = emptyMap()
)

data class ExecutionPlan(
    val steps: List<ActionStep>,
    val currentStep: Int = 0
) {
    val isComplete: Boolean get() = currentStep >= steps.size
    val currentAction: ActionStep? get() = steps.getOrNull(currentStep)
    fun advance() = copy(currentStep = currentStep + 1)
}

// ─── Pipeline Phase ───────────────────────────────────────────────────────────

enum class PipelinePhase {
    INPUT, PERCEPTION, INTENT, CONFIDENCE, RISK,
    DECISION, PLAN, EXECUTE, VERIFY, LEARN
}
