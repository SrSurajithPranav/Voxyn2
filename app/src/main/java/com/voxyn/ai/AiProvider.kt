package com.voxyn.ai

/**
 * AiProvider — abstraction over any intelligence source (local canned knowledge,
 * keyless web providers, or a cloud LLM). Providers return null when they cannot
 * answer; the engine then falls back gracefully instead of fabricating.
 */
data class AiResponse(
    val text: String,
    val source: String
)

interface AiProvider {
    val name: String

    /** Answer a spoken question, optionally with prior conversation context. */
    suspend fun answer(question: String, conversationContext: String): AiResponse?
}
