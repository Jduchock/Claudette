package com.duchock.claudette.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * ElevenLabs text-to-speech. Returns MP3 bytes for [TtsPlayer] to play.
 * "eleven_turbo_v2_5" is chosen for low latency; voice + delivery are tunable.
 */
class ElevenLabsClient(
    private val apiKey: String,
    private val http: OkHttpClient
) {
    suspend fun synthesize(
        text: String,
        voiceId: String,
        stability: Double = 0.5,
        similarityBoost: Double = 0.75,
        style: Double = 0.4
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val settings = JSONObject()
                .put("stability", stability)
                .put("similarity_boost", similarityBoost)
                .put("style", style)
                .put("use_speaker_boost", true)
            val payload = JSONObject()
                .put("text", text)
                .put("model_id", "eleven_turbo_v2_5")
                .put("voice_settings", settings)

            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                .addHeader("xi-api-key", apiKey)
                .addHeader("accept", "audio/mpeg")
                .addHeader("content-type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "ElevenLabs HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
                    return@withContext null
                }
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs call failed", e)
            null
        }
    }

    companion object { private const val TAG = "ElevenLabsClient" }
}
