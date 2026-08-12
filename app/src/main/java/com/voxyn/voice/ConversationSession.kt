package com.voxyn.voice

data class ConversationTurn(
    val userInput: String,
    val action: String,
    val response: String,
    val params: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

class ConversationSession {
    val turns = mutableListOf<ConversationTurn>()
    var isActive = false
    var lastInteractionTime = 0L
    val windowMs = 30_000L

    fun isWithinWindow(): Boolean = isActive && System.currentTimeMillis() - lastInteractionTime < windowMs

    fun addTurn(turn: ConversationTurn) {
        turns.add(turn)
        lastInteractionTime = System.currentTimeMillis()
        isActive = true
    }

    fun updateLastTurn(action: String, response: String, params: Map<String, String> = emptyMap()) {
        val last = turns.lastOrNull() ?: return
        val idx = turns.size - 1
        turns[idx] = ConversationTurn(last.userInput, action, response, params.ifEmpty { last.params }, last.timestamp)
    }

    fun getContext(): String = turns.takeLast(3).joinToString("\n") { "User: ${it.userInput}\nVOXYN: ${it.response}" }

    fun end() {
        isActive = false
        turns.clear()
    }

    fun lastTurn(): ConversationTurn? = turns.lastOrNull()
}
