package com.voxyn.execution

import com.voxyn.core.ActionStep
import com.voxyn.core.PhaseResult
import com.voxyn.core.VerificationOutcome

/**
 * An isolated Android action that can be selected by the execution layer.
 *
 * Capabilities declare their requirements but never request permissions,
 * determine intent, plan work, or update UI.
 */
interface Capability {
    val metadata: CapabilityMetadata

    fun supports(action: ActionStep): Boolean =
        action.actionType in metadata.supportedActions

    suspend fun execute(action: ActionStep): PhaseResult<String>

    suspend fun verify(action: ActionStep): VerificationOutcome

    suspend fun rollback(action: ActionStep): PhaseResult<String>? = null
}

data class CapabilityMetadata(
    val id: String,
    val displayName: String,
    val supportedActions: Set<String>,
    val requiredPermissions: Set<String> = emptySet(),
    val riskLevel: CapabilityRiskLevel,
    val availability: CapabilityAvailability,
    val estimatedLatencyMs: Long,
    val supportsVerification: Boolean,
    val supportsRollback: Boolean = false
)

enum class CapabilityRiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

enum class CapabilityAvailability {
    OFFLINE,
    ONLINE_REQUIRED
}
