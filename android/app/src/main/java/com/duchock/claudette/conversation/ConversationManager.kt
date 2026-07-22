package com.duchock.claudette.conversation

import com.duchock.claudette.net.ClaudeClient

/**
 * Holds recent conversation memory (D9) and drives one turn: pick model, call Claude,
 * append the reply. Memory resets after [idleResetMs] of silence.
 */
class ConversationManager(
    private val claude: ClaudeClient,
    private val maxTurns: Int = 12,           // ~6 exchanges kept as context
    private val idleResetMs: Long = 5 * 60_000 // forget after 5 min idle
) {
    private val history = ArrayList<Turn>()
    private var lastActivity = 0L

    @Synchronized
    fun reset() { history.clear(); lastActivity = 0L }

    /** Runs one turn. Returns Claudette's reply text, or null on failure. */
    suspend fun handle(userText: String): String? {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (lastActivity != 0L && now - lastActivity > idleResetMs) history.clear()
            history.add(Turn("user", userText))
            while (history.size > maxTurns) history.removeAt(0)
            lastActivity = now
        }
        val model = Router.pick(userText)
        val snapshot = synchronized(this) { ArrayList(history) }
        val reply = claude.respond(Persona.SYSTEM, snapshot, model) ?: return null
        synchronized(this) {
            history.add(Turn("assistant", reply))
            while (history.size > maxTurns) history.removeAt(0)
            lastActivity = System.currentTimeMillis()
        }
        return reply
    }
}
