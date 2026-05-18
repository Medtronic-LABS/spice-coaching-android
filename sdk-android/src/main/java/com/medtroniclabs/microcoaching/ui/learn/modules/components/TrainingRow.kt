package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule

/**
 * Horizontal LazyRow for training-type modules. Acts as a catch-all bucket:
 * any module whose `module_type` is neither `"refresher"` nor `"content_update"`
 * lands here. This prevents unknown future types (e.g. `"initial_training"`)
 * from silently dropping off the screen. `digital_proficiency` is the canonical
 * value but the filter deliberately does not hard-match it.
 *
 * Tapping a card routes through the existing
 * `ModuleReady → LessonContent → QuizQuestion` flow.
 */
@Composable
fun TrainingRow(
    modules: List<LearnModule>,
    onSelect: (LearnModule) -> Unit,
    modifier: Modifier = Modifier,
) {
    val training = modules.filter { it.moduleType != "refresher" && it.moduleType != "content_update" }
    if (training.isEmpty()) return

    Column(modifier = modifier) {
        SectionHeader(title = stringResource(R.string.modules_section_training))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = training, key = { it.scenarioId }) { module ->
                TrainingCard(
                    title = module.title,
                    meta = stringResource(
                        R.string.training_meta_minutes_questions,
                        module.estimatedMinutes ?: 5,
                        module.inlineQuestions?.size ?: 0,
                    ),
                    progressFraction = progressFor(module.status),
                    onClick = { onSelect(module) },
                )
            }
        }
    }
}

private fun progressFor(status: String): Float = when (status) {
    "completed" -> 1f
    "in_progress" -> 0.5f
    else -> 0f
}
