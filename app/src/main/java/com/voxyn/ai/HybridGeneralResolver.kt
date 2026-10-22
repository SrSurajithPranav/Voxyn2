package com.voxyn.ai

import com.voxyn.utils.VoxynLogger
import com.voxyn.voice.ConversationSession
import com.voxyn.web.DistanceProvider
import com.voxyn.web.DuckDuckGoSearchProvider
import com.voxyn.web.NewsSearchProvider
import com.voxyn.web.WeatherDay
import com.voxyn.web.WeatherProvider
import com.voxyn.web.WebSearchProvider
import com.voxyn.web.WikipediaSearchProvider
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HybridGeneralResolver — the single hybrid routing policy for general
 * questions and current-information requests.
 *
 * Decision order (never send everything to the cloud):
 *
 *   1. follow-up referents from the active ConversationSession
 *   2. world time (deterministic, offline)
 *   3. weather / distance / news → keyless web providers (WEB)
 *   4. local LLM (LOCAL)  →  Wikipedia / DuckDuckGo (WEB)  →  cloud (CLOUD)
 *
 * Providers are injected; the engine only ever calls GeneralQueryResolver.
 */
class HybridGeneralResolver(
    private val localLlm: AiProvider?,
    private val localKnowledge: LocalKnowledgeProvider,
    private val worldTime: WorldTimeProvider,
    private val wikipedia: WikipediaSearchProvider,
    private val news: NewsSearchProvider,
    private val duckDuckGo: DuckDuckGoSearchProvider,
    private val weather: WeatherProvider,
    private val distance: DistanceProvider,
    private val session: ConversationSession,
    private val cloud: CloudIntelligenceProvider? = null
) : GeneralQueryResolver {

    override suspend fun resolve(
        query: String,
        conversationContext: String,
        forceWeb: Boolean
    ): GeneralQueryResult? {
        val q = query.trim().lowercase().trimEnd('.', '!', '?')
        if (q.isBlank()) return null

        val followUp = resolveFollowUp(q)
        return when {
            followUp?.kind == FollowUpKind.WEATHER ->
                weatherAnswer(followUp.subject ?: return null, followUp.day ?: 0)

            followUp?.kind == FollowUpKind.DISTANCE -> {
                val origin = session.lastTurn()?.params?.get("origin") ?: return null
                distanceAnswer(origin, followUp.subject ?: return null)
            }

            followUp?.kind == FollowUpKind.TIME ->
                timeAnswer(followUp.subject ?: return null)

            isTimeQuery(q) -> timeAnswer(extractTimeLocation(q) ?: return null)
            isWeatherQuery(q) -> weatherAnswer(extractLocation(q) ?: return null, 0)
            isDistanceComparison(q) -> compareDistanceAnswer(q)
            isWhichCloser(q) -> whichCloserAnswer(q)
            isDistanceQuery(q) -> distanceAnswer(
                distanceParts(q)?.first ?: return null,
                distanceParts(q)?.second ?: return null
            )
            isNewsQuery(q) -> newsAnswer(q)
            forceWeb || isTimeSensitiveQuery(q) -> webAnswer(q)
            else -> generalAnswer(q, conversationContext)
        }
    }

    // ─── World time (offline, deterministic) ──────────────────────────

    private fun timeAnswer(location: String): GeneralQueryResult? {
        val text = worldTime.timeIn(location) ?: return null
        return GeneralQueryResult(
            text,
            "local time",
            IntelligenceRoute.LOCAL,
            mapOf("kind" to "time", "location" to location)
        )
    }

    // ─── Weather (keyless web) ─────────────────────────────────────────

    private suspend fun weatherAnswer(location: String, day: Int): GeneralQueryResult? {
        val result = runCatching { weather.forecast(location, day) }.getOrNull() ?: return null
        return GeneralQueryResult(
            result.text,
            "weather",
            IntelligenceRoute.WEB,
            mapOf("kind" to "weather", "location" to result.location, "day" to day.toString())
        )
    }

    // ─── Distance (keyless web, multi-stop aware) ─────────────────────

    private suspend fun distanceAnswer(originRaw: String, destinationRaw: String?): GeneralQueryResult? {
        val destination = destinationRaw ?: return null
        val origin = originRaw.trim()
        val stops = if (destination.contains(" via ", ignoreCase = true)) {
            val parts = destination.split(Regex("\\s+via\\s+", RegexOption.IGNORE_CASE))
            (listOf(origin) + parts.drop(1) + parts[0]).map { it.trim() }.filter { it.isNotBlank() }
        } else if (origin.contains(",")) {
            (origin.split(",").map { it.trim() } + destination.trim()).filter { it.isNotBlank() }
        } else {
            listOf(origin, destination)
        }
        if (stops.size >= 2) {
            return if (stops.size == 2) {
                singleDistance(stops[0], stops[1])
            } else {
                multiStopAnswer(stops)
            }
        }
        return singleDistance(origin, destination)
    }

    private suspend fun singleDistance(origin: String, destination: String): GeneralQueryResult? {
        val result = runCatching { distance.distance(origin, destination) }.getOrNull() ?: return null
        return GeneralQueryResult(
            result.text,
            "maps",
            IntelligenceRoute.WEB,
            mapOf("kind" to "distance", "origin" to origin, "destination" to destination)
        )
    }

    private suspend fun multiStopAnswer(stops: List<String>): GeneralQueryResult? {
        var totalKm = 0.0
        val legs = mutableListOf<String>()
        for (i in 0 until stops.size - 1) {
            val result = runCatching { distance.distance(stops[i], stops[i + 1]) }.getOrNull()
                ?: return null
            totalKm += result.km
            legs.add("${pretty(stops[i])} to ${pretty(stops[i + 1])} is about ${roundKm(result.km)} kilometres")
        }
        val text = legs.joinToString(". ") + ". Total around ${roundKm(totalKm)} kilometres."
        return GeneralQueryResult(
            text,
            "maps",
            IntelligenceRoute.WEB,
            mapOf("kind" to "distance", "origin" to stops.first(), "destination" to stops.last())
        )
    }

    /** "which is closer to Chennai, Bangalore or Kochi?" */
    private suspend fun whichCloserAnswer(q: String): GeneralQueryResult? {
        val parsed = parseCloser(q) ?: return null
        val (reference, a, b) = parsed
        val da = runCatching { distance.distance(reference, a) }.getOrNull()
        val db = runCatching { distance.distance(reference, b) }.getOrNull()
        if (da == null && db == null) return null
        val (winner, km) = when {
            da == null -> b to db!!.km
            db == null -> a to da.km
            da.km <= db.km -> a to da.km
            else -> b to db.km
        }
        val text = "${pretty(winner)} is closer to ${pretty(reference)} — about ${roundKm(km)} kilometres."
        return GeneralQueryResult(
            text,
            "maps",
            IntelligenceRoute.WEB,
            mapOf("kind" to "distance", "origin" to reference, "destination" to winner)
        )
    }

    // ─── News (keyless web, real headlines only) ───────────────────────

    private suspend fun newsAnswer(q: String): GeneralQueryResult? {
        val result = runCatching { news.search(q) }.getOrNull() ?: return null
        return GeneralQueryResult(
            result.text,
            result.source,
            IntelligenceRoute.WEB,
            mapOf("kind" to "news", "query" to q),
            result.sourceUrl,
            result.retrievedAtEpochMs
        )
    }

    /** Current facts and explicit web searches must never be answered from model memory. */
    private suspend fun webAnswer(query: String): GeneralQueryResult? {
        val result = runCatching { duckDuckGo.search(query) }.getOrNull()
            ?: runCatching { wikipedia.search(query) }.getOrNull()
            ?: return null
        return GeneralQueryResult(
            text = result.text,
            source = result.source,
            route = IntelligenceRoute.WEB,
            params = mapOf("kind" to "current_information", "query" to query),
            sourceUrl = result.sourceUrl,
            retrievedAtEpochMs = result.retrievedAtEpochMs
        )
    }

    // ─── General question: local → web → cloud → null ──────────────────

    private suspend fun generalAnswer(
        q: String,
        conversationContext: String,
        forceWeb: Boolean = false
    ): GeneralQueryResult? {
        if (forceWeb || isTimeSensitiveQuery(q)) return webAnswer(q)
        if (q.contains("joke")) {
            return GeneralQueryResult(localKnowledge.joke(), "VOXYN", IntelligenceRoute.LOCAL, mapOf("kind" to "chat"))
        }

        val llm = localLlm
        if (llm != null) {
            val local = withTimeoutOrNull(20_000L) {
                runCatching { llm.answer(q, conversationContext) }.getOrNull()
            }
            if (local != null) {
                VoxynLogger.i("[ROUTER] LOCAL route answered", "ROUTER")
                return GeneralQueryResult(local.text, local.source, IntelligenceRoute.LOCAL, mapOf("kind" to "chat"))
            }
        }

        val wiki = runCatching { wikipedia.search(q) }.getOrNull()
        if (wiki != null) {
            return GeneralQueryResult(wiki.text, wiki.source, IntelligenceRoute.WEB, mapOf("kind" to "chat"), wiki.sourceUrl, wiki.retrievedAtEpochMs)
        }

        val ddg = runCatching { duckDuckGo.search(q) }.getOrNull()
        if (ddg != null) {
            return GeneralQueryResult(ddg.text, ddg.source, IntelligenceRoute.WEB, mapOf("kind" to "chat"), ddg.sourceUrl, ddg.retrievedAtEpochMs)
        }

        val cloudProvider = cloud
        if (cloudProvider != null) {
            val answer = withTimeoutOrNull(25_000L) {
                runCatching { cloudProvider.answer(q, conversationContext) }.getOrNull()
            }
            if (answer != null) {
                VoxynLogger.i("[ROUTER] CLOUD route answered", "ROUTER")
                return GeneralQueryResult(answer.text, answer.source, IntelligenceRoute.CLOUD, mapOf("kind" to "chat"))
            }
        }

        VoxynLogger.w("[ROUTER] No provider answered — graceful fallback", "ROUTER")
        return null
    }

    private fun isTimeSensitiveQuery(query: String): Boolean =
        Regex("\\b(right now|currently|as of (?:today|now)|current|latest|today)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(query) &&
            !Regex("^(?:what(?:'s| is)? (?:the )?(?:date|day)|what day is it|what is today's date)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(query)

    // ─── Follow-up referents (extends ConversationSession) ─────────────

    private enum class FollowUpKind { WEATHER, DISTANCE, TIME }

    private data class FollowUp(val kind: FollowUpKind, val subject: String?, val day: Int? = null)

    private fun resolveFollowUp(q: String): FollowUp? {
        // VoiceInputService seeds every new turn with empty params before the
        // engine answers it, so resolve referents against the last turn that
        // actually carries conversation state (weather/time/distance params).
        val last = session.turns.lastOrNull { it.params.isNotEmpty() }
            ?: session.lastTurn()
            ?: return null
        val kind = last.params["kind"] ?: return null
        if (kind != "weather" && kind != "distance" && kind != "time") return null

        val about = Regex("^what\\s+about\\s+(.+)$").find(q)
            ?: Regex("^how\\s+about\\s+(.+)$").find(q)
            ?: Regex("^and\\s+(.+)$").find(q)
        val dayDirect = WeatherDay.offsetFrom(q)
        if (about == null && dayDirect == null) return null

        val subject = about?.groupValues?.get(1)?.trim().orEmpty().ifBlank { null }
        return when (kind) {
            "weather" -> {
                val location = last.params["location"] ?: return null
                val day = dayDirect ?: subject?.let { WeatherDay.offsetFrom(it) } ?: 0
                FollowUp(FollowUpKind.WEATHER, location, day)
            }
            "distance" -> {
                val destination = subject ?: return null
                FollowUp(FollowUpKind.DISTANCE, destination, null)
            }
            else -> {
                val city = subject ?: return null
                FollowUp(FollowUpKind.TIME, city, null)
            }
        }
    }

    // ─── Classification helpers ────────────────────────────────────────

    private fun isTimeQuery(q: String): Boolean =
        Regex("^what time is it in .+$").containsMatchIn(q)

    private fun extractTimeLocation(q: String): String? =
        Regex("^what time is it in (.+)$").find(q)?.groupValues?.get(1)?.trimEnd('.', '?', '!')

    private fun isWeatherQuery(q: String): Boolean =
        Regex("^what('s| is)? the weather(?: like)? in .+$").containsMatchIn(q) ||
            Regex("^weather(?: like)? in .+$").containsMatchIn(q) ||
            Regex("^how (hot|cold|warm) is it in .+$").containsMatchIn(q)

    private fun extractLocation(q: String): String? =
        Regex("^what('s| is)? the weather(?: like)? in (.+)$").find(q)?.groupValues?.get(2)
            ?: Regex("^weather(?: like)? in (.+)$").find(q)?.groupValues?.get(1)
            ?: Regex("^how (hot|cold|warm) is it in (.+)$").find(q)?.groupValues?.get(2)

    private fun isDistanceQuery(q: String): Boolean = distanceParts(q) != null

    private fun distanceParts(q: String): Pair<String, String>? =
        Regex("^(?:what is the |what's the )?distance between (.+) and (.+)$").find(q)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("^(?:what is the |what's the )?distance from (.+) to (.+)$").find(q)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("^how far is it from (.+) to (.+)$").find(q)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("^how far is (.+) (?:from|to) (.+)$").find(q)?.let { it.groupValues[1] to it.groupValues[2] }

    private suspend fun compareDistanceAnswer(q: String): GeneralQueryResult? {
        val regex1 = Regex("compare (?:the )?distance (?:from|between) (.+?) (?:to|and) (.+?) and (?:from|between)?\\s*(.+?) (?:to|and) (.+)", RegexOption.IGNORE_CASE).find(q)
        if (regex1 != null) {
            val o1 = regex1.groupValues[1].trim()
            val d1 = regex1.groupValues[2].trim()
            val o2 = regex1.groupValues[3].trim()
            val d2 = regex1.groupValues[4].trim()
            val r1 = runCatching { distance.distance(o1, d1) }.getOrNull()
            val r2 = runCatching { distance.distance(o2, d2) }.getOrNull()
            if (r1 != null && r2 != null) {
                val comp = if (r1.km < r2.km) {
                    "${pretty(o1)} to ${pretty(d1)} is shorter (${roundKm(r1.km)} km vs ${roundKm(r2.km)} km)."
                } else if (r1.km > r2.km) {
                    "${pretty(o2)} to ${pretty(d2)} is shorter (${roundKm(r2.km)} km vs ${roundKm(r1.km)} km)."
                } else {
                    "Both routes are about the same distance (${roundKm(r1.km)} km)."
                }
                val text = "${pretty(o1)} to ${pretty(d1)} is about ${roundKm(r1.km)} km, and ${pretty(o2)} to ${pretty(d2)} is about ${roundKm(r2.km)} km. $comp"
                return GeneralQueryResult(text, "maps", IntelligenceRoute.WEB, mapOf("kind" to "distance", "origin" to o1, "destination" to d1))
            }
        }

        val regex2 = Regex("how far is (.+?) from (.+?) and (.+)", RegexOption.IGNORE_CASE).find(q)
        if (regex2 != null) {
            val ref = regex2.groupValues[1].trim()
            val d1 = regex2.groupValues[2].trim()
            val d2 = regex2.groupValues[3].trim()
            val r1 = runCatching { distance.distance(ref, d1) }.getOrNull()
            val r2 = runCatching { distance.distance(ref, d2) }.getOrNull()
            if (r1 != null && r2 != null) {
                val text = "${pretty(ref)} to ${pretty(d1)} is about ${roundKm(r1.km)} km, while ${pretty(ref)} to ${pretty(d2)} is about ${roundKm(r2.km)} km."
                return GeneralQueryResult(text, "maps", IntelligenceRoute.WEB, mapOf("kind" to "distance", "origin" to ref, "destination" to d1))
            }
        }
        return null
    }

    private fun isDistanceComparison(q: String): Boolean =
        q.startsWith("compare distance") ||
            q.startsWith("compare the distance") ||
            Regex("^how far is .+ from .+ and .+$", RegexOption.IGNORE_CASE).containsMatchIn(q)

    private fun isWhichCloser(q: String): Boolean =
        q.startsWith("which is closer") || q.startsWith("which one is closer")

    private fun isNewsQuery(q: String): Boolean =
        Regex("\\b(news|headlines|breaking|current events)\\b").containsMatchIn(q) ||
            q.contains("what happened today") ||
            q.contains("what's happening") ||
            q.contains("what is happening")

    private fun parseCloser(q: String): Triple<String, String, String>? {
        val parts = q.split(Regex("\\s+or\\s+"))
        if (parts.size < 2) return null
        val b = parts.last().trim()
        val before = parts[parts.size - 2].trim()
        val tokens = before.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        if (tokens.size < 3) return null
        val a = tokens.last()
        val reference = tokens[tokens.size - 2]
        if (a.isEmpty() || reference.isEmpty()) return null
        if (reference in STOP_WORDS) return null
        return Triple(reference, a, b)
    }

    private fun pretty(value: String): String =
        value.trim().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun roundKm(km: Double): Int = maxOf(1, Math.round(km).toInt())

    companion object {
        private val STOP_WORDS = setOf("is", "to", "closer", "which", "one", "the", "a", "an")
    }
}
