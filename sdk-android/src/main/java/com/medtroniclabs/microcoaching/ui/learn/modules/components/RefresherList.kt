package com.medtroniclabs.microcoaching.ui.learn.modules.components

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule

private const val TAG = "RefresherList"

/**
 * Vertical list of refresher tiles driven by the morning-card cache.
 *
 * Any module surfaced by `morning_card_cache` (i.e. [LearnModule.source] != null)
 * is a refresher candidate — regardless of `moduleType`. Gap-sourced items always
 * sort above fallback items; within each tier, backend rank order is preserved.
 *
 * @param modules Full module list — caller does not pre-filter.
 * @param onSelect Invoked when the CHW taps a tile; opens [RefresherBottomSheet].
 */
@Composable
fun RefresherList(
    modules: List<LearnModule>,
    onSelect: (LearnModule) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Any module from morning_card_cache (source != null) is a refresher.
    // The old moduleType=="refresher" filter is dropped — morning cards may
    // reference modules of any type (digital_proficiency, content_update, etc.).
    val refreshers = modules
        .filter { it.source != null }
        .sortedWith(compareBy { if (it.source == "gap") 0 else 1 })

    Log.d(TAG, "input=${modules.size} morning-card modules=${refreshers.size} " +
        "(gap=${refreshers.count { it.source == "gap" }} " +
        "fallback=${refreshers.count { it.source == "fallback" }} " +
        "null=${modules.count { it.source == null }})")

    if (refreshers.isEmpty()) return

    Column(modifier = modifier) {
        SectionHeader(title = stringResource(R.string.modules_section_refreshers))
        refreshers.forEach { module ->
            RefresherTile(
                category = stringResource(categoryLabelFor(module)),
                title = module.title,
                meta = pluralStringResource(
                    R.plurals.refresher_meta_quiz,
                    module.inlineQuestions?.size ?: 0,
                    module.inlineQuestions?.size ?: 0,
                ),
                isCritical = module.clinicalDomain.equals("emergency", ignoreCase = true),
                isGap = module.source == "gap",
                onClick = { onSelect(module) },
            )
        }
    }
}

private fun categoryLabelFor(module: LearnModule): Int = when {
    module.clinicalDomain.equals("spice_digital", ignoreCase = true) ->
        R.string.category_spice_app
    module.inlineQuestions != null && module.inlineQuestions.isNotEmpty() ->
        R.string.category_clinical_assessment
    else -> R.string.category_learning
}
