package com.duchock.claudette.memory

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Nova's persistent, ENCRYPTED long-term memory (D12/D16). Two small text fields:
 *   profile  -- durable facts about John (work, projects, people, preferences, values).
 *   summary  -- a short rolling narrative of what's recently been going on.
 *
 * Stored in EncryptedSharedPreferences (Android Keystore-backed), the same scheme SecretStore
 * uses for API keys -- this is the most sensitive data in the app, so it is never written in
 * clear text (ref threat S13). MemoryUpdater maintains the contents after each conversation.
 *
 * This is small by design (text only): a few kilobytes, injected into Nova's system prompt so
 * she remembers John across sessions without re-reading whole transcripts.
 */
object MemoryStore {
    private const val FILE = "nova_memory"
    private const val K_PROFILE = "profile"
    private const val K_SUMMARY = "summary"

    private var appContext: Context? = null
    @Volatile private var profile = ""
    @Volatile private var summary = ""

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        try {
            store()?.let { p ->
                profile = p.getString(K_PROFILE, "") ?: ""
                summary = p.getString(K_SUMMARY, "") ?: ""
            }
            Log.i(TAG, "Memory loaded (profile=${profile.length} summary=${summary.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "Memory load failed", e)
        }
    }

    fun isEmpty() = profile.isBlank() && summary.isBlank()
    fun profile() = profile
    fun summary() = summary

    /** Text block injected into Nova's system prompt (empty when she has no memory yet). */
    fun memoryBlock(): String {
        if (isEmpty()) return ""
        val sb = StringBuilder("\n\nWHAT YOU REMEMBER ABOUT JOHN (from past conversations)\n")
        if (profile.isNotBlank()) sb.append("Profile:\n").append(profile.trim()).append("\n")
        if (summary.isNotBlank()) sb.append("Recently:\n").append(summary.trim()).append("\n")
        sb.append(
            "Use this naturally -- do not recite it back verbatim. If something here conflicts " +
                "with what John says now, believe John."
        )
        return sb.toString()
    }

    @Synchronized
    fun update(newProfile: String, newSummary: String) {
        profile = newProfile.trim().take(4000)
        summary = newSummary.trim().take(3000)
        try {
            store()?.edit()?.putString(K_PROFILE, profile)?.putString(K_SUMMARY, summary)?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Memory save failed", e)
        }
    }

    private fun store(): SharedPreferences? {
        val c = appContext ?: return null
        return EncryptedSharedPreferences.create(
            c, FILE,
            MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private const val TAG = "MemoryStore"
}
