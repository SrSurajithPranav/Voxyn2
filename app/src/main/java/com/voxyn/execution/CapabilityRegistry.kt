package com.voxyn.execution

import com.voxyn.core.ActionStep

/**
 * Resolves planned actions to DI-provided capabilities.
 *
 * A duplicate action is a composition error: it would otherwise make action
 * selection depend on registration order.
 */
class CapabilityRegistry(capabilities: List<Capability>) {
    private val capabilitiesByAction: Map<String, Capability> = buildMap {
        capabilities.forEach { capability ->
            require(capability.supportedActions.isNotEmpty()) {
                "Capability '${capability.id}' must support at least one action"
            }

            capability.supportedActions.forEach { action ->
                require(put(action, capability) == null) {
                    "Multiple capabilities support action '$action'"
                }
            }
        }
    }

    fun find(action: String): Capability? = capabilitiesByAction[action]

    fun find(step: ActionStep): Capability? = find(step.actionType)

    fun all(): List<Capability> = capabilitiesByAction.values.distinct()

    fun supportedActions(): Set<String> = capabilitiesByAction.keys
}
