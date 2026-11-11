package com.medtroniclabs.microcoaching.ui.coaching

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.domain.streak.LearningStreak
import com.medtroniclabs.microcoaching.domain.streak.StreakDay
import com.medtroniclabs.microcoaching.domain.streak.StreakDayStatus
import com.medtroniclabs.microcoaching.ui.theme.CoachingTheme
import com.medtroniclabs.microcoaching.ui.theme.blendOver
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * SK Coaching-tab streak card: "N-day learning streak", the last 7 days, and a
 * one-line nudge. Renders nothing until the first read lands.
 */
@Composable
fun LearningStreakCard(chwId: String, modifier: Modifier = Modifier) {
    val vm: LearningStreakViewModel = viewModel(
        key = "learning-streak-$chwId",
        factory = LearningStreakViewModel.factory(chwId),
    )
    val streak by vm.state.collectAsStateWithLifecycle()
    streak?.let { LearningStreakCardContent(it, modifier) }
}

/**
 * Soft peach/orange tones from the product mockup, all derived from the theme's
 * `warning` token (the documented streak colour) so a host brand override still
 * applies and no colour literal is needed outside ui/theme.
 */
private data class StreakPalette(
    val flame: Brush,
    val todayRing: Color,
    val cardBackground: Color,
    val cardBorder: Color,
    val nudgeBackground: Color,
    val nudgeText: Color,
    val missedOutline: Color,
)

@Composable
private fun rememberStreakPalette(): StreakPalette {
    val colors = CoachingTheme.colors
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    return remember(colors.warning, colors.textStrong, surface, outline) {
        val orange = colors.warning
        StreakPalette(
            flame = Brush.linearGradient(
                listOf(blendOver(surface, orange, 0.45f), blendOver(surface, orange, 0.80f)),
            ),
            todayRing = blendOver(surface, orange, 0.40f),
            cardBackground = blendOver(surface, orange, 0.04f),
            cardBorder = blendOver(surface, orange, 0.18f),
            nudgeBackground = blendOver(surface, orange, 0.09f),
            // Brown-orange; ~5.9:1 on nudgeBackground with SPICE defaults (AA).
            nudgeText = blendOver(colors.textStrong, orange, 0.55f),
            missedOutline = blendOver(surface, outline, 0.75f),
        )
    }
}

@Composable
internal fun LearningStreakCardContent(streak: LearningStreak, modifier: Modifier = Modifier) {
    val palette = rememberStreakPalette()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.cardBackground)
            .border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Header(streak, palette)
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            streak.days.forEach { DayCell(it, palette, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = nudgeText(streak),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.nudgeBackground)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = palette.nudgeText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Header(streak: LearningStreak, palette: StreakPalette) {
    val colors = CoachingTheme.colors
    // One TalkBack stop for title + subtitle; the decorative 🔥 tile is hidden so it
    // isn't announced as "fire".
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.flame)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "🔥", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = pluralStringResource(
                    R.plurals.learning_streak_title,
                    streak.currentStreak,
                    streak.currentStreak,
                ),
                color = colors.textStrong,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (streak.learningDays > 0) {
                Text(
                    text = stringResource(
                        R.string.learning_streak_subtitle,
                        streak.learnedDays,
                        streak.learningDays,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DayCell(day: StreakDay, palette: StreakPalette, modifier: Modifier = Modifier) {
    val colors = CoachingTheme.colors
    val locale = Locale.getDefault()
    val weekday = if (day.isToday) {
        stringResource(R.string.learning_streak_today)
    } else {
        day.date.format(DateTimeFormatter.ofPattern("EEE", locale))
    }
    val dateLabel = day.date.format(DateTimeFormatter.ofPattern("d MMM", locale))
    val isRest = day.status == StreakDayStatus.REST
    val a11y = stringResource(
        when (day.status) {
            StreakDayStatus.DONE -> R.string.learning_streak_cd_done
            StreakDayStatus.MISSED -> R.string.learning_streak_cd_missed
            StreakDayStatus.REST -> R.string.learning_streak_cd_rest
            StreakDayStatus.PENDING -> R.string.learning_streak_cd_pending
        },
        "$weekday $dateLabel",
    )

    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = a11y },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DayCircle(day, palette)
        Spacer(Modifier.height(6.dp))
        Text(
            text = weekday,
            color = when {
                isRest -> colors.textDisabled
                day.isToday -> colors.warning
                else -> colors.textStrong
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = if (isRest) stringResource(R.string.learning_streak_rest_day) else dateLabel,
            color = if (isRest) colors.textDisabled else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun DayCircle(day: StreakDay, palette: StreakPalette) {
    val colors = CoachingTheme.colors
    // Today gets a soft outer ring (filled when done, empty while still to do).
    val ring = if (day.isToday && day.status != StreakDayStatus.REST) {
        Modifier.border(2.dp, palette.todayRing, CircleShape).padding(3.dp)
    } else {
        Modifier.padding(3.dp)
    }
    Box(modifier = ring, contentAlignment = Alignment.Center) {
        val circle = Modifier.size(34.dp).clip(CircleShape)
        when (day.status) {
            StreakDayStatus.DONE -> Box(circle.background(palette.flame), contentAlignment = Alignment.Center) {
                Text(text = "🔥", style = MaterialTheme.typography.bodyMedium)
            }
            StreakDayStatus.MISSED -> Box(
                Modifier.size(34.dp).dashedCircle(palette.missedOutline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, tint = palette.missedOutline, modifier = Modifier.size(14.dp))
            }
            StreakDayStatus.REST -> Box(circle.background(colors.lockedSurface), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = colors.textDisabled, modifier = Modifier.size(16.dp))
            }
            StreakDayStatus.PENDING -> Box(circle.background(palette.nudgeBackground))
        }
    }
}

/** Dotted circular outline, as on the mockup's missed day. */
private fun Modifier.dashedCircle(color: Color): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    drawCircle(
        color = color,
        radius = (size.minDimension - stroke) / 2f,
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        ),
    )
}

@Composable
private fun nudgeText(streak: LearningStreak): String = when {
    streak.isNewLearner -> stringResource(R.string.learning_streak_msg_new)
    streak.isTodayPending -> stringResource(R.string.learning_streak_msg_pending)
    streak.lastMissedDay != null -> stringResource(
        R.string.learning_streak_msg_missed,
        streak.lastMissedDay.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())),
    )
    else -> stringResource(R.string.learning_streak_msg_keep_going)
}
