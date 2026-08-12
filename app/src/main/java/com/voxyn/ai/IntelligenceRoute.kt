package com.voxyn.ai

/**
 * IntelligenceRoute — the single routing policy for non-deterministic requests.
 *
 * The engine never hard-codes provider choices; a GeneralQueryResolver returns
 * which route produced an answer so callers can log and (in future) learn from
 * routing behaviour.
 */
enum class IntelligenceRoute {
    /** Deterministic Android capability (never sent to AI). */
    DETERMINISTIC,

    /** On-device answer: world time, jokes, local LLM. */
    LOCAL,

    /** Keyless web provider: weather, distance, news, Wikipedia, DuckDuckGo. */
    WEB,

    /** Optional cloud LLM fallback (only when configured with an API key). */
    CLOUD
}
