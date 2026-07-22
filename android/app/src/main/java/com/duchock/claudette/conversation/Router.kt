package com.duchock.claudette.conversation

/**
 * Chooses Sonnet vs Opus per the user's decision (D8: auto-escalate on complex asks).
 * Sonnet handles everyday requests; Opus is used when the request looks analytical,
 * multi-step, or long.
 *
 * TODO(config): set these to the CURRENT Anthropic model IDs before shipping -- these are
 * placeholders. They can also be surfaced in Settings if you want to change them without a rebuild.
 */
object Router {
    const val SONNET = "claude-sonnet-4-5"   // TODO: confirm exact current model id
    const val OPUS   = "claude-opus-4-1"     // TODO: confirm exact current model id

    private val COMPLEX_HINTS = listOf(
        "analyze", "analyse", "explain why", "compare", "difference between", "plan",
        "design", "debug", "write code", "refactor", "prove", "calculate", "step by step",
        "strategy", "pros and cons", "trade-off", "tradeoff", "optimi", "summarize this",
        "summarise this", "draft", "outline", "reason through", "think through", "why does",
        "how would", "walk me through"
    )

    /** Returns the model id for this request. */
    fun pick(userText: String): String {
        val t = userText.lowercase()
        val longAsk = userText.length > 200 || userText.split(" ").size > 40
        val looksComplex = COMPLEX_HINTS.any { t.contains(it) }
        return if (longAsk || looksComplex) OPUS else SONNET
    }
}
