package com.medtroniclabs.microcoaching.ui.learn

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.common.translatedText
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

/**
 * Card-by-card lesson player. Renders each [LessonCard] in sequence with a
 * dark-blue gradient header and a numbered list body content area.
 *
 * Layout matches `docs/v3/designs/module_content_1.png` and `module_content_2.png`:
 * - Dark-blue gradient header: back arrow, "Learning X of N", bold white title,
 *   decorative semi-transparent circle blob top-right.
 * - White rounded content card: body text split on `\n` → numbered items in red,
 *   separated by HorizontalDividers.
 * - Fixed bottom: "Next →" (not last card) / "Start Quiz →" (last card).
 *
 * @param cards The ordered list of lesson cards to display.
 * @param initialIndex Starting card index (0-based). Defaults to 0.
 * @param lang SDK language code — "bn" or "en".
 * @param onBack Navigate back to [ModuleDetailScreen].
 * @param onStartQuiz Navigate to the quiz (called when the CHW taps "Start Quiz" on last card).
 * @param onCardShown Callback fired on each card display for telemetry.
 */
@Composable
fun LessonPlayerScreen(
    cards: List<LessonCard>,
    initialIndex: Int = 0,
    lang: String = "bn",
    onBack: () -> Unit,
    onStartQuiz: () -> Unit,
    onCardShown: (Int) -> Unit,
) {
    if (cards.isEmpty()) {
        // No content — go straight to quiz.
        LaunchedEffect(Unit) { onStartQuiz() }
        return
    }

    var currentIndex by rememberSaveable { mutableIntStateOf(initialIndex.coerceIn(0, cards.size - 1)) }
    val card = cards[currentIndex]
    val isLast = currentIndex == cards.size - 1

    LaunchedEffect(currentIndex) { onCardShown(currentIndex) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Consistent blue header ──────────────────────────────────────────
            SdkScreenHeader(
                title = stringResource(R.string.lesson_player_progress, currentIndex + 1, cards.size),
                onBack = onBack,
            )

            // ── Body card ──────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White),
            ) {
                val bodyText = translatedText(bn = card.bodyBn, en = card.bodyEn)
                val items = bodyText.lines().filter { it.isNotBlank() }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp, bottom = 96.dp),
                ) {
                    item {
                        Text(
                            text = translatedText(bn = card.titleBn, en = card.titleEn),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF101828),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    itemsIndexed(items) { index, line ->
                        BodyItem(number = index + 1, text = line)
                        if (index < items.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = DividerColor,
                                thickness = 0.5.dp,
                            )
                        }
                    }
                }
            }
        }

        // ── Fixed bottom button ────────────────────────────────────────────────
        Button(
            onClick = {
                if (isLast) onStartQuiz()
                else currentIndex++
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
        ) {
            Text(
                text = if (isLast)
                    stringResource(R.string.lesson_player_start_quiz)
                else
                    stringResource(R.string.lesson_player_next),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            if (!isLast) {
                Spacer(Modifier.size(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun BodyItem(number: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$number",
            color = NumberColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = BodyTextColor,
        )
    }
}

private val DividerColor = Color(0xFFE4E7EC)
private val NumberColor = Color(0xFFDC2626)  // red — matches design numbered list indices
private val BodyTextColor = Color(0xFF344054)

/** Convenience overload that reads the SDK's current language automatically. */
@Composable
fun LessonPlayerScreen(
    module: LearnModule,
    initialIndex: Int = 0,
    onBack: () -> Unit,
    onStartQuiz: () -> Unit,
    onCardShown: (Int) -> Unit,
) {
    val lang = if (MicroCoachingSDK.getInstance().config.language == Language.ENGLISH) "en" else "bn"
    LessonPlayerScreen(
        cards = parseLessonCards(module.cardsJson),
        initialIndex = initialIndex,
        lang = lang,
        onBack = onBack,
        onStartQuiz = onStartQuiz,
        onCardShown = onCardShown,
    )
}
