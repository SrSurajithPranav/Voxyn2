package com.voxyn.router

import com.voxyn.utils.VoxynLogger

/**
 * RequestRouter — cheap deterministic routing BEFORE any intent work or LLM call.
 *
 * Fast deterministic commands stay on the existing fast path. Everything that
 * needs memory, current information, web access, or general knowledge is routed
 * to its provider. Only when the fast path fails does the engine consider AI.
 */
enum class RouteType {
    FAST_COMMAND, CURRENT_INFORMATION, MEMORY_SAVE, MEMORY_RECALL,
    WEB_REQUIRED, WEATHER, DISTANCE, CONVERSATIONAL, UNKNOWN
}

data class RouteDecision(
    val type: RouteType,
    val params: Map<String, String> = emptyMap(),
    val confidence: Float = 0f
)

class RequestRouter {

    fun route(perceived: String): RouteDecision {
        val t = perceived.trim().lowercase()

        // ── Memory: "remember that my meeting with Rahul is Friday" ──────
        val save = Regex("^(remember|note|save|don't\\s*forget|dont\\s*forget|remind\\s*me\\s+that)\\s+(that\\s+|this\\s+)?(.+)$").find(t)
        if (save != null) {
            val note = save.groupValues[3].trim().trimEnd('.', '!')
            val isQuestion = Regex("^(what|who|why|when|where|how)\\b").containsMatchIn(note)
            val isScreenAction = note == "this" || note == "this screen" || note == "that"
            if (note.isNotBlank() && !isQuestion && !isScreenAction) {
                return RouteDecision(RouteType.MEMORY_SAVE, mapOf("note" to note), 0.9f)
            }
        }

        // ── Memory: "what did I tell you about Rahul?" ────────────────────
        val recall = Regex("^(?:what did i (?:tell|say|ask|mention|promise)(?: you)?(?: about)?|what do you (?:know|remember|have)(?: about)?|do you remember(?: about)?)\\s+(.+)$").find(t)
        if (recall != null) {
            val topic = recall.groupValues[1].trim().trimEnd('?', '.', '!')
            return RouteDecision(RouteType.MEMORY_RECALL, mapOf("topic" to topic), 0.85f)
        }

        // ── Current information: "what time is it in New York?" ───────────
        val worldTime = Regex("^what\\s+time\\s+is\\s+it\\s+in\\s+(.+)$").find(t)
        if (worldTime != null) {
            val city = worldTime.groupValues[1].trim().trimEnd('?', '.', '!')
            return RouteDecision(RouteType.CURRENT_INFORMATION, mapOf("city" to city), 0.92f)
        }

        // ── Weather: "what's the weather in Chennai?" ──────────────────────
        val weather = Regex("^what('s| is)? the weather(?: like)? in (.+)$").find(t)
            ?: Regex("^weather(?: like)? in (.+)$").find(t)
            ?: Regex("^(?:how (hot|cold|warm) is it) in (.+)$").find(t)
        if (weather != null) {
            val location = weather.groupValues.last().trim().trimEnd('?', '.', '!')
            return RouteDecision(RouteType.WEATHER, mapOf("location" to location), 0.92f)
        }

        // ── Distance: "distance between Chennai and Bangalore" ─────────────
        val distance = Regex("^distance between (.+) and (.+)$").find(t)
            ?: Regex("^how far is (.+) from (.+)$").find(t)
            ?: Regex("^how far is it from (.+) to (.+)$").find(t)
        if (distance != null) {
            return RouteDecision(
                RouteType.DISTANCE,
                mapOf(
                    "origin" to distance.groupValues[1].trim().trimEnd('?', '.', '!'),
                    "destination" to distance.groupValues[2].trim().trimEnd('?', '.', '!')
                ),
                0.9f
            )
        }
        if (t.startsWith("which is closer") || t.startsWith("which one is closer")) {
            return RouteDecision(RouteType.DISTANCE, mapOf("query" to perceived.trim()), 0.85f)
        }

        // ── Web required: "what's the latest news about OpenAI?" ──────────
        val newsIntent = Regex(
            "latest news|breaking news|headlines|current events|top stories|world news|tech news|" +
                "business news|news today|news about|news in|what's happening|what is happening|" +
                "what happened today|what's today's news|today's news"
        ).containsMatchIn(t) ||
            (t.contains("news") && Regex("^\\s*(what|whats|what's|give me|tell me|any|read|show)\\b").containsMatchIn(t)) ||
            (t.contains("what happened") && (t.contains("today") || t.contains("world")))
        if (newsIntent) {
            val query = when {
                t.contains("news about") -> t.substringAfter("news about").removePrefix("the ")
                t.contains("latest news") -> t.substringAfter("latest news")
                else -> ""
            }.trim().trimEnd('?', '.', '!').removePrefix("about ").removePrefix("on ")
            VoxynLogger.i("[ROUTER] news query=\"$query\"", "ROUTER")
            return RouteDecision(RouteType.WEB_REQUIRED, mapOf("query" to query), 0.9f)
        }

        // ── Explicit web search: "search the internet for X" ───────────────
        val webSearch = Regex("^search (?:the )?(?:web|internet|google)(?: for)? (.+)$").find(t)
        if (webSearch != null) {
            val query = webSearch.groupValues[1].trim().trimEnd('?', '.', '!')
            return RouteDecision(RouteType.WEB_REQUIRED, mapOf("query" to query), 0.9f)
        }

        // ── Smalltalk must stay on the fast path (SMALLTALK/GREETING/TIME/DATE) ──
        if (Regex("your name|how are you|are you|who are you|good job|great|what's up|whats up|what's that|what is that|the time|time is it|current time|the date|today|day is it").containsMatchIn(t)) {
            return RouteDecision(RouteType.FAST_COMMAND, emptyMap(), 0.8f)
        }

        // ── Knowledge / conversation: "what is quantum computing?" ────────
        if (Regex("^(?:what is|what are|what was|what does|what do|who is|who was|explain|define|tell me about|tell me what|how does|how do|why is|why do|how many|how much|what's the meaning|what is the meaning|meaning of|capital of|population of|what is the population)").containsMatchIn(t) ||
            t.contains("joke") ||
            Regex("\\b(capital|population|largest|tallest|biggest|distance between|difference between)\\b").containsMatchIn(t)
        ) {
            return RouteDecision(RouteType.CONVERSATIONAL, mapOf("query" to perceived.trim()), 0.85f)
        }

        return RouteDecision(RouteType.FAST_COMMAND, emptyMap(), 0.5f)
    }
}
