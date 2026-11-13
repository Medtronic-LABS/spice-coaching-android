package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.theme.CoachingTheme

/** How many Learning Library modules the SK sees before tapping "Show all". */
internal const val LEARNING_LIBRARY_INITIAL_COUNT = 5

/** What the collapsed / expanded Learning Library renders. Pure for unit tests. */
internal data class LearningLibraryWindow(val visibleCount: Int, val showAllButton: Boolean)

internal fun learningLibraryWindow(
    total: Int,
    expanded: Boolean,
    initialCount: Int = LEARNING_LIBRARY_INITIAL_COUNT,
): LearningLibraryWindow {
    val safeTotal = total.coerceAtLeast(0)
    return if (expanded || safeTotal <= initialCount) {
        LearningLibraryWindow(visibleCount = safeTotal, showAllButton = false)
    } else {
        LearningLibraryWindow(visibleCount = initialCount, showAllButton = true)
    }
}

/**
 * SK Learning Library (LEAP-133): a vertical list of the assigned training modules,
 * newest assignment first. Shows the first [LEARNING_LIBRARY_INITIAL_COUNT], then a
 * "Show all (N)" button that reveals the rest in place — no navigation, no
 * "Show less". The PO keeps its grid ([TrainingGrid]).
 *
 * Rendered inside the Refresher sub-tab's scrolling Column, so rows are composed
 * eagerly; fine at assignment-list sizes (tens of modules).
 */
@Composable
fun LearningLibraryList(
    modules: List<LearnModule>,
    onSelect: (LearnModule) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    newModuleFamilyId: String? = null,
) {
    if (modules.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val window = learningLibraryWindow(modules.size, expanded)

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = title, seeAllLabel = null, onSeeAllClick = null)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            modules.take(window.visibleCount).forEach { module ->
                LearningLibraryRow(
                    module = module,
                    showNewBadge = module.moduleFamilyId == newModuleFamilyId,
                    onClick = { onSelect(module) },
                )
            }
            if (window.showAllButton) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.learning_library_show_all, modules.size))
                }
            }
        }
    }
}

/**
 * One Learning Library card, per the "Lesson list" mockup: square thumbnail on the
 * left, tags + title + "N min · N questions" + progress in the middle, and an
 * outlined arrow on the right (same on every row). The whole card is the tap target.
 */
@Composable
internal fun LearningLibraryRow(
    module: LearnModule,
    showNewBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val green = CoachingTheme.colors.success
    val progress = progressFractionFor(module)

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = THUMBNAIL_SIZE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModuleThumbnail(
                thumbnailUrl = module.thumbnailUrl,
                contentDescription = null, // title is read from the text beside it
                modifier = Modifier
                    .width(THUMBNAIL_SIZE)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
                fallback = {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    ),
                                ),
                            ),
                    )
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (showNewBadge) TrainingNewBadge()
                    ContentDomainTag(contentDomain = module.contentDomain)
                }
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.training_meta_minutes_questions,
                        module.estimatedMinutes ?: 5,
                        module.questionCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = green,
                )
                if (progress > 0f) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.weight(1f),
                            color = green,
                            trackColor = CoachingTheme.colors.successContainer,
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Decorative: the whole card is clickable, so the arrow adds no extra action.
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(36.dp)
                    .border(1.5.dp, green, CircleShape)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = green,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private val THUMBNAIL_SIZE = 104.dp
