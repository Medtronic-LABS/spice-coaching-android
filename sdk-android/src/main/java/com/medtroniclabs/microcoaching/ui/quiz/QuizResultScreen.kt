package com.medtroniclabs.microcoaching.ui.quiz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CircularScoreArc
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.theme.SurfaceBackground

/**
 * Quiz result screen.
 *
 * Shows the CHW their score, badge, per-question review with explanations, and options to:
 * - Go back to the module list ("আরও মডিউল") — the primary CTA
 * - Exit to SPICE ("SPICE-এ ফিরুন")
 *
 * When [onTryAgain] is non-null and the CHW failed (scorePercent < pass threshold),
 * the "আরও মডিউল" button is replaced with "Try Again" which restarts the course
 * from card 1 via [onTryAgain].
 *
 * @param uiState Must be [LearnUiState.QuizResult] for content to render.
 * @param onNextModule Called when the CHW taps "আরও মডিউল" — navigate back to module list.
 * @param onBackToSpice Called when the CHW taps "SPICE-এ ফিরুন" — `activity.finish()`.
 * @param onTryAgain When non-null and quiz failed, "Try Again" replaces "More Modules" CTA.
 * @param isRefresherQuiz When true, the primary "More Modules" label changes to
 *   "Back to Refreshers" to better reflect the refresher context.
 */
@Composable
fun QuizResultScreen(
    uiState: LearnUiState,
    onNextModule: () -> Unit,
    onBackToSpice: () -> Unit,
    onTryAgain: (() -> Unit)? = null,
    isRefresherQuiz: Boolean = false,
) {
    if (uiState !is LearnUiState.QuizResult) {
        CircularProgressIndicator()
        return
    }

    val passThreshold = com.medtroniclabs.microcoaching.MicroCoachingSDK.getInstance().config.quizPassThreshold
    val failed = uiState.scorePercent < passThreshold
    val showTryAgain = onTryAgain != null && failed

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { Spacer(Modifier.height(32.dp)) }

        item {
            Text(
                text = stringResource(R.string.quiz_complete_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF0A3D27),
            )
        }

        item { Spacer(Modifier.height(32.dp)) }

        item {
            CircularScoreArc(
                scorePercent = uiState.scorePercent,
                size = 160.dp,
            )
        }

        item { Spacer(Modifier.height(16.dp)) }

        item {
            Text(
                text = stringResource(R.string.quiz_correct_count, uiState.correctCount, uiState.totalCount),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF444444),
            )
        }

        item { Spacer(Modifier.height(32.dp)) }

        // Badge
        item {
            Column(
                modifier = Modifier
                    .background(Color(0xFFD7F0E5), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "🏅", fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = uiState.badgeLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF0A3D27),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.quiz_badge_earned),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF1B6B4A),
                )
            }
        }

        // Per-question review section
        if (uiState.questions.isNotEmpty()) {
            item { Spacer(Modifier.height(24.dp)) }

            item {
                Text(
                    text = stringResource(R.string.quiz_review_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF0A3D27),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            itemsIndexed(uiState.questions) { idx, question ->
                val selected = uiState.answers[idx]
                val isCorrect = selected == question.correctIndex
                QuizReviewRow(
                    questionNumber = idx + 1,
                    questionText = question.questionText,
                    isCorrect = isCorrect,
                    explanation = question.explanation,
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onBackToSpice,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.quiz_back_to_spice))
                }

                Button(
                    onClick = if (showTryAgain) onTryAgain!! else onNextModule,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = when {
                            showTryAgain -> stringResource(R.string.quiz_try_again)
                            isRefresherQuiz -> stringResource(R.string.quiz_back_to_refreshers)
                            else -> stringResource(R.string.quiz_more_modules)
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun QuizReviewRow(
    questionNumber: Int,
    questionText: String,
    isCorrect: Boolean,
    explanation: String,
) {
    val bg = if (isCorrect) Color(0xFFD7F0E5) else Color(0xFFFFE8E8)
    val indicator = if (isCorrect) "✓" else "✗"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = "$indicator  $questionNumber. $questionText",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        if (explanation.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF444444),
            )
        }
    }
}
