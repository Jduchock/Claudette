package com.duchock.claudette.conversation

/** One message in the conversation. role is "user" or "assistant". */
data class Turn(val role: String, val content: String)
