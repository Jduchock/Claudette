package com.duchock.claudette.net

import android.util.Log
import com.duchock.claudette.conversation.Turn
import com.duchock.claudette.util.DebugStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal Anthropic Messages API client. Non-streaming for the Phase 2 framework;
 * SSE streaming + sentence chunking is a Phase 2b enhancement.
 *
 * respond()          -- plain single-shot text reply (everyday conversation).
 * respondWithTools() -- runs the tool_use / tool_result loop so Nova can call the demo
 *                       inventory tools and speak the returned data (deterministic answers).
 *
 * Transient failures (timeouts, dropped connections, 429/5xx/529 overloaded) are retried a
 * few times with backoff so they don't surface to John as "trouble reaching my brain".
 */
class ClaudeClient(
    private val apiKey: String,
    http: OkHttpClient
) {
    // LLM turns can be slow; give them real headroom instead of OkHttp's short defaults.
    private val callClient: OkHttpClient = http.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

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
            val toolNames = (0 until tools.length()).joinToString(",") { tools.optJSONObject(it)?.optString("name").orEmpty() }
            Log.i(TAG, "respondWithTools START model=$model history=${history.size} tools=[$toolNames]")

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
                Log.i(TAG, "respondWithTools round=$round stop=$stopReason blocks=${content.length()}")

                // Server-side tool (web_search) still working: echo the turn back and resume.
                if (attachTools && stopReason == "pause_turn") {
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                    round++
                    continue
                }

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

    /**
     * Vision turn WITH tools. Like [respondWithTools], but the final user message also carries an
     * [imageB64] photo. Used in demo mode: Nova identifies the item in the picture, may call the
     * server-side web_search tool to confirm the make/model, then calls the inventory tools and
     * speaks the stock answer. Client tool_use blocks are run via [exec]; server tools (web_search)
     * execute API-side, so a "pause_turn" stop is resumed by echoing the turn back unchanged.
     */
    suspend fun respondWithImageAndTools(
        system: String,
        history: List<Turn>,
        imageB64: String,
        mediaType: String,
        prompt: String,
        model: String,
        tools: JSONArray,
        maxTokens: Int = 1024,
        maxRounds: Int = 5,
        exec: suspend (name: String, input: JSONObject) -> String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray()
            for (t in history) messages.put(JSONObject().put("role", t.role).put("content", t.content))
            // Final user turn: the photo followed by the instruction.
            val first = JSONArray()
            first.put(
                JSONObject().put("type", "image").put(
                    "source", JSONObject()
                        .put("type", "base64").put("media_type", mediaType).put("data", imageB64)
                )
            )
            first.put(JSONObject().put("type", "text").put("text", prompt))
            messages.put(JSONObject().put("role", "user").put("content", first))
            val toolNames = (0 until tools.length()).joinToString(",") { tools.optJSONObject(it)?.optString("name").orEmpty() }
            Log.i(TAG, "respondWithImageAndTools START model=$model imgB64=${imageB64.length} history=${history.size} tools=[$toolNames]")

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
                Log.i(TAG, "respondWithImageAndTools round=$round stop=$stopReason blocks=${content.length()}")

                // Server-side tool still working (e.g. web_search): echo the turn back and resume.
                if (attachTools && stopReason == "pause_turn") {
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                    round++
                    continue
                }

                if (attachTools && stopReason == "tool_use") {
                    // Collect only CLIENT tool_use blocks (server_tool_use/web_search results are
                    // already in `content` and just get echoed back untouched).
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
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                    if (results.length() > 0) {
                        messages.put(JSONObject().put("role", "user").put("content", results))
                    }
                    round++
                    continue
                }

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
            Log.e(TAG, "Claude image+tool call failed", e)
            null
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

    /** POST with a few retries on transient failures (network drop, timeout, 408/429/5xx/529). */
    private fun postForJson(payload: JSONObject): JSONObject? {
        val maxAttempts = 3
        var attempt = 0
        while (true) {
            attempt++
            try {
                Log.i(TAG, "POST attempt=$attempt/$maxAttempts model=${payload.optString("model")} tools=${payload.optJSONArray("tools")?.length() ?: 0}")
                callClient.newCall(buildRequest(payload)).execute().use { resp ->
                    val bodyStr = resp.body?.string()
                    if (resp.isSuccessful && bodyStr != null) {
                        DebugStatus.lastError = ""
                        Log.i(TAG, "POST ok HTTP ${resp.code} bodyLen=${bodyStr.length}")
                        return JSONObject(bodyStr)
                    }
                    val retryable = resp.code == 408 || resp.code == 429 || resp.code == 529 || resp.code in 500..599
                    Log.e(TAG, "Claude HTTP ${resp.code} (attempt $attempt/$maxAttempts): ${bodyStr?.take(300)}")
                    if (!retryable || attempt >= maxAttempts) {
                        DebugStatus.lastError = "HTTP ${resp.code}: ${bodyStr?.take(300)}"
                        return null
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Claude HTTP IO (attempt $attempt/$maxAttempts): ${e.message}")
                if (attempt >= maxAttempts) { DebugStatus.lastError = "IO: ${e.message}"; return null }
            }
            try { Thread.sleep(350L * attempt) } catch (_: InterruptedException) {}
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

    companion object {
        private const val TAG = "ClaudeClient"

        /**
         * Store-demo web search allow-list: Hibbett's own site, the main competitors, the big
         * footwear brands, and a few sneaker databases. This mirrors the Console whitelist as an
         * in-code backstop, so demo web search stays scoped even if the Console setting is changed.
         * Keep the two lists in sync; the effective scope is their intersection.
         */
        val WEB_SEARCH_ALLOWED_DOMAINS = listOf(
            "hibbett.com",
            // competitors
            "footlocker.com", "dickssportinggoods.com", "academy.com", "champssports.com", "finishline.com",
            // footwear brands
            "nike.com", "adidas.com", "newbalance.com", "asics.com", "underarmour.com", "puma.com",
            "converse.com", "vans.com", "hoka.com", "brooksrunning.com", "skechers.com",
            // sneaker references
            "stockx.com", "goat.com", "sneakernews.com"
        )

        /**
         * Anthropic's server-side web search tool, scoped to [WEB_SEARCH_ALLOWED_DOMAINS].
         *
         * DISABLED (until further notice): this helper is intentionally not called anywhere right
         * now -- web search kept throwing 400s and slowed the demo. To re-enable later, add
         * `.apply { put(ClaudeClient.webSearchTool()) }` back to the demo tool list in
         * ConversationManager. Kept here so re-enabling is a one-liner. Billed per search when used.
         */
        fun webSearchTool(maxUses: Int = 3): JSONObject =
            JSONObject()
                .put("type", "web_search_20250305")
                .put("name", "web_search")
                .put("max_uses", maxUses)
                .put("allowed_domains", JSONArray(WEB_SEARCH_ALLOWED_DOMAINS))
    }
}
