package com.duchock.claudette.bible

import android.content.Context

/**
 * Detects a spoken "start reading Scripture" command in a conversation utterance and resolves
 * the starting point — a specific reference if John named one, otherwise the saved bookmark
 * (D23). Returns null when the utterance is not a read command.
 */
object BibleControl {

    fun readingStart(text: String, c: Context): BibleRepo.Ref? {
        val t = text.lowercase()
        val mentionsRead = Regex("\\b(read|reading)\\b").containsMatchIn(t)
        val continueish = t.contains("continue reading") || t.contains("keep reading") ||
            t.contains("keep going") || t.contains("pick up where") || t.contains("where we left") ||
            t.contains("resume")
        val bibleish = t.contains("bible") || t.contains("scripture") || t.contains("the word") ||
            t.contains("gospel") || t.contains("psalm") || t.contains("verse") || t.contains("chapter")

        if (!(continueish || (mentionsRead && bibleish))) return null

        // Try to pull a specific reference out of the phrase (after the word "read").
        var after = t.substringAfter("read", "").trim()
        for (junk in listOf("the bible", "bible", "scripture", "from the", "from", "me", "to me",
            "the word", "the gospel of", "gospel of", "book of", "chapter", "please", "a little", "some")) {
            after = after.replace(junk, " ")
        }
        after = after.replace(Regex("\\s+"), " ").trim()
        val parsed = if (after.isNotBlank()) BibleRepo.parse(after) else null
        return parsed ?: BibleBookmark.get(c)
    }
}
