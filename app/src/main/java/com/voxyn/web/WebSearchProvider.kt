package com.voxyn.web

/**
 * WebSearchProvider — keyless web information abstraction. Implementations are
 * small, single-purpose fetchers (Wikipedia, Google News RSS, DuckDuckGo
 * Instant Answer). They return null on any failure so callers fall through to
 * the next provider instead of fabricating.
 */
data class WebSearchResult(
    val text: String,
    val source: String
)

interface WebSearchProvider {
    val id: String
    suspend fun search(query: String): WebSearchResult?
}
