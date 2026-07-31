package com.duchock.claudette.bible

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools Nova calls DURING conversation to discuss Scripture (D23) — look up passage text so she
 * quotes the KJV accurately, recall and save passage-keyed study notes, and report where reading
 * left off. Playback (reading aloud, STOP) is controlled in the service, not here.
 */
object BibleTools {

    @Volatile var readingNow = false          // set by the service while it is reading aloud
    private var appContext: Context? = null
    fun init(c: Context) { if (appContext == null) appContext = c.applicationContext }

    fun schema(): JSONArray {
        val tools = JSONArray()
        tools.put(
            JSONObject().put("name", "bible_lookup")
                .put("description", "Look up the exact King James Version text of a passage so you can quote or discuss it accurately. Give a reference like \"John 3:16\" or \"Romans 8:28\"; use 'verses' to fetch a short range (default 1).")
                .put("input_schema", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("reference", JSONObject().put("type", "string").put("description", "Passage reference, e.g. 'John 3:16', 'Psalm 23:1', 'Romans 8:28'"))
                        .put("verses", JSONObject().put("type", "integer").put("description", "How many consecutive verses to return (default 1, max 20).")))
                    .put("required", JSONArray().put("reference")))
        )
        tools.put(
            JSONObject().put("name", "bible_recall_notes")
                .put("description", "Recall the study notes John and you have saved about a passage (keyed by book and chapter), so you can pick up prior discussion.")
                .put("input_schema", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("reference", JSONObject().put("type", "string").put("description", "Any reference in the chapter, e.g. 'John 3' or 'John 3:16'")))
                    .put("required", JSONArray().put("reference")))
        )
        tools.put(
            JSONObject().put("name", "bible_save_note")
                .put("description", "Save a short note capturing something worth remembering from this discussion of a passage (an insight, a question John raised, a conclusion). Keep it one or two sentences.")
                .put("input_schema", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("reference", JSONObject().put("type", "string").put("description", "The passage the note is about, e.g. 'John 3:16'"))
                        .put("note", JSONObject().put("type", "string").put("description", "The note to save.")))
                    .put("required", JSONArray().put("reference").put("note")))
        )
        tools.put(
            JSONObject().put("name", "bible_where")
                .put("description", "Report where Scripture reading last left off (the bookmark) and whether you are reading aloud right now.")
                .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()))
        )
        return tools
    }

    fun execute(name: String, input: JSONObject): String = when (name) {
        "bible_lookup" -> lookup(input)
        "bible_recall_notes" -> recall(input)
        "bible_save_note" -> saveNote(input)
        "bible_where" -> where()
        else -> JSONObject().put("error", "unknown tool $name").toString()
    }

    private fun lookup(input: JSONObject): String {
        if (!BibleRepo.isLoaded()) return err("scripture not loaded")
        val ref = BibleRepo.parse(input.optString("reference")) ?: return err("could not parse that reference")
        val count = input.optInt("verses", 1).coerceIn(1, 20)
        val arr = JSONArray()
        var cur: BibleRepo.Ref? = ref
        var i = 0
        while (cur != null && i < count) {
            val t = BibleRepo.verse(cur) ?: break
            arr.put(JSONObject().put("ref", BibleRepo.label(cur)).put("text", t))
            cur = BibleRepo.next(cur); i++
        }
        return JSONObject().put("passage", arr).toString()
    }

    private fun recall(input: JSONObject): String {
        val ref = BibleRepo.parse(input.optString("reference")) ?: return err("could not parse that reference")
        val notes = BibleNotes.get(ref.book, ref.chapter)
        val arr = JSONArray(); notes.forEach { arr.put(it) }
        return JSONObject()
            .put("chapter", "${BibleRepo.bookName(ref.book)} ${ref.chapter}")
            .put("notes", arr).toString()
    }

    private fun saveNote(input: JSONObject): String {
        val ref = BibleRepo.parse(input.optString("reference")) ?: return err("could not parse that reference")
        val note = input.optString("note").trim()
        if (note.isBlank()) return err("empty note")
        BibleNotes.add(ref.book, ref.chapter, note)
        return JSONObject().put("saved", true).put("chapter", "${BibleRepo.bookName(ref.book)} ${ref.chapter}").toString()
    }

    private fun where(): String {
        val c = appContext ?: return err("not initialized")
        val ref = BibleBookmark.get(c)
        return JSONObject()
            .put("bookmark", BibleRepo.label(ref))
            .put("readingNow", readingNow)
            .put("hasProgress", BibleBookmark.hasProgress(c)).toString()
    }

    private fun err(m: String) = JSONObject().put("error", m).toString()
}
