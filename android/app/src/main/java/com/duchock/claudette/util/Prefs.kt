package com.duchock.claudette.util

import android.content.Context

/** Small, non-secret preferences (listening flag, chosen ElevenLabs voice id). */
object Prefs {
    private const val FILE = "claudette_prefs"
    private const val KEY_ENABLED = "listening_enabled"
    private const val KEY_VOICE = "elevenlabs_voice_id"

    fun isListeningEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setListeningEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** ElevenLabs voice id (not a secret). Default is a placeholder to set in Settings. */
    fun voiceId(context: Context): String =
        prefs(context).getString(KEY_VOICE, "") ?: ""

    fun setVoiceId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_VOICE, id).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
