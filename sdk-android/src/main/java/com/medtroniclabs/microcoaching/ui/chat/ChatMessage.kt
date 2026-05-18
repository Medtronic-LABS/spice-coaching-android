package com.medtroniclabs.microcoaching.ui.chat

/**
 * Public model representing a single chat message.
 * Used in [CoachingDataRepository] and [ChatSession] — safe to hold in SPICE ViewModels.
 */
data class ChatMessage(
    val id: Long = 0,
    val sessionId: String,
    /** "user" or "assistant" */
    val role: String,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    /** OTel trace ID linking this message to its inference span. Null for user messages. */
    val traceId: String? = null,
    /** Where the assistant response came from. See [MessageSource]. Empty for user messages or history. */
    val source: String = "",
)

object ChatRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

object MessageSource {
    const val LOCAL_MODEL = "local_model"
    const val RAG_API = "rag_api"
}
