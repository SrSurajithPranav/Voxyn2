package com.voxyn.voice

data class ConversationTurn(
    val userInput: String,
    val action: String,
    val response: String,
    val params: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

class ConversationSession(
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        /** Keeps a long follow-up exchange from becoming an unbounded in-memory log. */
        private const val MAX_TURNS = 8
    }

    private val lock = Any()
    private val storedTurns = mutableListOf<ConversationTurn>()
    private var active = false
    private var lastInteraction = 0L

    /** A snapshot prevents callers from mutating session state outside this class. */
    val turns: List<ConversationTurn>
        get() = synchronized(lock) { storedTurns.toList() }

    val isActive: Boolean
        get() = synchronized(lock) { active }

    val lastInteractionTime: Long
        get() = synchronized(lock) { lastInteraction }

    val windowMs = 30_000L

    fun isWithinWindow(): Boolean = remainingMs() > 0L

    fun remainingMs(): Long = synchronized(lock) {
        if (!active) return@synchronized 0L
        (windowMs - (clock() - lastInteraction)).coerceAtLeast(0L)
    }

    fun touch() {
        synchronized(lock) {
            if (active) lastInteraction = clock()
        }
    }

    fun addTurn(turn: ConversationTurn) {
        synchronized(lock) {
            storedTurns.add(turn)
            while (storedTurns.size > MAX_TURNS) storedTurns.removeAt(0)
        }
    }

    /**
     * Keeps a pending confirmation/clarification inside the current conversation
     * without adding a synthetic turn or reviving an expired session implicitly.
     */
    fun activate() {
        synchronized(lock) {
            active = true
            lastInteraction = clock()
        }
    }

    fun updateLastTurn(action: String, response: String, params: Map<String, String> = emptyMap()) {
        synchronized(lock) {
            val last = storedTurns.lastOrNull() ?: return
            val idx = storedTurns.size - 1
            storedTurns[idx] = ConversationTurn(
                last.userInput,
                action,
                response,
                params.ifEmpty { last.params },
                last.timestamp
            )
            active = true
            lastInteraction = clock()
        }
    }

    fun getContext(): String = synchronized(lock) {
        storedTurns.takeLast(3)
            .joinToString("\n") { "User: ${it.userInput}\nVOXYN: ${it.response}" }
    }

    fun end() {
        synchronized(lock) {
            active = false
            lastInteraction = 0L
            storedTurns.clear()
        }
    }

    fun lastTurn(): ConversationTurn? = synchronized(lock) { storedTurns.lastOrNull() }
}
