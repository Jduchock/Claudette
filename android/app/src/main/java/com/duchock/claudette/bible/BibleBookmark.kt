package com.duchock.claudette.bible

import android.content.Context

/** Remembers where John left off reading Scripture (D23). Not sensitive; plain prefs. */
object BibleBookmark {
    private const val FILE = "nova_bible"
    private const val K_BOOK = "book"
    private const val K_CH = "chapter"
    private const val K_VS = "verse"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(c: Context): BibleRepo.Ref {
        val p = prefs(c)
        val b = p.getInt(K_BOOK, 0)
        return if (b in 1..66) BibleRepo.Ref(b, p.getInt(K_CH, 1), p.getInt(K_VS, 1))
        else BibleRepo.first()
    }

    fun set(c: Context, ref: BibleRepo.Ref) {
        prefs(c).edit().putInt(K_BOOK, ref.book).putInt(K_CH, ref.chapter).putInt(K_VS, ref.verse).apply()
    }

    fun hasProgress(c: Context) = prefs(c).getInt(K_BOOK, 0) in 1..66
}
