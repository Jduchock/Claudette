package com.duchock.claudette.util

import android.content.Context
import com.duchock.claudette.BuildConfig

/**
 * Single source for credentials. Priority:
 *   1. A value entered in-app (EncryptedSharedPreferences / Prefs), if present.
 *   2. Otherwise the value baked in from local.properties at build time (BuildConfig).
 *
 * This lets you set keys once in Android Studio's local.properties (git-ignored) instead
 * of typing them on the phone, while still allowing an in-app override later.
 *
 * NOTE (threat S1): keys baked into BuildConfig live inside the APK and can be extracted
 * by decompiling it. Fine for a personal device; for a hardened build, front the APIs with
 * a small proxy and ship no keys at all.
 */
object Secrets {
    fun anthropicKey(c: Context): String =
        pick(SecretStore.get(c, SecretStore.KEY_ANTHROPIC), BuildConfig.ANTHROPIC_API_KEY)

    fun elevenLabsKey(c: Context): String =
        pick(SecretStore.get(c, SecretStore.KEY_ELEVENLABS), BuildConfig.ELEVENLABS_API_KEY)

    fun voiceId(c: Context): String =
        pick(Prefs.voiceId(c), BuildConfig.ELEVENLABS_VOICE_ID)

    fun googleMapsKey(c: Context): String =
        pick(SecretStore.get(c, SecretStore.KEY_GOOGLE_MAPS), BuildConfig.GOOGLE_MAPS_API_KEY)

    private fun pick(inApp: String?, baked: String): String =
        inApp?.takeIf { it.isNotBlank() } ?: baked
}
