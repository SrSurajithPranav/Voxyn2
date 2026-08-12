package com.voxyn.ai

/**
 * GeneralQueryResolver — the single seam through which VOXYN answers questions
 * that are not deterministic Android commands.
 *
 * The implementation owns the hybrid decision (local LLM → web → cloud) plus
 * follow-up referent resolution against the ongoing ConversationSession.
 * Nothing else in the engine talks to a specific web or LLM provider.
 */
data class GeneralQueryResult(
    val text: String,
    val source: String,
    val route: IntelligenceRoute,
    val params: Map<String, String> = emptyMap()
)

interface GeneralQueryResolver {
    /**
     * Answers a general question. [conversationContext] carries the last turns
     * of the ConversationSession so follow-ups ("what about tomorrow?") resolve.
     * Returns null only when no provider could answer — callers then degrade
     * gracefully instead of fabricating.
     */
    suspend fun resolve(query: String, conversationContext: String): GeneralQueryResult?
}
