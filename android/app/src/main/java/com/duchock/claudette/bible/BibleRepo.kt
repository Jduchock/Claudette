package com.duchock.claudette.bible

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Loads the bundled King James Version (assets/bible_kjv.json — public domain, 66 books, 31,102
 * verses) and provides passage access, sequential navigation, and reference parsing. Degrades
 * gracefully if the asset is missing or partial.
 */
object BibleRepo {

    data class Ref(val book: Int, val chapter: Int, val verse: Int)

    private class Book(val name: String, val num: Int, val chapters: List<List<String>>)

    @Volatile private var loaded = false
    private val books = ArrayList<Book>()
    private val byNum = HashMap<Int, Book>()
    private val nameToNum = LinkedHashMap<String, Int>()

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        try {
            val text = context.assets.open("bible_kjv.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONArray("books")
            for (i in 0 until arr.length()) {
                val bo = arr.getJSONObject(i)
                val name = bo.getString("name")
                val num = bo.getInt("num")
                val chArr = bo.getJSONArray("chapters")
                val chapters = ArrayList<List<String>>(chArr.length())
                for (c in 0 until chArr.length()) {
                    val vArr = chArr.getJSONArray(c)
                    val verses = ArrayList<String>(vArr.length())
                    for (v in 0 until vArr.length()) verses.add(vArr.getString(v))
                    chapters.add(verses)
                }
                val b = Book(name, num, chapters)
                books.add(b); byNum[num] = b
                nameToNum[norm(name)] = num
                ABBR[num]?.forEach { nameToNum[norm(it)] = num }
            }
            books.sortBy { it.num }
            loaded = true
            Log.i(TAG, "KJV loaded: ${books.size} books")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bible_kjv.json", e)
        }
    }

    fun isLoaded() = loaded

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun bookName(num: Int): String? = byNum[num]?.name
    fun chapterCount(book: Int): Int = byNum[book]?.chapters?.size ?: 0
    fun verseCount(book: Int, ch: Int): Int = byNum[book]?.chapters?.getOrNull(ch - 1)?.size ?: 0

    fun verse(ref: Ref): String? =
        byNum[ref.book]?.chapters?.getOrNull(ref.chapter - 1)?.getOrNull(ref.verse - 1)

    fun label(ref: Ref): String = "${bookName(ref.book) ?: "?"} ${ref.chapter}:${ref.verse}"

    fun first(): Ref = Ref(1, 1, 1)

    /** Next verse in reading order, or null at the very end of Revelation. */
    fun next(ref: Ref): Ref? {
        val b = byNum[ref.book] ?: return null
        val chapter = b.chapters.getOrNull(ref.chapter - 1) ?: return null
        if (ref.verse < chapter.size) return ref.copy(verse = ref.verse + 1)
        if (ref.chapter < b.chapters.size) return Ref(ref.book, ref.chapter + 1, 1)
        val nextBook = byNum[ref.book + 1] ?: return null
        return if (nextBook.chapters.isNotEmpty()) Ref(nextBook.num, 1, 1) else null
    }

    /**
     * Parse a reference like "John 3:16", "john 3", "genesis", "1 Cor 13:4", "Psalm 23".
     * Chapter and verse default to 1 when omitted. Returns null if the book is unknown.
     */
    fun parse(text: String): Ref? {
        val m = Regex("^\\s*((?:[1-3]\\s*)?[A-Za-z][A-Za-z ]*?)\\s*(\\d+)?\\s*[:. ]?\\s*(\\d+)?\\s*$")
            .find(text.trim()) ?: return null
        val num = nameToNum[norm(m.groupValues[1])] ?: return null
        val ch = m.groupValues[2].toIntOrNull() ?: 1
        val vs = m.groupValues[3].toIntOrNull() ?: 1
        val safeCh = if (chapterCount(num) in 1 until ch || ch < 1) 1 else ch
        val safeVs = if (verseCount(num, safeCh) in 1 until vs || vs < 1) 1 else vs
        return Ref(num, safeCh, safeVs)
    }

    private val ABBR = mapOf(
        1 to listOf("gen", "ge"), 2 to listOf("exo", "ex"), 3 to listOf("lev", "lv"),
        4 to listOf("num", "nu"), 5 to listOf("deut", "dt", "deu"),
        6 to listOf("josh", "jos"), 7 to listOf("judg", "jdg"), 8 to listOf("ruth", "ru"),
        19 to listOf("ps", "psalm", "psa", "psalms"), 20 to listOf("prov", "pr", "pro"),
        21 to listOf("eccl", "ecc"), 22 to listOf("song", "sos", "songofsolomon"),
        23 to listOf("isa", "is"), 24 to listOf("jer"), 26 to listOf("ezek", "eze"),
        27 to listOf("dan", "dn"), 40 to listOf("matt", "mt", "mat"),
        41 to listOf("mark", "mk", "mar"), 42 to listOf("luke", "lk", "luk"),
        43 to listOf("john", "jn", "joh"), 44 to listOf("acts", "act"),
        45 to listOf("rom", "ro"), 46 to listOf("1cor", "1co"), 47 to listOf("2cor", "2co"),
        48 to listOf("gal", "ga"), 49 to listOf("eph"), 50 to listOf("phil", "php"),
        58 to listOf("heb"), 59 to listOf("james", "jas", "jam"),
        66 to listOf("rev", "rv", "revelations", "revelation")
    )

    private const val TAG = "BibleRepo"
}
