package com.medtroniclabs.microcoaching.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.chat.ChatRole
import com.medtroniclabs.microcoaching.ui.chat.ChatUiState
import com.medtroniclabs.microcoaching.ui.chat.MessageSource
import com.medtroniclabs.microcoaching.ui.chat.SuggestedQuestion
import com.medtroniclabs.microcoaching.ui.common.ChatInputBar
import com.medtroniclabs.microcoaching.ui.common.DownloadProgressBar
import com.medtroniclabs.microcoaching.ui.common.FullScreenLoader
import com.medtroniclabs.microcoaching.ui.common.MessageBubble
import com.medtroniclabs.microcoaching.ui.common.StreamingBubble
import com.medtroniclabs.microcoaching.ui.components.TranslationModelStateChip
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK

@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onSendMessage: (String) -> Unit,
    onSendSuggested: (SuggestedQuestion) -> Unit,
    onRequestDownload: () -> Unit,
    onSpeakMessage: (String) -> Unit,
    onMicTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val error = (uiState as? ChatUiState.Ready)?.error
    LaunchedEffect(error) {
        if (!error.isNullOrBlank()) {
            snackbarHostState.showSnackbar(error)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (uiState) {
                is ChatUiState.Loading -> FullScreenLoader()
                is ChatUiState.ModelNotReady -> ModelNotReadyContent(
                    isDownloading = uiState.isDownloading,
                    downloadProgress = uiState.downloadProgress,
                    onRequestDownload = onRequestDownload,
                )
                is ChatUiState.Ready -> ReadyChatContent(
                    uiState = uiState,
                    onSendMessage = onSendMessage,
                    onSendSuggested = onSendSuggested,
                    onRequestDownload = onRequestDownload,
                    onSpeakMessage = onSpeakMessage,
                    onMicTap = onMicTap,
                )
                is ChatUiState.Error -> ErrorContent(message = uiState.message)
            }
        }
    }
}

@Composable
private fun ReadyChatContent(
    uiState: ChatUiState.Ready,
    onSendMessage: (String) -> Unit,
    onSendSuggested: (SuggestedQuestion) -> Unit,
    onRequestDownload: () -> Unit,
    onSpeakMessage: (String) -> Unit,
    onMicTap: (() -> Unit)?,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        val itemCount = uiState.messages.size + (if (uiState.isGenerating) 1 else 0)
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    val suggestedQuestionsLabel = stringResource(R.string.chat_suggested_questions)
    val nextQuestionLabel = stringResource(R.string.chat_next_question)

    Column(modifier = Modifier.fillMaxSize()) {
        // Translation pack status — only renders when SDK lang=Bangla and pack
        // is downloading or failed.
        TranslationModelStateChip(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        if (!uiState.modelPresent) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chat_model_not_installed),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (uiState.isModelDownloading) {
                            Text(
                                text = if (uiState.modelDownloadProgress >= 0)
                                    stringResource(R.string.chat_model_downloading_progress, uiState.modelDownloadProgress)
                                else
                                    stringResource(R.string.chat_model_downloading_waiting),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (uiState.isModelDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(onClick = onRequestDownload) {
                            Text(stringResource(R.string.chat_model_download_action))
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            if (uiState.messages.isEmpty() && !uiState.isGenerating && uiState.suggestedQuestions.isNotEmpty()) {
                item {
                    SuggestionChips(
                        questions = uiState.suggestedQuestions.take(3),
                        label = suggestedQuestionsLabel,
                        onSendSuggested = onSendSuggested,
                    )
                }
            }

            items(items = uiState.messages, key = { it.id }) { message ->
                when (message.role) {
                    ChatRole.ASSISTANT -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            MessageBubble(message = message)
                            Row(
                                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = { onSpeakMessage(message.text) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = stringResource(R.string.chat_speak),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                                    )
                                }
                            }
                        }
                    }
                    else -> MessageBubble(message = message)
                }
            }

            if (uiState.isGenerating) {
                item { StreamingBubble(text = uiState.streamingText) }
            }

            if (uiState.messages.isNotEmpty() && !uiState.isGenerating && uiState.suggestedQuestions.isNotEmpty()) {
                item {
                    SuggestionChips(
                        questions = uiState.suggestedQuestions.take(3),
                        label = nextQuestionLabel,
                        onSendSuggested = onSendSuggested,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        ChatInputBar(
            onSend = onSendMessage,
            enabled = !uiState.isGenerating,
            onMicTap = onMicTap,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
private fun SuggestionChips(
    questions: List<SuggestedQuestion>,
    label: String,
    onSendSuggested: (SuggestedQuestion) -> Unit,
) {
    val sdkLanguage = MicroCoachingSDK.getInstance().language
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        questions.forEach { q ->
            // Pick the chip text by SDK language. ChatViewModel populates
            // `question` with English (translated from `banglaQuestion`) when
            // SDK lang=English; falls back to `banglaQuestion` if translation
            // hasn't landed yet.
            val displayText = when (sdkLanguage) {
                Language.ENGLISH ->
                    q.question.ifBlank { q.banglaQuestion }
                Language.BANGLA ->
                    q.banglaQuestion.ifBlank { q.question }
            }
            SuggestionChip(
                onClick = { onSendSuggested(q) },
                label = {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// @Composable
// private fun SourceBadge(source: String) {
//     val (label, color) = when (source) {
//         MessageSource.LOCAL_MODEL -> "Local AI" to Color(0xFF388E3C)
//         MessageSource.RAG_API -> "RAG API" to MaterialTheme.colorScheme.primary
//         else -> return
//     }
//     Surface(
//         color = color.copy(alpha = 0.12f),
//         shape = RoundedCornerShape(4.dp),
//         modifier = Modifier.padding(start = 4.dp),
//     ) {
//         Text(
//             text = label,
//             style = MaterialTheme.typography.labelSmall,
//             color = color,
//             fontSize = 9.sp,
//             modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
//         )
//     }
// }

@Composable
private fun ModelNotReadyContent(
    isDownloading: Boolean,
    downloadProgress: Int,
    onRequestDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.chat_ai_coaching_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.chat_model_download_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))
        if (isDownloading) {
            Text(
                text = if (downloadProgress >= 0)
                    stringResource(R.string.chat_downloading_model_progress, downloadProgress)
                else
                    stringResource(R.string.chat_downloading_model_preparing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            DownloadProgressBar(progressPercent = downloadProgress)
        } else {
            androidx.compose.material3.Button(onClick = onRequestDownload) {
                Text(stringResource(R.string.chat_download_ai_model))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.chat_model_size_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.common_error_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
