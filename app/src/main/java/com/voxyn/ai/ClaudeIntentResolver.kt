package com.voxyn.ai

import com.voxyn.core.IntentResult
import com.voxyn.utils.VoxynLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ClaudeIntentResolver(private val apiKey: String) {

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL   = "claude-haiku-4-5-20251001"

        private val SYSTEM_PROMPT = """
You are VOXYN's intent parser. VOXYN is an Android AI assistant that executes real device actions.
Parse the user's voice command and return ONLY valid JSON. No explanation. No markdown. No backticks.

JSON FORMAT:
{
  "action": "ACTION_NAME",
  "confidence": 0.92,
  "params": { "key": "value" },
  "speak": "Short TTS confirmation after action"
}

SUPPORTED ACTIONS:
WIFI_ON / WIFI_OFF
BLUETOOTH_ON / BLUETOOTH_OFF  
VOLUME_UP / VOLUME_DOWN
SILENT_MODE / VIBRATE_MODE / NORMAL_MODE
FLASHLIGHT_ON / FLASHLIGHT_OFF
SCREENSHOT
BRIGHTNESS_UP / BRIGHTNESS_DOWN
AIRPLANE_ON / AIRPLANE_OFF
BATTERY_SAVER_ON / BATTERY_STATUS
PLAY_MUSIC / PAUSE_MUSIC / NEXT_TRACK / PREV_TRACK
OPEN_APP — params: {"app_name": "spotify"}
SEND_MESSAGE — params: {"contact": "John", "message": "On my way"}
CALL_CONTACT — params: {"contact": "Mom"}
SET_ALARM — params: {"time": "7:30am", "label": "Wake up"}
SET_TIMER — params: {"duration": "10 minutes"}
NAVIGATE — params: {"destination": "Starbucks", "mode": "driving"}
LOCK_PHONE
MODE_ACTIVATE — params: {"mode_name": "driving"}
SEND_CURRENT_MESSAGE
LIKE_CURRENT
SCROLL_DOWN / SCROLL_UP
GO_BACK / GO_HOME
READ_NOTIFICATIONS
REMEMBER_THIS
CHAIN — params: {"steps": "[\"OPEN_APP:spotify\", \"PLAY_MUSIC\", \"VOLUME_UP\"]"}
UNKNOWN — if nothing matches

CONTEXT RESOLUTION:
- Use the provided conversation history to resolve pronouns and follow-up commands.
- Example: history contains "turn on flashlight" and user says "turn it off" -> FLASHLIGHT_OFF.
- For multi-stop navigation, return params: {"destinations": "StopA|StopB|StopC"}.
- For messaging platform routing, return params: {"message": "...", "contact": "...", "platform": "whatsapp|sms|instagram"}.

EXAMPLES:
"Call my wife" → {"action":"CALL_CONTACT","confidence":0.95,"params":{"contact":"wife"},"speak":"Calling your wife."}
"Set a timer for pasta" → {"action":"SET_TIMER","confidence":0.97,"params":{"duration":"10 minutes","label":"pasta"},"speak":"Timer started."}
<<<<<<< HEAD
"Open spotify and play music" → {"action":"CHAIN","confidence":0.95,"params":{"steps":"[\"OPEN_APP:spotify\", \"PLAY_MUSIC\"]"},"speak":"Opening Spotify and playing music."}
"Scroll down" → {"action":"SCROLL_DOWN","confidence":0.96,"params":{},"speak":"Scrolling down."}
"Send this message" → {"action":"SEND_CURRENT_MESSAGE","confidence":0.97,"params":{},"speak":"Sending message."}
=======
"Navigate home" → {"action":"NAVIGATE","confidence":0.95,"params":{"destination":"home","mode":"driving"},"speak":"Opening navigation."}
"visit Central Park and then Times Square" → {"action":"NAVIGATE","confidence":0.95,"params":{"destinations":"Central Park|Times Square"},"speak":"Opening multi-stop navigation."}
"send text I am on my way to John on WhatsApp" → {"action":"SEND_MESSAGE","confidence":0.95,"params":{"message":"I am on my way","contact":"John","platform":"whatsapp"},"speak":"Sending your message."}
"Driving mode" → {"action":"MODE_ACTIVATE","confidence":0.98,"params":{"mode_name":"driving"},"speak":"Driving mode activated."}
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
        """.trimIndent()
    }

    suspend fun resolve(
        userInput: String,
        conversationHistory: List<String> = emptyList()
    ): ClaudeResult = withContext(Dispatchers.IO) {
        VoxynLogger.i("Claude resolving: \"$userInput\"", "CLAUDE")
        try {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 300)
                put("system", SYSTEM_PROMPT)
                put("messages", JSONArray().apply {
                    conversationHistory.takeLast(8).forEach { utterance ->
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "History: $utterance")
                        })
                    }
                    put(JSONObject().apply { put("role", "user"); put("content", userInput) })
                })
            }.toString()

            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
                doOutput = true; connectTimeout = 8000; readTimeout = 12000
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            if (conn.responseCode != 200) {
                VoxynLogger.e("Claude API ${conn.responseCode}", tag = "CLAUDE")
                return@withContext ClaudeResult.failed(userInput)
            }

            val content = JSONObject(conn.inputStream.bufferedReader().readText())
                .getJSONArray("content").getJSONObject(0).getString("text").trim()

            val parsed = JSONObject(content)
            val action = parsed.optString("action", "UNKNOWN")
            val confidence = parsed.optDouble("confidence", 0.75).toFloat()
            val speak = parsed.optString("speak", "")
            val paramsJson = parsed.optJSONObject("params")
            val params = mutableMapOf<String, String>()
            paramsJson?.keys()?.forEach { k -> params[k] = paramsJson.getString(k) }

            VoxynLogger.i("Claude → $action ($confidence)", "CLAUDE")
            ClaudeResult(
                intent = IntentResult(action = action, params = params, confidence = confidence, rawInput = userInput),
                speakOverride = speak.ifEmpty { null },
                success = true
            )
        } catch (e: Exception) {
            VoxynLogger.e("Claude failed: ${e.message}", tag = "CLAUDE")
            ClaudeResult.failed(userInput)
        }
    }
}

data class ClaudeResult(
    val intent: IntentResult,
    val speakOverride: String?,
    val success: Boolean
) {
    companion object {
        fun failed(raw: String) = ClaudeResult(
            intent = IntentResult(action = "UNKNOWN", rawInput = raw, confidence = 0f),
            speakOverride = null, success = false
        )
    }
}
