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

                val req = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("content-type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string()
                    if (!resp.isSuccessful || bodyStr == null) {
                        Log.e(TAG, "Claude HTTP ${resp.code}: ${bodyStr?.take(300)}")
                        return@withContext null
                    }
                    val content = JSONObject(bodyStr).optJSONArray("content") ?: return@withContext null
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") == "text") sb.append(block.optString("text"))
                    }
                    sb.toString().trim().ifEmpty { null }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Claude call failed", e)
                null
            }
        }

    companion object { private const val TAG = "ClaudeClient" }
}
