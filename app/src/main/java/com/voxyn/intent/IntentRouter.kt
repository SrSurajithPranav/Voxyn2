package com.voxyn.intent

import com.voxyn.core.ActionStep
import com.voxyn.core.IntentResult
import com.voxyn.utils.VoxynLogger
import org.json.JSONArray

data class IntentDef(
    val action: String,
    val baseConfidence: Float,
    val patterns: List<Regex>,
    val paramExtractors: Map<String, Int> = emptyMap()
)

class IntentRouter {

    private val intents = listOf(

        // ─── Accessibility & System Interaction ──────────────────────────
        IntentDef("SEND_CURRENT_MESSAGE", 0.95f, listOf(
            Regex("send\\s+(that|it|this\\s*message)", RegexOption.IGNORE_CASE),
            Regex("^send$", RegexOption.IGNORE_CASE),
            Regex("^send\\s+it$", RegexOption.IGNORE_CASE)
        )),

        IntentDef("LIKE_CURRENT", 0.95f, listOf(
            Regex("like\\s+(this|it)", RegexOption.IGNORE_CASE),
            Regex("^thumbs\\s*up$", RegexOption.IGNORE_CASE),
            Regex("^like$", RegexOption.IGNORE_CASE)
        )),

        IntentDef("SCROLL_DOWN", 0.94f, listOf(
            Regex("scroll\\s*down", RegexOption.IGNORE_CASE),
            Regex("go\\s*down", RegexOption.IGNORE_CASE),
            Regex("swipe\\s*up", RegexOption.IGNORE_CASE)
        )),

        IntentDef("SCROLL_UP", 0.94f, listOf(
            Regex("scroll\\s*up", RegexOption.IGNORE_CASE),
            Regex("go\\s*up", RegexOption.IGNORE_CASE),
            Regex("swipe\\s*down", RegexOption.IGNORE_CASE)
        )),

        IntentDef("GO_BACK", 0.95f, listOf(
            Regex("go\\s*back", RegexOption.IGNORE_CASE),
            Regex("^back$", RegexOption.IGNORE_CASE),
            Regex("^previous$", RegexOption.IGNORE_CASE)
        )),

        IntentDef("GO_HOME", 0.95f, listOf(
            Regex("go\\s*home", RegexOption.IGNORE_CASE),
            Regex("home\\s*screen", RegexOption.IGNORE_CASE),
            Regex("^home$", RegexOption.IGNORE_CASE)
        )),

        IntentDef("READ_NOTIFICATIONS", 0.93f, listOf(
            Regex("read\\s*notifications?", RegexOption.IGNORE_CASE),
            Regex("read\\s*(my\\s*)?messages?", RegexOption.IGNORE_CASE),
            Regex("what\\s*did\\s*i\\s*miss", RegexOption.IGNORE_CASE),
            Regex("read\\s*alerts?", RegexOption.IGNORE_CASE)
        )),

        IntentDef("REMEMBER_THIS", 0.95f, listOf(
            Regex("remember\\s*this", RegexOption.IGNORE_CASE),
            Regex("save\\s*this", RegexOption.IGNORE_CASE),
            Regex("clip\\s*this", RegexOption.IGNORE_CASE)
        )),

        // ─── WiFi ──────────────────────────────────────────────────────
        IntentDef("WIFI_ON", 0.93f, listOf(
            Regex("turn\\s*(on|enable)\\s*wi.?fi", RegexOption.IGNORE_CASE),
            Regex("wi.?fi\\s*(on|enable|connect)", RegexOption.IGNORE_CASE),
            Regex("enable\\s*wi.?fi", RegexOption.IGNORE_CASE)
        )),
        IntentDef("WIFI_OFF", 0.93f, listOf(
            Regex("turn\\s*(off|disable)\\s*wi.?fi", RegexOption.IGNORE_CASE),
            Regex("wi.?fi\\s*(off|disable|disconnect)", RegexOption.IGNORE_CASE)
        )),

        // ─── Bluetooth ─────────────────────────────────────────────────
        IntentDef("BLUETOOTH_ON", 0.92f, listOf(
            Regex("turn\\s*(on|enable)\\s*bluetooth", RegexOption.IGNORE_CASE),
            Regex("bluetooth\\s*(on|enable)", RegexOption.IGNORE_CASE),
            Regex("enable\\s*bluetooth", RegexOption.IGNORE_CASE)
        )),
        IntentDef("BLUETOOTH_OFF", 0.92f, listOf(
            Regex("turn\\s*(off|disable)\\s*bluetooth", RegexOption.IGNORE_CASE),
            Regex("bluetooth\\s*(off|disable)", RegexOption.IGNORE_CASE)
        )),

        // ─── Volume ────────────────────────────────────────────────────
        IntentDef("VOLUME_UP", 0.92f, listOf(
            Regex("volume\\s*(up|increase|louder|raise)", RegexOption.IGNORE_CASE),
            Regex("(increase|raise|turn\\s*up)\\s*(the\\s*)?volume", RegexOption.IGNORE_CASE),
            Regex("^louder$", RegexOption.IGNORE_CASE)
        )),
        IntentDef("VOLUME_DOWN", 0.92f, listOf(
            Regex("volume\\s*(down|decrease|lower|quiet)", RegexOption.IGNORE_CASE),
            Regex("(decrease|lower|turn\\s*down)\\s*(the\\s*)?volume", RegexOption.IGNORE_CASE),
            Regex("^quieter$", RegexOption.IGNORE_CASE)
        )),

        // ─── Silent ────────────────────────────────────────────────────
        IntentDef("SILENT_MODE", 0.93f, listOf(
            Regex("(silent|silence|mute)\\s*(mode|phone|device)?", RegexOption.IGNORE_CASE),
            Regex("(put|set)\\s*(on\\s*)?silent", RegexOption.IGNORE_CASE),
            Regex("do not disturb", RegexOption.IGNORE_CASE)
        )),
        IntentDef("VIBRATE_MODE", 0.90f, listOf(
            Regex("vibrate\\s*(mode)?", RegexOption.IGNORE_CASE)
        )),
        IntentDef("NORMAL_MODE", 0.90f, listOf(
            Regex("(normal|ring|ringer)\\s*mode", RegexOption.IGNORE_CASE),
            Regex("unmute", RegexOption.IGNORE_CASE)
        )),

        // ─── Flashlight ────────────────────────────────────────────────
        IntentDef("FLASHLIGHT_ON", 0.95f, listOf(
            Regex("turn\\s*(on|enable)\\s*(the\\s*)?flashlight", RegexOption.IGNORE_CASE),
            Regex("flashlight\\s*(on|enable)", RegexOption.IGNORE_CASE),
            Regex("torch\\s*(on|enable|please)?", RegexOption.IGNORE_CASE),
            Regex("(switch|put)\\s*(on\\s*)?torch", RegexOption.IGNORE_CASE)
        )),
        IntentDef("FLASHLIGHT_OFF", 0.95f, listOf(
            Regex("turn\\s*(off|disable)\\s*(the\\s*)?flashlight", RegexOption.IGNORE_CASE),
            Regex("flashlight\\s*(off|disable)", RegexOption.IGNORE_CASE),
            Regex("torch\\s*(off|disable)", RegexOption.IGNORE_CASE)
        )),

        // ─── Brightness ────────────────────────────────────────────────
        IntentDef("BRIGHTNESS_UP", 0.90f, listOf(
            Regex("brightness\\s*(up|increase|higher|max)", RegexOption.IGNORE_CASE),
            Regex("(increase|raise)\\s*(the\\s*)?brightness", RegexOption.IGNORE_CASE),
            Regex("^brighter$", RegexOption.IGNORE_CASE)
        )),
        IntentDef("BRIGHTNESS_DOWN", 0.90f, listOf(
            Regex("brightness\\s*(down|decrease|lower|dim|min)", RegexOption.IGNORE_CASE),
            Regex("(decrease|lower|dim)\\s*(the\\s*)?brightness", RegexOption.IGNORE_CASE),
            Regex("^dimmer$", RegexOption.IGNORE_CASE)
        )),

        // ─── Screenshot ────────────────────────────────────────────────
        IntentDef("SCREENSHOT", 0.95f, listOf(
            Regex("take\\s*(a\\s*)?screenshot", RegexOption.IGNORE_CASE),
            Regex("^screenshot$", RegexOption.IGNORE_CASE),
            Regex("capture\\s*(the\\s*)?screen", RegexOption.IGNORE_CASE)
        )),

        // ─── Media ─────────────────────────────────────────────────────
        IntentDef("PLAY_MUSIC", 0.90f, listOf(
            Regex("play\\s*(music|song|audio|media|podcast|something)", RegexOption.IGNORE_CASE),
            Regex("(start|resume)\\s*(playing|music|song)", RegexOption.IGNORE_CASE)
        )),
        IntentDef("PAUSE_MUSIC", 0.90f, listOf(
            Regex("pause\\s*(music|song|audio|media)?", RegexOption.IGNORE_CASE),
            Regex("stop\\s*(playing|music|song)", RegexOption.IGNORE_CASE)
        )),
        IntentDef("NEXT_TRACK", 0.91f, listOf(
            Regex("(next|skip)\\s*(song|track|music)?", RegexOption.IGNORE_CASE)
        )),
        IntentDef("PREV_TRACK", 0.91f, listOf(
            Regex("(previous|prev|back)\\s*(song|track|music)?", RegexOption.IGNORE_CASE)
        )),

        // ─── Alarm & Timer ─────────────────────────────────────────────
        IntentDef("SET_ALARM", 0.92f, listOf(
            Regex("set\\s*(an?\\s*)?alarm", RegexOption.IGNORE_CASE),
            Regex("wake\\s*me\\s*(up)?", RegexOption.IGNORE_CASE),
            Regex("alarm\\s*(at|for)", RegexOption.IGNORE_CASE)
        )),
        IntentDef("SET_TIMER", 0.92f, listOf(
            Regex("set\\s*(a\\s*)?timer", RegexOption.IGNORE_CASE),
            Regex("start\\s*(a\\s*)?timer", RegexOption.IGNORE_CASE),
            Regex("\\d+\\s*(minute|min|second|sec|hour)s?\\s*timer", RegexOption.IGNORE_CASE)
        )),

        // ─── Battery ───────────────────────────────────────────────────
        IntentDef("BATTERY_SAVER_ON", 0.91f, listOf(
            Regex("(turn\\s*on|enable)\\s*battery\\s*sav", RegexOption.IGNORE_CASE),
            Regex("battery\\s*saver", RegexOption.IGNORE_CASE)
        )),
        IntentDef("BATTERY_STATUS", 0.90f, listOf(
            Regex("(battery|charge)\\s*(level|status|percent)?", RegexOption.IGNORE_CASE),
            Regex("how\\s*much\\s*(battery|charge)", RegexOption.IGNORE_CASE)
        )),

        // ─── Navigation ────────────────────────────────────────────────
        IntentDef("NAVIGATE", 0.91f, listOf(
<<<<<<< HEAD
            Regex("navigate\\s*to\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("directions?\\s*to\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("take\\s*me\\s*to\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("how\\s*do\\s*i\\s*get\\s*to\\s*(.+)", RegexOption.IGNORE_CASE)
        ), paramExtractors = mapOf("destination" to 1)),
=======
            Regex("navigate\\s*(to)?\\s+(.+?)\\s+then\\s+(.+?)\\s+and\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("(visit|go to)\\s+(.+?)\\s+and\\s+then\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("navigate\\s*(to)?\\s+(.+?,.+)", RegexOption.IGNORE_CASE),
            Regex("navigate\\s*(to)?\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("(take|show|give)\\s*me\\s*(directions?|route)\\s*(to)?\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("(go|directions?)\\s*(to)?\\s*(.+)", RegexOption.IGNORE_CASE),
        )),

        // ─── Messaging ─────────────────────────────────────────────────
        IntentDef("SEND_MESSAGE", 0.92f, listOf(
            Regex("message\\s+(.+?)\\s+to\\s+(.+?)\\s+on\\s+(whatsapp|sms|instagram)", RegexOption.IGNORE_CASE),
            Regex("send\\s+text\\s+(.+?)\\s+to\\s+(.+?)\\s+on\\s+(whatsapp|sms|instagram)", RegexOption.IGNORE_CASE),
            Regex("send\\s+message\\s+(.+?)\\s+to\\s+(.+?)\\s+on\\s+(whatsapp|sms|instagram)", RegexOption.IGNORE_CASE),
        ), paramExtractors = mapOf("message" to 1, "contact" to 2, "platform" to 3)),

        // ─── Call ──────────────────────────────────────────────────────
        IntentDef("CALL_CONTACT", 0.90f, listOf(
            Regex("call\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("(phone|ring|dial)\\s+(.+)", RegexOption.IGNORE_CASE),
        ), paramExtractors = mapOf("contact" to 1)),
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)

        // ─── Mode System ───────────────────────────────────────────────
        IntentDef("MODE_ACTIVATE", 0.95f, listOf(
            Regex("(driving|drive)\\s*mode", RegexOption.IGNORE_CASE),
            Regex("(study|focus)\\s*mode", RegexOption.IGNORE_CASE),
            Regex("(sleep|bedtime|night)\\s*mode", RegexOption.IGNORE_CASE),
            Regex("(meeting|office)\\s*mode", RegexOption.IGNORE_CASE),
            Regex("(morning|good\\s*morning)\\s*mode", RegexOption.IGNORE_CASE)
        )),

        // ─── Lock ──────────────────────────────────────────────────────
        IntentDef("LOCK_PHONE", 0.93f, listOf(
            Regex("lock\\s*(the\\s*)?(phone|screen|device)", RegexOption.IGNORE_CASE),
            Regex("turn\\s*off\\s*(the\\s*)?screen", RegexOption.IGNORE_CASE)
        )),

        // ─── Open App ──────────────────────────────────────────────────
        IntentDef("OPEN_APP", 0.88f, listOf(
            Regex("^open\\s+(\\w+)$", RegexOption.IGNORE_CASE),
            Regex("^launch\\s+(\\w+)$", RegexOption.IGNORE_CASE),
            Regex("^start\\s+(\\w+)$", RegexOption.IGNORE_CASE),
            Regex("^go\\s+to\\s+(\\w+)$", RegexOption.IGNORE_CASE),
            Regex("open\\s+the\\s+(\\w+)\\s*app", RegexOption.IGNORE_CASE)
        ), paramExtractors = mapOf("app_name" to 1)),

        // ─── Call ──────────────────────────────────────────────────────
        IntentDef("CALL_CONTACT", 0.90f, listOf(
            Regex("^call\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(phone|ring|dial)\\s+(.+)$", RegexOption.IGNORE_CASE)
        ), paramExtractors = mapOf("contact" to 1))
    )

    fun route(perceived: String): IntentResult {
        val trimmed = perceived.trim()

        // Check for smart chain commands joined by "and" or "then"
        if (trimmed.contains(" and ", ignoreCase = true) || trimmed.contains(" then ", ignoreCase = true)) {
            val parts = trimmed.split(Regex("\\s+(and|then)\\s+", RegexOption.IGNORE_CASE))
            if (parts.size > 1) {
                val stepActions = mutableListOf<String>()
                for (part in parts) {
                    val subIntent = routeSingle(part)
                    if (subIntent.action != "UNKNOWN") {
                        if (subIntent.action == "OPEN_APP" && subIntent.params.containsKey("app_name")) {
                            stepActions.add("OPEN_APP:${subIntent.params["app_name"]}")
                        } else {
                            stepActions.add(subIntent.action)
                        }
                    }
                }
<<<<<<< HEAD
                if (stepActions.size > 1) {
                    val jsonArray = JSONArray()
                    stepActions.forEach { jsonArray.put(it) }
                    VoxynLogger.i("Chain command routed: $stepActions", "INTENT")
                    return IntentResult(
                        action = "CHAIN",
                        params = mapOf("steps" to jsonArray.toString()),
                        confidence = 0.95f,
=======

                if (intent.action == "NAVIGATE") {
                    val destinations = extractDestinationsFromMatch(match.groupValues)
                    if (destinations.isNotEmpty()) {
                        params["destinations"] = destinations.joinToString("|")
                        params["destination"] = destinations.last()
                    }
                }

                if (intent.action == "SEND_MESSAGE") {
                    params["platform"] = params["platform"]?.lowercase() ?: "sms"
                }

                if (intent.baseConfidence > bestConfidence) {
                    bestConfidence = intent.baseConfidence
                    bestMatch = IntentResult(
                        action = intent.action,
                        params = params,
                        confidence = bestConfidence,
>>>>>>> 2c02828 (WIP: Copilot improvements before sync)
                        rawInput = perceived
                    )
                }
            }
        }

        return routeSingle(trimmed)
    }

    private fun routeSingle(trimmed: String): IntentResult {
        var bestMatch: IntentResult? = null
        var bestConfidence = 0f

        for (intent in intents) {
            for (pattern in intent.patterns) {
                val match = pattern.find(trimmed) ?: continue
                val params = mutableMapOf<String, String>()

                for ((paramName, groupIndex) in intent.paramExtractors) {
                    runCatching {
                        val value = match.groupValues.getOrNull(groupIndex)?.trim() ?: ""
                        if (value.isNotEmpty()) params[paramName] = value
                    }
                }

                if (intent.action == "MODE_ACTIVATE") {
                    params["mode_name"] = when {
                        trimmed.contains("driv", ignoreCase = true)    -> "driving"
                        trimmed.contains("study", ignoreCase = true)   -> "study"
                        trimmed.contains("focus", ignoreCase = true)   -> "study"
                        trimmed.contains("sleep", ignoreCase = true)   -> "sleep"
                        trimmed.contains("night", ignoreCase = true)   -> "sleep"
                        trimmed.contains("meeting", ignoreCase = true) -> "meeting"
                        trimmed.contains("morning", ignoreCase = true) -> "morning"
                        else -> "unknown"
                    }
                }

                if (intent.baseConfidence > bestConfidence) {
                    bestConfidence = intent.baseConfidence
                    bestMatch = IntentResult(
                        action = intent.action,
                        params = params,
                        confidence = bestConfidence,
                        rawInput = trimmed
                    )
                }
            }
        }

        if (bestMatch != null) {
            VoxynLogger.i("Intent: ${bestMatch.action} (${bestMatch.confidence}) params=${bestMatch.params}", "INTENT")
            return bestMatch
        }

        VoxynLogger.w("No intent matched: '$trimmed'", "INTENT")
        return IntentResult(action = "UNKNOWN", rawInput = trimmed, confidence = 0.1f)
    }

    fun fallback(intent: IntentResult): IntentResult? = null

    fun buildSteps(intent: IntentResult): List<ActionStep> = listOf(
        ActionStep(
            id = "step_0",
            description = intent.action,
            actionType = intent.action,
            params = intent.params
        )
    )

    private fun extractDestinationsFromMatch(groups: List<String>): List<String> {
        val combined = groups.drop(1)
            .firstOrNull { it.contains("then", ignoreCase = true) || it.contains(",") }
            ?: groups.drop(1).firstOrNull { it.isNotBlank() }
            ?: return emptyList()

        return combined
            .replace(Regex("\\s+and\\s+then\\s+", RegexOption.IGNORE_CASE), " then ")
            .replace(Regex("\\s+then\\s+", RegexOption.IGNORE_CASE), "|")
            .replace(" and ", "|")
            .replace(",", "|")
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
