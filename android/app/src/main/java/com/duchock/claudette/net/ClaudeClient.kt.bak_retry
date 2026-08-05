package com.duchock.claudette.net

import android.util.Log
import com.duchock.claudette.conversation.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Anthropic Messages API client. Non-streaming for the Phase 2 framework;
 * SSE streaming + sentence chunking is a Phase 2b enhancement.
 *
 * respond()          -- plain single-shot text reply (everyday conversation).
 * respondWithTools() -- runs the tool_use / tool_result loop so Nova can call the demo
 *                       inventory tools and speak the returned data (deterministic answers).
 */
class ClaudeClient(
    private val apiKey: String,
    private val http: OkHttpClient
) {
    /** Returns the assistant's text reply, or null on error. */
    suspend fun respond(system: String, history: List<Turn>, model: String, maxTokens: Int = 1024): String? =
        withContext(Dispatchers.IO) {
            try {
                val messages = JSONArray()
                for (t in history) {
                    messages.put(JSONObject().put("role", t.role).put("content", t.content))
                }
                val payload = JSONObject()
                    .put("model", model)
                    .put("max_tokens", maxTokens)
                    .put("system", system)
                    .put("messages", messages)

                postForText(payload)
            } catch (e: Exception) {
                Log.e(TAG, "Claude call failed", e)
                null
            }
        }

    /**
     * Tool-enabled turn. Sends [tools]; whenever the model emits tool_use blocks, [exec] is
     * invoked (name, input) -> compact JSON string, the results are fed back, and the loop
     * continues until the model returns a final text answer (or [maxRounds] is hit, after
     * which tools are dropped to force a text reply). Returns the final text, or null.
     */
    suspend fun respondWithTools(
        system: String,
        history: List<Turn>,
        model: String,
        tools: JSONArray,
        maxTokens: Int = 1024,
        maxRounds: Int = 4,
        exec: suspend (name: String, input: JSONObject) -> String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()
            for (t in history) {
                messages.put(JSONObject().put("role", t.role).put("content", t.content))
            }

            var round = 0
            while (true) {
                val attachTools = round < maxRounds
                val payload = JSONObject()
                    .put("model", model)
                    .put("max_tokens", maxTokens)
                    .put("system", system)
                    .put("messages", messages)
                if (attachTools) payload.put("tools", tools)

                val root = postForJson(payload) ?: return@withContext null
                val content = root.optJSONArray("content") ?: return@withContext null
                val stopReason = root.optString("stop_reason")

                if (attachTools && stopReason == "tool_use") {
                    // Echo the assistant's tool_use turn back verbatim.
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                    // Execute each tool_use and return tool_result blocks.
                    val results = JSONArray()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") == "tool_use") {
                            val id = block.optString("id")
                            val name = block.optString("name")
                            val input = block.optJSONObject("input") ?: JSONObject()
                            val out = try {
                                exec(name, input)
                            } catch (e: Exception) {
                                Log.e(TAG, "tool $name failed", e)
                                JSONObject().put("error", e.message ?: "tool error").toString()
                            }
                            results.put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", id)
                                    .put("content", out)
                            )
                        }
                    }
                    messages.put(JSONObject().put("role", "user").put("content", results))
                    round++
                    continue
                }

                // Final answer: concatenate text blocks.
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    if (b.optString("type") == "text") sb.append(b.optString("text"))
                }
                return@withContext sb.toString().trim().ifEmpty { null }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Claude tool call failed", e)
            null
        }
    }

    /**
     * One-shot vision turn: sends [history] plus a final user message carrying an image and a
     * [prompt], and returns Nova's text reply. Used when John feeds her a photo.
     */
    suspend fun respondWithImage(
        system: String,
        history: List<Turn>,
        imageB64: String,
        mediaType: String,
        prompt: String,
        model: String,
        maxTokens: Int = 1024
    ): String? = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()
            for (t in history) messages.put(JSONObject().put("role", t.role).put("content", t.content))
            val content = JSONArray()
            content.put(
                JSONObject().put("type", "image").put(
                    "source", JSONObject()
                        .put("type", "base64").put("media_type", mediaType).put("data", imageB64)
                )
            )
            content.put(JSONObject().put("type", "text").put("text", prompt))
            messages.put(JSONObject().put("role", "user").put("content", content))
            val payload = JSONObject()
                .put("model", model).put("max_tokens", maxTokens)
                .put("system", system).put("messages", messages)
            postForText(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Claude image call failed", e); null
        }
    }

    // ---- HTTP helpers ----
    private fun buildRequest(payload: JSONObject): Request =
        Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

    private fun postForJson(payload: JSONObject): JSONObject? {
        http.newCall(buildRequest(payload)).execute().use { resp ->
            val bodyStr = resp.body?.string()
            if (!resp.isSuccessful || bodyStr == null) {
                Log.e(TAG, "Claude HTTP ${resp.code}: ${bodyStr?.take(300)}")
                return null
            }
            return JSONObject(bodyStr)
        }
    }

    private fun postForText(payload: JSONObject): String? {
        val root = postForJson(payload) ?: return null
        val content = root.optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().trim().ifEmpty { null }
    }

    companion object { private const val TAG = "ClaudeClient" }
}
