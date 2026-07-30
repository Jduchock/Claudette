package com.duchock.claudette.conversation

import com.duchock.claudette.demo.DemoMode
import com.duchock.claudette.demo.InventoryTools
import com.duchock.claudette.memory.MemoryStore
import com.duchock.claudette.memory.MemoryUpdater
import com.duchock.claudette.net.ClaudeClient

/**
 * Holds recent conversation memory (D9) and drives one turn: pick model, call Claude,
 * append the reply. Short-term memory resets after [idleResetMs] of silence.
 *
 * Long-term memory (D12/D16): on the normal path, Nova's persistent profile + rolling
 * summary (MemoryStore) are injected into her system prompt so she remembers John across
 * sessions; after a conversation ends the service calls reflect() to distill new facts back
 * into that store.
 *
 * Demo mode: the turn is routed through Claude's tool-use loop with the inventory tools
 * attached and the demo persona addendum added, and personal memory is left out.
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

    /** Runs one turn. Returns Nova's reply text, or null on failure. */
    suspend fun handle(userText: String): String? {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (lastActivity != 0L && now - lastActivity > idleResetMs) history.clear()
            history.add(Turn("user", userText))
            while (history.size > maxTurns) history.removeAt(0)
            lastActivity = now
        }

        // Demo-mode toggles (process-level; survives across wake-ups).
        if (DemoMode.detectStart(userText)) DemoMode.setActive(true)
        else if (DemoMode.active && DemoMode.detectStop(userText)) DemoMode.setActive(false)

        val snapshot = synchronized(this) { ArrayList(history) }

        val reply = if (DemoMode.active) {
            // Sonnet keeps tool round-trips snappy for a live demo.
            val system = Persona.SYSTEM + "\n" + DemoMode.ADDENDUM
            claude.respondWithTools(system, snapshot, Router.SONNET, InventoryTools.schema()) { name, input ->
                InventoryTools.execute(name, input)
            }
        } else {
            val system = Persona.SYSTEM + MemoryStore.memoryBlock()
            claude.respond(system, snapshot, Router.pick(userText))
        } ?: return null

        synchronized(this) {
            history.add(Turn("assistant", reply))
            while (history.size > maxTurns) history.removeAt(0)
            lastActivity = System.currentTimeMillis()
        }
        return reply
    }

    /** Distill this conversation into Nova's long-term memory. No-op during demo mode. */
    suspend fun reflect() {
        val snap = synchronized(this) { ArrayList(history) }
        MemoryUpdater.reflect(claude, snap)
    }
}
