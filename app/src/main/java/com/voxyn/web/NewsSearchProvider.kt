package com.voxyn.web

import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * NewsSearchProvider — keyless Google News RSS headlines. The LLM (or the
 * condensed titles themselves) becomes the summary; the provider never invents
 * stories — it returns real titles or null.
 */
open class NewsSearchProvider : WebSearchProvider {

    override val id: String = "news"

    open override suspend fun search(query: String): WebSearchResult? = withContext(Dispatchers.IO) {
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
            val items = parseNewsItems(xml)
                .map { it.copy(title = cleanNewsTitle(it.title)) }
                .filter { it.title.isNotBlank() }
                .distinctBy { it.title }
            if (items.isEmpty()) {
                VoxynLogger.w("Google News no results for '$q'", "WEB")
                return@withContext null
            }
            VoxynLogger.i("[WEB] News results: ${items.size} for '$q'", "WEB")
            val body = items.take(3).joinToString(". ") { it.title.trimEnd('.') }
            WebSearchResult(
                "Here's the latest on $q: $body.",
                "Google News",
                sourceUrl = items.firstOrNull()?.url
            )
        }.getOrNull()
    }

    private data class NewsItem(val title: String, val url: String?, val publishedAtEpochMs: Long?)

    private fun parseNewsItems(xml: String): List<NewsItem> =
        Regex("<item>.*?</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .mapNotNull { m ->
                val item = m.value
                val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                    .find(item)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
                val url = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
                    .find(item)?.groupValues?.get(1)?.trim()?.takeIf { it.startsWith("http") }
                val publishedAt = Regex("<pubDate>(.*?)</pubDate>", RegexOption.DOT_MATCHES_ALL)
                    .find(item)?.groupValues?.get(1)?.trim()?.let { value ->
                        runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    }
                NewsItem(title, url, publishedAt)
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
}
