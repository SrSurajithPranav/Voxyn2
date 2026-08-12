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
            val titles = parseNewsTitles(xml).map { cleanNewsTitle(it) }.filter { it.isNotBlank() }.distinct()
            if (titles.isEmpty()) {
                VoxynLogger.w("Google News no results for '$q'", "WEB")
                return@withContext null
            }
            VoxynLogger.i("[WEB] News results: ${titles.size} for '$q'", "WEB")
            val body = titles.take(3).joinToString(". ") { it.trimEnd('.') }
            WebSearchResult("Here's the latest on $q: $body.", "Google News")
        }.getOrNull()
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
}
