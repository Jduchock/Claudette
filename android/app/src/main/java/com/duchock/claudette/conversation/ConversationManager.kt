package com.duchock.claudette.conversation

import android.util.Log
import com.duchock.claudette.bible.BibleTools
import com.duchock.claudette.demo.DemoMode
import com.duchock.claudette.demo.InventoryTools
import com.duchock.claudette.location.LocationTools
import com.duchock.claudette.memory.MemoryStore
import com.duchock.claudette.memory.MemoryUpdater
import com.duchock.claudette.net.ClaudeClient
import com.duchock.claudette.util.DebugStatus
import org.json.JSONArray

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
    private val idleResetMs: Long = 30 * 60_000 // forget after 30 min idle
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
        DebugStatus.demoMode = DemoMode.active  // reflect on the main screen
        Log.i(TAG, "handle demo=${DemoMode.active} textLen=${userText.length}")

        val snapshot = synchronized(this) { ArrayList(history) }

        val reply = if (DemoMode.active) {
            // Sonnet keeps tool round-trips snappy for a live demo. Web search is DISABLED for now,
            // so demo turns use the inventory tools only.
            val system = Persona.SYSTEM + "\n" + DemoMode.ADDENDUM
            claude.respondWithTools(system, snapshot, Router.SONNET, InventoryTools.schema()) { name, input ->
                InventoryTools.execute(name, input)
            }
        } else {
            // Normal path: personal memory + location/places + Bible tools she can call on demand.
            val system = Persona.SYSTEM + MemoryStore.memoryBlock()
            val tools = JSONArray()
            LocationTools.schema().let { for (i in 0 until it.length()) tools.put(it.get(i)) }
            BibleTools.schema().let { for (i in 0 until it.length()) tools.put(it.get(i)) }
            claude.respondWithTools(system, snapshot, Router.pick(userText), tools) { name, input ->
                if (name.startsWith("bible_")) BibleTools.execute(name, input)
                else LocationTools.execute(name, input)
            }
        }

        if (reply == null) {
            // Couldn't reach the model ("trouble reaching my brain"). FORGET the question we just
            // added, so it is not replayed and answered alongside the next thing John asks.
            synchronized(this) {
                val i = history.indexOfLast { it.role == "user" }
                if (i >= 0) history.removeAt(i)
            }
            return null
        }

        synchronized(this) {
            history.add(Turn("assistant", reply))
            while (history.size > maxTurns) history.removeAt(0)
            lastActivity = System.currentTimeMillis()
        }
        return reply
    }

    /**
     * Vision turn: John showed Nova a photo. She analyzes it once and her description is folded
     * into the shared conversation history (as text) so follow-up questions keep the context
     * without re-sending the image. Returns her spoken-style reply.
     */
    suspend fun handleImage(imageB64: String, mediaType: String): String? {
        val now = System.currentTimeMillis()
        val snapshot = synchronized(this) {
            if (lastActivity != 0L && now - lastActivity > idleResetMs) history.clear()
            ArrayList(history)
        }

        // In demo mode a photo means "match this item to inventory". Two-phase for speed: (1) ONE fast
        // Sonnet vision pass identifies the item as text (image read once, no tools, no web search);
        // (2) a text-only inventory lookup + complimentary answer. Out of demo mode she just describes it.
        val demo = DemoMode.active
        Log.i(TAG, "handleImage demo=$demo b64=${imageB64.length} type=$mediaType")
        var identity: String? = null
        val reply: String? = if (demo) {
            DebugStatus.analyzingImage = true
            try {
                // Phase 1 -- identify (Sonnet, no tools, no history so it stays fast and focused).
                val ident = claude.respondWithImage(
                    DemoMode.IDENTIFY_SYSTEM, emptyList(), imageB64, mediaType,
                    DemoMode.IDENTIFY_PROMPT, Router.SONNET, maxTokens = 200
                )
                if (ident.isNullOrBlank()) {
                    Log.w(TAG, "phase-1 identify returned null (lastError=${DebugStatus.lastError})")
                    null
                } else {
                    val id = ident.trim()
                    identity = id
                    Log.i(TAG, "identified item: ${id.take(120)}")
                    DebugStatus.event("Identified: ${id.take(40)}")
                    // Phase 2 -- text-only inventory lookup + spoken answer (fast; no image resent).
                    val system = Persona.SYSTEM + "\n" + DemoMode.ADDENDUM + "\n" + DemoMode.IMAGE_ADDENDUM
                    val idTurn = Turn("user", "A customer just showed us this item: $id. " +
                        "Match it to our inventory and tell me about it.")
                    val phase2 = ArrayList(snapshot).apply { add(idTurn) }
                    claude.respondWithTools(system, phase2, Router.SONNET, InventoryTools.schema()) { name, input ->
                        InventoryTools.execute(name, input)
                    }
                }
            } finally {
                DebugStatus.analyzingImage = false
            }
        } else {
            val system = Persona.SYSTEM + MemoryStore.memoryBlock()
            val prompt = "John just showed you this photo. In one or two spoken-word sentences, tell him " +
                "what it is and anything useful you notice. Expect follow-up questions, so read it carefully."
            claude.respondWithImage(system, snapshot, imageB64, mediaType, prompt, Router.SONNET)
        }
        // Fold the identity into the history marker so spoken follow-ups know what the photo was.
        val userMarker = if (demo)
            "[Showed you an item to match to inventory" + (identity?.let { " -- identified as: $it" } ?: "") + ".]"
        else "[Showed you a photo.]"

        if (reply == null) return null
        synchronized(this) {
            history.add(Turn("user", userMarker))
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

    companion object { private const val TAG = "NovaConvo" }
}
