package com.voxyn.execution

/**
 * Read-only directory of capabilities supplied by dependency injection.
 *
 * Selection policy, permission evaluation, execution, and health tracking
 * remain outside this registry until their dedicated milestones.
 */
class CapabilityRegistry(capabilities: List<Capability>) {
    private val capabilitiesById: Map<String, Capability> = capabilities.associateBy { it.metadata.id }
    private val capabilitiesByAction: Map<String, List<Capability>> = capabilities
        .flatMap { capability ->
            capability.metadata.supportedActions.map { action -> action to capability }
        }
        .groupBy({ (action, _) -> action }, { (_, capability) -> capability })

    init {
        require(capabilitiesById.size == capabilities.size) {
            "Capability identifiers must be unique"
        }
    }

    fun get(id: String): Capability? = capabilitiesById[id]

    fun findAll(action: String): List<Capability> =
        capabilitiesByAction[action].orEmpty()

    fun all(): Collection<Capability> = capabilitiesById.values
}
