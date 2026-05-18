package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AnswerCard
import com.medtroniclabs.microcoaching.ui.common.AnswerCardState
import com.medtroniclabs.microcoaching.ui.common.AnswerFeedbackOverlay
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion

/**
 * Shared quiz-in-progress composable used by both:
 * - [RefresherQuizContent] (inside the bottom sheet)
 * - Module-end quiz flow triggered from [LessonPlayerScreen]
 *
 * Renders a sequential question flow: progress bar → question text → answer
 * cards → [AnswerFeedbackOverlay] on selection → advances to next question or
 * calls [onAllAnswered] when the last question is dismissed.
 */
@Composable
fun SharedQuizInProgressContent(
    questions: List<QuizQuestion>,
    viewModel: LearnViewModel,
    onAllAnswered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingSelectedIndex by remember { mutableStateOf<Int?>(null) }

    if (questions.isEmpty()) return

    val total = questions.size
    val safeIndex = currentIndex.coerceIn(0, total - 1)
    val question = questions[safeIndex]

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.quiz_question_counter, safeIndex + 1, total),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF6B7280),
        )
        LinearProgressIndicator(
            progress = { (safeIndex + 1f) / total },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = question.questionText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        question.answers.forEachIndexed { index, answerText ->
            val cardState = resolveAnswerCardState(
                index = index,
                selected = pendingSelectedIndex,
                correctIndex = question.correctIndex,
            )
            AnswerCard(
                text = answerText,
                state = cardState,
                onClick = {
                    if (pendingSelectedIndex != null) return@AnswerCard
                    pendingSelectedIndex = index
                    viewModel.selectAnswer(safeIndex, index)
                },
                index = index,
            )
        }

        pendingSelectedIndex?.let { selected ->
            Spacer(Modifier.height(4.dp))
            AnswerFeedbackOverlay(
                isCorrect = selected == question.correctIndex,
                pointValue = question.pointValue,
                correctAnswerText = question.answers.getOrNull(question.correctIndex) ?: "",
                explanation = question.explanation,
                onDismiss = {
                    pendingSelectedIndex = null
                    if (safeIndex + 1 >= total) {
                        onAllAnswered()
                    } else {
                        currentIndex = safeIndex + 1
                    }
                },
            )
        }
    }
}

internal fun resolveAnswerCardState(
    index: Int,
    selected: Int?,
    correctIndex: Int,
): AnswerCardState = when {
    selected == null -> AnswerCardState.Unselected
    index == correctIndex -> AnswerCardState.CorrectRevealed
    index == selected -> AnswerCardState.WrongRevealed
    else -> AnswerCardState.Unselected
}
