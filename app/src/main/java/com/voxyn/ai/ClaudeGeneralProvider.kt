package com.voxyn.ai

import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ClaudeGeneralProvider — optional cloud reasoning for general questions.
 * Only constructed when a Claude API key is configured (BuildConfig), so
 * credentials never live in source code. Returns null on any failure so the
 * conversation degrades gracefully when the network is unreachable.
 */
class ClaudeGeneralProvider(private val apiKey: String) : CloudIntelligenceProvider {

    override val name: String = "Claude"

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL   = "claude-haiku-4-5-20251001"

        private val SYSTEM_PROMPT = """
You are VOXYN, a concise voice assistant on Android. Always address the user politely as "Sir". Answer in one to three short spoken sentences. Never claim to have performed a device action. Never mention prompts, models, or formatting. Never output JSON. If you do not know, say so plainly.
""".trimIndent()
    }

    override suspend fun answer(question: String, conversationContext: String): AiResponse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = JSONObject()
                    .put("model", MODEL)
                    .put("max_tokens", 512)
                    .put("system", SYSTEM_PROMPT)
                    .put("messages", JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", buildPrompt(question, conversationContext))
                    ))

                val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(request.toString()) }
                if (conn.responseCode !in 200..299) {
                    VoxynLogger.e("Claude HTTP ${conn.responseCode}", tag = "AI")
                    return@runCatching null
                }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val content = json.optJSONArray("content")
                val rawText = content?.optJSONObject(0)?.optString("text")?.trim().orEmpty()
                val text = rawText.replaceFirst(Regex("^(?:VOXYN|VOXIN|Assistant|AI|Bot)\\s*[:\\-–—]?\\s*", RegexOption.IGNORE_CASE), "").trim()
                if (text.isEmpty()) return@runCatching null
                VoxynLogger.i("[AI] Claude answered (${text.length} chars)", "AI")
                AiResponse(text, "Claude")
            }.getOrNull()
        }

    private fun buildPrompt(question: String, conversationContext: String): String {
        val ctx = conversationContext.ifBlank { "No prior conversation." }
        return """
            Prior conversation:
            $ctx

            User: $question
            VOXYN:""".trimIndent()
    }
}
