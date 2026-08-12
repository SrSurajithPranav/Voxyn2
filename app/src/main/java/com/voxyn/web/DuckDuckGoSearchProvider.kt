package com.voxyn.web

import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * DuckDuckGoSearchProvider — keyless Instant Answer API. Often returns nothing
 * for conversational queries; it is a last-resort web step before the cloud
 * fallback, never a primary source.
 */
open class DuckDuckGoSearchProvider : WebSearchProvider {

    override val id: String = "duckduckgo"

    open override suspend fun search(query: String): WebSearchResult? = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("User-Agent", "VOXYN/1.0 (Android assistant)")
            }
            if (conn.responseCode != 200) {
                VoxynLogger.w("DuckDuckGo HTTP ${conn.responseCode}", "WEB")
                return@withContext null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val abstractText = json.optString("AbstractText").trim()
            val answer = json.optString("Answer").trim()
            val text = when {
                answer.isNotBlank() -> answer
                abstractText.isNotBlank() -> abstractText.take(280)
                else -> return@withContext null
            }
            VoxynLogger.i("[WEB] DuckDuckGo answered (${text.length} chars)", "WEB")
            WebSearchResult(text, "DuckDuckGo")
        }.getOrNull()
    }
}
