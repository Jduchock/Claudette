package com.duchock.claudette.conversation

/** Detects when the user wants to end the conversation (D10: stay open until dismissed). */
object Dismiss {
    private val PHRASES = listOf(
        "that's all", "thats all", "that is all", "goodbye", "bye claudette", "good night",
        "goodnight", "never mind", "nevermind", "go to sleep", "stop listening",
        "dismiss", "we're done", "were done", "that will be all", "thank you that's all"
    )

    fun isDismiss(text: String): Boolean {
        val t = text.lowercase().trim().trimEnd('.', '!', '?')
        return PHRASES.any { t == it || t.endsWith(it) }
    }
}
