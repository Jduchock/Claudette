package com.duchock.claudette.bible

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * Passage-aware study notes (D23): short summaries of the discussions John and Nova have about a
 * passage, keyed by book:chapter and encrypted at rest (personal reflection). Surfaced when that
 * passage comes up again so Nova can say "last time you wondered about...".
 */
object BibleNotes {
    private const val FILE = "nova_bible_notes"
    private var appContext: Context? = null

    fun init(context: Context) { if (appContext == null) appContext = context.applicationContext }

    private fun store(): SharedPreferences? {
        val c = appContext ?: return null
        return EncryptedSharedPreferences.create(
            c, FILE,
            MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun key(book: Int, ch: Int) = "n_${book}_$ch"

    /** All notes for a chapter, oldest first. */
    fun get(book: Int, ch: Int): List<String> {
        val raw = store()?.getString(key(book, ch), null) ?: return emptyList()
        return try {
            val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun add(book: Int, ch: Int, note: String) {
        val s = store() ?: return
        val cur = ArrayList(get(book, ch))
        cur.add(note.trim())
        while (cur.size > 20) cur.removeAt(0)
        val a = JSONArray(); cur.forEach { a.put(it) }
        try {
            s.edit().putString(key(book, ch), a.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "note save failed", e)
        }
    }

    private const val TAG = "BibleNotes"
}
