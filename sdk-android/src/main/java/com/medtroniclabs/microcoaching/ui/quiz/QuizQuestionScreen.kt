package com.medtroniclabs.microcoaching.ui.quiz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AnswerCard
import com.medtroniclabs.microcoaching.ui.common.AnswerCardState
import com.medtroniclabs.microcoaching.ui.common.AnswerFeedbackOverlay
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.theme.SurfaceBackground

/**
 * Single quiz question screen with animated answer reveal and a consistent
 * blue [SdkScreenHeader].
 *
 * @param uiState Must be [LearnUiState.QuizInProgress].
 * @param questionIndex Which question is currently displayed (0-based).
 * @param onAnswerSelected Called when the CHW selects an answer.
 * @param onNext Navigate to next question or result screen.
 * @param onBack Navigate back (to lesson player if arriving from a course,
 *   or to module detail for the direct "Do a Quiz" path). Pressing the
 *   system back button triggers this — preventing mid-quiz back-stack issues.
 * @param moduleTitle Shown in the header when provided.
 */
@Composable
fun QuizQuestionScreen(
    uiState: LearnUiState,
    questionIndex: Int,
    onAnswerSelected: (answerIndex: Int) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit = {},
    moduleTitle: String = "",
) {
    val quizState = uiState as? LearnUiState.QuizInProgress ?: return
    val question = quizState.questions.getOrNull(questionIndex) ?: return
    val totalQuestions = quizState.questions.size

    var selectedIndex by remember(questionIndex) { mutableStateOf(-1) }
    var showFeedback by remember(questionIndex) { mutableStateOf(false) }

    // Intercept system back — go to lesson player or module detail rather
    // than stepping back through quiz questions one-by-one.
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBackground),
    ) {
        SdkScreenHeader(
            title = if (moduleTitle.isNotBlank()) moduleTitle
            else stringResource(R.string.quiz_question_counter, questionIndex + 1, totalQuestions),
            onBack = onBack,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.quiz_question_counter, questionIndex + 1, totalQuestions),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = question.questionText,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = 26.sp,
                )

                Spacer(Modifier.height(24.dp))

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    question.answers.forEachIndexed { index, answerText ->
                        val cardState = when {
                            !showFeedback && selectedIndex == index -> AnswerCardState.Selected
                            !showFeedback -> AnswerCardState.Unselected
                            index == question.correctIndex -> AnswerCardState.CorrectRevealed
                            index == selectedIndex -> AnswerCardState.WrongRevealed
                            else -> AnswerCardState.Unselected
                        }
                        AnswerCard(
                            text = answerText,
                            state = cardState,
                            onClick = {
                                if (selectedIndex == -1 && !showFeedback) {
                                    selectedIndex = index
                                    onAnswerSelected(index)
                                    showFeedback = true
                                }
                            },
                            index = index,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                if (selectedIndex == -1) {
                    Button(
                        onClick = { /* tap a card to answer */ },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().height(52.dp).navigationBarsPadding(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(stringResource(R.string.quiz_select_answer), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }

                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }

            if (showFeedback) {
                AnswerFeedbackOverlay(
                    isCorrect = selectedIndex == question.correctIndex,
                    pointValue = question.pointValue,
                    correctAnswerText = question.answers.getOrElse(question.correctIndex) { "" },
                    explanation = question.explanation,
                    onDismiss = {
                        showFeedback = false
                        onNext()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
