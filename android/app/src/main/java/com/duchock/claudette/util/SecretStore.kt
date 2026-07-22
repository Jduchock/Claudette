package com.duchock.claudette.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for API keys (Anthropic, ElevenLabs), backed by the Android Keystore.
 * NEVER hardcode keys in source -- this is where they live (ref threat S1). Wired into a
 * settings screen in Phase 4.
 */
object SecretStore {
    private const val FILE = "claudette_secrets"
    const val KEY_ANTHROPIC = "anthropic_api_key"
    const val KEY_ELEVENLABS = "elevenlabs_api_key"

    fun put(context: Context, key: String, value: String) =
        store(context).edit().putString(key, value).apply()

    fun get(context: Context, key: String): String? =
        store(context).getString(key, null)

    private fun store(context: Context) = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
