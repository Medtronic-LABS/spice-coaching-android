package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R

/**
 * Chat input row with text field, optional mic button, and send button.
 *
 * The mic button is rendered when [onMicTap] is non-null. Hosts that don't
 * want a mic can pass `null`; SDK callers (e.g. [com.medtroniclabs.microcoaching.ui.chat.CoachingChatFragment])
 * always pass a real handler that forwards to the configured
 * [com.medtroniclabs.microcoaching.ai.voice.VoiceInputController].
 *
 * The text field's input is exposed via the [externalText] companion of state
 * so transcription results can be set programmatically — pass the same
 * `inputState` instance to [ChatInputBar] from a parent and call
 * `inputState.setText(...)` from the mic transcription callback.
 */
@Composable
fun ChatInputBar(
    onSend: (String) -> Unit,
    enabled: Boolean = true,
    placeholder: String = stringResource(R.string.chat_input_placeholder),
    onMicTap: (() -> Unit)? = null,
    inputState: ChatInputState = rememberChatInputState(),
    modifier: Modifier = Modifier,
) {
    fun submit() {
        if (inputState.text.isNotBlank()) {
            onSend(inputState.text.trim())
            inputState.text = ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = inputState.text,
            onValueChange = { inputState.text = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            },
            enabled = enabled,
            singleLine = false,
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        if (onMicTap != null) {
            IconButton(
                onClick = onMicTap,
                enabled = enabled,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.chat_voice_input_hint),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        IconButton(
            onClick = { submit() },
            enabled = enabled && inputState.text.isNotBlank(),
            modifier = Modifier
                .padding(start = 8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (enabled && inputState.text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    }
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.chat_send_message),
                tint = if (enabled && inputState.text.isNotBlank()) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Holder for the chat input field. Hoist this in a parent if you need to
 * push a transcription result into the input from outside (e.g. STT callback).
 */
class ChatInputState(initial: String = "") {
    var text by mutableStateOf(initial)
    fun append(suffix: String) {
        text = if (text.isBlank()) suffix else "$text $suffix"
    }
}

@Composable
fun rememberChatInputState(initial: String = ""): ChatInputState =
    rememberSaveable(saver = androidx.compose.runtime.saveable.Saver(
        save = { it.text },
        restore = { ChatInputState(it) },
    )) { ChatInputState(initial) }
