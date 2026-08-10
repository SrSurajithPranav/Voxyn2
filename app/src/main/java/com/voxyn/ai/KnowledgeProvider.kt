package com.voxyn.ai

import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * KnowledgeProvider — keyless cloud knowledge and current information:
 *  - Wikipedia REST summaries for "explain X", "what is X", "capital of X"
 *  - Google News RSS for "latest news about X"
 *
 * No API keys required. Returns null on any failure so the engine can fall
 * back gracefully instead of fabricating an answer.
 */
class KnowledgeProvider {

    @Volatile private var lastTopic: String? = null

    suspend fun knowledge(question: String): AiResponse? {
        val topic = resolveTopic(question) ?: return null
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
                val answer = condense(extract, question, topic)
                VoxynLogger.i("[AI] Wikipedia: \"$topic\" → ${answer.length} chars", "AI")
                AiResponse(answer, "Wikipedia")
            }.getOrNull()
        }
    }

    suspend fun news(query: String): AiResponse? = withContext(Dispatchers.IO) {
        runCatching {
            val q = query.ifBlank { "top stories" }
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "https://news.google.com/rss/search?q=$encoded&hl=en-IN&gl=IN&ceid=IN:en"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
            }
            if (conn.responseCode != 200) {
                VoxynLogger.w("Google News HTTP ${conn.responseCode}", "WEB")
                return@withContext null
            }
            val xml = conn.inputStream.bufferedReader().readText()
            val titles = parseNewsTitles(xml).map { cleanNewsTitle(it) }.filter { it.isNotBlank() }.distinct()
            if (titles.isEmpty()) {
                VoxynLogger.w("Google News no results for '$q'", "WEB")
                return@withContext null
            }
            VoxynLogger.i("[WEB] News results: ${titles.size} for '$q'", "WEB")
            val body = titles.take(3).joinToString(". ") { it.trimEnd('.') }
            AiResponse("Here's the latest on $q: $body.", "Google News")
        }.getOrNull()
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
            .replace(Regex("\\b(tell me|for me|please|about|the|a|an)\\b.*$"), "")
            .replace(Regex("\\b(the|a|an)\\b"), "")
            .trim()

        // Follow-up referents reuse the previous topic ("how many people live there")
        if (topic.length < 4 || Regex("\\b(there|it|them|that|then|here)\\b").containsMatchIn(q)) {
            lastTopic?.let { return it }
        }
        if (topic.length < 3) return null
        return topic
    }

    /** Keeps TTS-friendly length; surfaces the sentence matching the question. */
    private fun condense(extract: String, question: String, topic: String): String {
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

    private fun parseNewsTitles(xml: String): List<String> =
        Regex("<item>.*?</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .mapNotNull { m ->
                Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                    .find(m.value)?.groupValues?.get(1)?.let { unescape(it) }
            }
            .toList()

    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")

    /** Drops the trailing " - Publisher" suffix Google News appends. */
    private fun cleanNewsTitle(title: String): String =
        title.replace(Regex("\\s+-\\s+[^-]+$"), "").trim()

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
