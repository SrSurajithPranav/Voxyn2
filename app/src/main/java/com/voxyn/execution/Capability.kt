package com.voxyn.execution

import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome

/**
 * An independently testable Android action provider.
 *
 * Capabilities declare their prerequisites but never request permissions or
 * interact with UI. The engine continues to own intent, planning, risk
 * decisions, and user confirmation.
 */
interface Capability {
    val id: String
    val supportedActions: Set<String>
    val riskLevel: CapabilityRisk
    val requiredPermissions: Set<String>
    val requiresNetwork: Boolean

    /** True only when repeating execute after an uncertain failure cannot duplicate a side effect. */
    val retrySafe: Boolean get() = false

    suspend fun execute(step: ActionStep): PhaseResult<String>

    suspend fun verify(step: ActionStep, executionResult: String): VerificationOutcome
}

enum class CapabilityRisk {
    LOW,
    HIGH
}
