package com.duchock.claudette.util

import android.content.Context

/** Small, non-secret preferences (e.g. whether the user turned listening on). */
object Prefs {
    private const val FILE = "claudette_prefs"
    private const val KEY_ENABLED = "listening_enabled"

    fun isListeningEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setListeningEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
