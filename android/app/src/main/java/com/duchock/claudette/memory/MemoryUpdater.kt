package com.duchock.claudette.memory

import android.util.Log
import com.duchock.claudette.conversation.Router
import com.duchock.claudette.conversation.Turn
import com.duchock.claudette.demo.DemoMode
import com.duchock.claudette.net.ClaudeClient
import org.json.JSONObject

/**
 * The "reflection" step: after a conversation ends, distill anything worth keeping into
 * Nova's long-term memory (MemoryStore). Runs one cheap Sonnet call, merges new facts into
 * the profile, and refreshes the rolling summary. Skipped entirely during demo mode so store
 * questions never pollute John's personal memory.
 */
object MemoryUpdater {

    suspend fun reflect(claude: ClaudeClient, turns: List<Turn>) {
        if (DemoMode.active) return
        val convo = turns.filter { it.content.isNotBlank() }
        if (convo.none { it.role == "user" }) return

        val transcript = convo.joinToString("\n") {
            (if (it.role == "user") "John" else "Nova") + ": " + it.content
        }
        val userMsg = buildString {
            append("CURRENT PROFILE:\n").append(MemoryStore.profile().ifBlank { "(empty)" }).append("\n\n")
            append("CURRENT SUMMARY:\n").append(MemoryStore.summary().ifBlank { "(empty)" }).append("\n\n")
            append("NEW CONVERSATION:\n").append(transcript).append("\n\n")
            append("Return the updated memory as JSON now.")
        }

        val reply = claude.respond(REFLECT_SYSTEM, listOf(Turn("user", userMsg)), Router.SONNET, maxTokens = 900)
            ?: return
        val json = extractJson(reply) ?: run { Log.w(TAG, "no JSON in reflection reply"); return }
        try {
            val o = JSONObject(json)
            val p = o.optString("profile", MemoryStore.profile())
            val s = o.optString("summary", MemoryStore.summary())
            if (p.isNotBlank() || s.isNotBlank()) {
                MemoryStore.update(p, s)
                Log.i(TAG, "Memory updated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reflection parse failed: ${reply.take(160)}", e)
        }
    }

    private fun extractJson(s: String): String? {
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        return if (a >= 0 && b > a) s.substring(a, b + 1) else null
    }

    private val REFLECT_SYSTEM = """
        You maintain Nova's long-term memory of John, the person she talks with. You are given
        the current memory and a new conversation, and you output the UPDATED memory.

        Output ONLY a JSON object, nothing else:
        {"profile": "...", "summary": "..."}

        - profile: durable facts about John -- his work, projects, the people in his life, his
          preferences, his values, and ongoing goals. MERGE new facts into the existing profile
          and REVISE anything that changed (do not keep stale/contradicted facts). Keep it tight,
          one short fact per line, no fluff. Do not invent anything not supported by the
          conversation. Max about 1500 characters.
        - summary: a brief running narrative (2-5 sentences) of what's recently been going on
          with John and where things stand. Max about 600 characters.

        Never store secrets such as passwords, API keys, or full card numbers even if mentioned.
        If the new conversation adds nothing durable, return the existing memory unchanged.
        Return JSON only -- no explanation, no markdown.
    """.trimIndent()

    private const val TAG = "MemoryUpdater"
}
