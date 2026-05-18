package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AnswerCard
import com.medtroniclabs.microcoaching.ui.common.AnswerCardState
import com.medtroniclabs.microcoaching.ui.common.AnswerFeedbackOverlay
import com.medtroniclabs.microcoaching.ui.learn.LessonCard
import com.medtroniclabs.microcoaching.ui.learn.modules.AnswerOutcome
import com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.parseLessonCards
import com.medtroniclabs.microcoaching.ui.common.translatedText
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

/**
 * Full refresher experience inside [RefresherBottomSheet].
 *
 * Two-phase state machine driven by [entryMode]:
 *
 * **QUESTION_FIRST** (from [QuizRefresherCard]):
 *   Phase 1 = 1 quiz question → Phase 2 = lesson cards → Done
 *
 * **CARDS_FIRST** (from [MorningCard]):
 *   Phase 1 = lesson cards → Phase 2 = 1 quiz question → Done
 *
 * When [fromHomeScreen] is true, the Done action closes the sheet without
 * offering a "Next Refresher" — the home screen flow is complete.
 */
@Composable
fun RefresherContent(
    viewModel: QuickLearnViewModel,
    fromHomeScreen: Boolean = false,
    entryMode: RefresherBottomSheet.EntryMode = RefresherBottomSheet.EntryMode.QUESTION_FIRST,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val question by viewModel.quickQuestion.collectAsState()
    val answerState by viewModel.answerState.collectAsState()
    val payload = question ?: return

    val cards = remember(payload.module.cardsJson) { parseLessonCards(payload.module.cardsJson) }
    val hasCards = cards.isNotEmpty()

    // Phase tracking — file-level enum RefresherPhase
    var phase by remember { mutableStateOf(RefresherPhase.PHASE_1) }
    var cardIndex by rememberSaveable { mutableIntStateOf(0) }

    // In QUESTION_FIRST mode: phase1=quiz, phase2=cards.
    // In CARDS_FIRST mode:    phase1=cards, phase2=quiz.
    val phase1IsQuiz = entryMode == RefresherBottomSheet.EntryMode.QUESTION_FIRST

    when (phase) {
        RefresherPhase.DONE -> {
            viewModel.reset()
            onDismiss()
        }

        RefresherPhase.PHASE_1 -> {
            if (phase1IsQuiz) {
                RefresherQuizPhase(
                    payload = payload,
                    answerState = answerState,
                    viewModel = viewModel,
                    onAllAnswered = {
                        if (hasCards) {
                            phase = RefresherPhase.PHASE_2
                            cardIndex = 0
                        } else {
                            phase = RefresherPhase.DONE
                        }
                    },
                    modifier = modifier,
                )
            } else {
                // CARDS_FIRST: show cards in phase 1
                if (!hasCards) {
                    phase = RefresherPhase.PHASE_2 // skip to quiz
                    return
                }
                RefresherCardSlide(
                    cards = cards,
                    cardIndex = cardIndex,
                    onNext = {
                        if (cardIndex < cards.size - 1) {
                            cardIndex++
                        } else {
                            phase = RefresherPhase.PHASE_2
                            cardIndex = 0
                        }
                    },
                    modifier = modifier,
                )
            }
        }

        RefresherPhase.PHASE_2 -> {
            if (phase1IsQuiz) {
                // QUESTION_FIRST: phase 2 = cards
                if (!hasCards) {
                    phase = RefresherPhase.DONE
                    return
                }
                RefresherCardSlide(
                    cards = cards,
                    cardIndex = cardIndex,
                    onNext = {
                        if (cardIndex < cards.size - 1) cardIndex++
                        else phase = RefresherPhase.DONE
                    },
                    modifier = modifier,
                )
            } else {
                // CARDS_FIRST: phase 2 = quiz
                RefresherQuizPhase(
                    payload = payload,
                    answerState = answerState,
                    viewModel = viewModel,
                    onAllAnswered = { phase = RefresherPhase.DONE },
                    modifier = modifier,
                )
            }
        }
    }
}

// ── Quiz phase ────────────────────────────────────────────────────────────────

@Composable
private fun RefresherQuizPhase(
    payload: com.medtroniclabs.microcoaching.ui.learn.modules.QuickQuestion,
    answerState: AnswerOutcome?,
    viewModel: QuickLearnViewModel,
    onAllAnswered: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = payload.question.questionText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        payload.question.answers.forEachIndexed { index, answerText ->
            AnswerCard(
                text = answerText,
                state = resolveRefresherCardState(index, answerState, payload.question.correctIndex),
                onClick = { if (answerState == null) viewModel.submitAnswer(index) },
                index = index,
            )
        }
        Spacer(Modifier.height(8.dp))

        answerState?.let { outcome ->
            AnswerFeedbackOverlay(
                isCorrect = outcome.isCorrect,
                pointValue = payload.question.pointValue,
                correctAnswerText = payload.question.answers.getOrNull(payload.question.correctIndex) ?: "",
                explanation = payload.question.explanation,
                onDismiss = {
                    viewModel.reset()
                    onAllAnswered()
                },
            )
        }
    }
}

// ── Cards phase ───────────────────────────────────────────────────────────────

/**
 * Single lesson-card slide matching the refresher design in
 * `docs/v3/designs/` — card title, progress bar, bullet-point body, Next button.
 */
@Composable
private fun RefresherCardSlide(
    cards: List<LessonCard>,
    cardIndex: Int,
    onNext: () -> Unit,
    modifier: Modifier,
) {
    if (cards.isEmpty()) return
    val safeIndex = cardIndex.coerceIn(0, cards.size - 1)
    val card = cards[safeIndex]
    val isLast = safeIndex == cards.size - 1

    val bodyText = translatedText(bn = card.bodyBn, en = card.bodyEn)
    val bodyLines = bodyText.lines().filter { it.isNotBlank() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Title
        Text(
            text = translatedText(bn = card.titleBn, en = card.titleEn),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF101828),
        )

        // Progress
        LinearProgressIndicator(
            progress = { (safeIndex + 1f) / cards.size },
            modifier = Modifier.fillMaxWidth(),
            color = SpiceBlue,
            trackColor = Color(0xFFE4E7EC),
        )
        Text(
            text = "${safeIndex + 1} / ${cards.size}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF667085),
            modifier = Modifier.align(Alignment.End),
        )

        // Body content card
        if (bodyLines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF9FAFB), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                bodyLines.forEachIndexed { idx, line ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = SpiceBlue,
                        )
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF344054),
                        )
                    }
                    if (idx < bodyLines.size - 1) {
                        HorizontalDivider(color = Color(0xFFE4E7EC), thickness = 0.5.dp)
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Next / Done button
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
        ) {
            Text(
                text = if (isLast) stringResource(R.string.refresher_card_done)
                       else stringResource(R.string.lesson_player_next),
                fontWeight = FontWeight.SemiBold,
            )
            if (!isLast) {
                Spacer(Modifier.padding(start = 4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                )
            }
        }
    }
}

// ── Shared types ─────────────────────────────────────────────────────────────

private enum class RefresherPhase { PHASE_1, PHASE_2, DONE }

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun resolveRefresherCardState(
    index: Int,
    outcome: AnswerOutcome?,
    correctIndex: Int,
): AnswerCardState = when {
    outcome == null -> AnswerCardState.Unselected
    index == correctIndex -> AnswerCardState.CorrectRevealed
    index == outcome.selectedIndex -> AnswerCardState.WrongRevealed
    else -> AnswerCardState.Unselected
}
