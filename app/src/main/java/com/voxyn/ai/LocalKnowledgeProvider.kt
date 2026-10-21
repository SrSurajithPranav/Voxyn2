package com.voxyn.ai

/**
 * LocalKnowledgeProvider — offline answers that need no network or LLM.
 * Covers jokes and graceful offline notices. Everything else is routed to the
 * cloud knowledge provider; when that is unavailable VOXYN says so instead of
 * fabricating an answer.
 */
class LocalKnowledgeProvider {

    fun joke(): String = JOKES.random()

    /** Used when no local/web/cloud provider could answer a general question. */
    fun cantFind(): String = "I couldn't find an answer to that, Sir. Try asking it a different way."

    fun offlineNotice(): String = OFFLINE_NOTICE

    companion object {
        private val OFFLINE_NOTICE = "I can't access current information right now, Sir."

        private val JOKES = listOf(
            "Why do programmers prefer dark mode? Because light attracts bugs.",
            "I told my phone a joke about a Wi-Fi network. Now it won't connect to me.",
            "Why did the smartphone need glasses? It lost all its apps.",
            "What do you call a fake noodle? An impasta.",
            "Why don't scientists trust atoms? Because they make up everything.",
            "I would tell you a UDP joke, but you might not get it.",
            "Why was the computer cold? It left its Windows open.",
            "What's a computer's favorite snack? Microchips.",
            "Why did the robot go on a diet? It had too many bytes.",
            "Parallel lines have so much in common. It's a shame they'll never meet."
        )
    }
}
