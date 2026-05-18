package com.medtroniclabs.microcoaching.ui.chat

/**
 * A real suggested question populated from the CHW's scenario cache / morning cards.
 * [scenarioId] anchors the suggestion to its source scenario so the LLM prompt can pull
 * grounded clinical guidance from `scenario_cache` when the user taps it.
 */
data class SuggestedQuestion(
    val question: String,
    val banglaQuestion: String = "",
    val scenarioId: String? = null,
)

/** UI state for [ChatViewModel]. Observed by [ChatScreen]. */
sealed class ChatUiState {

    /** Initial state — checking model availability. */
    object Loading : ChatUiState()

    /**
     * No model file found on device.
     * [ChatScreen] should show a download prompt.
     */
    data class ModelNotReady(
        val downloadProgress: Int = -1,   // -1 = not downloading, 0–100 = in progress
        val isDownloading: Boolean = false,
    ) : ChatUiState()

    /**
     * Model is loaded and chat is ready.
     * @param messages Current conversation history.
     * @param isGenerating True while the LLM is streaming a response.
     * @param streamingText Partial text being accumulated during streaming.
     * @param error Non-null if the last inference failed.
     * @param suggestedQuestions Quick-start chips from morning card cache.
     */
    data class Ready(
        val messages: List<ChatMessage> = emptyList(),
        val isGenerating: Boolean = false,
        val streamingText: String = "",
        val error: String? = null,
        val modelPresent: Boolean = false,
        val isModelDownloading: Boolean = false,
        val modelDownloadProgress: Int = -1,
        val suggestedQuestions: List<SuggestedQuestion> = emptyList(),
    ) : ChatUiState()

    /** Unrecoverable error (e.g. model load failed, DB error). */
    data class Error(val message: String) : ChatUiState()
}
