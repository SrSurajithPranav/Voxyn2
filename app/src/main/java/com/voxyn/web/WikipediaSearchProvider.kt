package com.voxyn.web

import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WikipediaSearchProvider — keyless Wikipedia REST summaries for
 * "explain X", "what is X", "capital of X", etc. Returns a condensed,
 * TTS-friendly extract or null so callers fall through gracefully.
 */
open class WikipediaSearchProvider : WebSearchProvider {

    override val id: String = "wikipedia"

    @Volatile private var lastTopic: String? = null

    open override suspend fun search(query: String): WebSearchResult? {
        val topic = resolveTopic(query) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val encoded = URLEncoder.encode(topic, "UTF-8")
                val conn = (URL("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
                }
                if (conn.responseCode != 200) {
                    VoxynLogger.w("Wikipedia HTTP ${conn.responseCode} for '$topic'", "WEB")
                    return@withContext null
                }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val extract = json.optString("extract").trim()
                if (extract.isBlank()) {
                    VoxynLogger.w("Wikipedia empty extract for '$topic'", "WEB")
                    return@withContext null
                }
                lastTopic = topic
                val answer = condense(extract, query)
                VoxynLogger.i("[AI] Wikipedia: \"$topic\" → ${answer.length} chars", "AI")
                val sourceUrl = json.optJSONObject("content_urls")
                    ?.optJSONObject("desktop")
                    ?.optString("page")
                    ?.takeIf { it.isNotBlank() }
                    ?: "https://en.wikipedia.org/wiki/${URLEncoder.encode(topic.replace(' ', '_'), "UTF-8")}"
                WebSearchResult(answer, "Wikipedia", sourceUrl)
            }.getOrNull()
        }
    }

    /** Picks a Wikipedia title from a spoken question, honouring follow-ups. */
    private fun resolveTopic(question: String): String? {
        val q = question.trim().lowercase().trimEnd('?', '.', '!')
        var topic = q
        for (prefix in TOPIC_PREFIXES) {
            if (q.startsWith(prefix)) {
                topic = q.removePrefix(prefix).trim()
                break
            }
        }
        topic = topic
            .replace(Regex("^(tell me|for me|please|about)\\s+"), "")
            .replace(Regex("\\b(the|a|an)\\b"), "")
            .replace(Regex("\\b(right now|currently|as of (?:today|now)|current|latest|today)\\b"), "")
            .trim()
        topic = topic.replace(Regex("\\s+"), " ")
        if (topic == "president of us" || topic == "president of united states") {
            topic = "President of the United States"
        }

        // Follow-up referents reuse the previous topic ("how many people live there")
        if (topic.length < 4 || Regex("\\b(there|it|them|that|then|here)\\b").containsMatchIn(q)) {
            lastTopic?.let { return it }
        }
        if (topic.length < 3) return null
        return topic
    }

    /** Keeps TTS-friendly length; surfaces the sentence matching the question. */
    private fun condense(extract: String, question: String): String {
        val sentences = extract.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

        val keyword = when {
            Regex("population|how many people|people live|inhabitants").containsMatchIn(question) -> "population"
            Regex("capital").containsMatchIn(question) -> "capital"
            Regex("area|big|size").containsMatchIn(question) -> "area"
            else -> null
        }
        val match = keyword?.let { k -> sentences.firstOrNull { it.contains(k, ignoreCase = true) } }
        val chosen = when {
            match != null -> listOf(sentences.first(), match)
            else -> sentences.take(3)
        }
        return chosen.joinToString(" ")
    }

    companion object {
        private val TOPIC_PREFIXES = listOf(
            "what is the capital of ", "what is the population of ", "capital of ", "population of ",
            "what is the meaning of ", "what's the meaning of ", "meaning of ",
            "what is ", "what are ", "what was ", "what does ", "what do ",
            "who is ", "who was ", "explain ", "define ", "tell me about ", "tell me what ",
            "how does ", "how do ", "why is ", "why do ", "what is the "
        )
    }
}
