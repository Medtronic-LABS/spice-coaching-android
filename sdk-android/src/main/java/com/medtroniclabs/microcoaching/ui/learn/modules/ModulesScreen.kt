package com.medtroniclabs.microcoaching.ui.learn.modules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.modules.components.KnowledgeRow
import com.medtroniclabs.microcoaching.ui.learn.modules.components.QuizRefresherCard
import com.medtroniclabs.microcoaching.ui.learn.modules.components.RefresherList
import com.medtroniclabs.microcoaching.ui.learn.modules.components.TrainingRow

/**
 * Orchestrator composable for the v0.3.2 modules screen. Renders, top to
 * bottom: [QuickLearnCard] · [RefresherList] · [TrainingRow] · [KnowledgeRow].
 *
 * Sheet launches are hoisted out — the caller (`CoachingNavGraph`) holds the
 * activity reference needed for `FragmentManager` and passes lambdas down.
 * This keeps `ModulesScreen` free of any Compose context-walking and
 * eliminates the "FragmentManager is null" failure mode reported in pilot.
 *
 * @param modules Full module list from [LearnViewModel].
 * @param chwId Forwarded into the [QuickLearnViewModel] factory.
 * @param onShowQuickLearn Open the Quick learn bottom sheet (no-op when no
 *   morning module is surfaced; the banner hides automatically in that case).
 * @param onShowRefresherQuiz Open the Refresher quiz bottom sheet. The caller
 *   must have already primed the LearnViewModel via `selectModuleForQuiz`.
 * @param onTrainingSelect Tap target for `digital_proficiency` cards — host
 *   routes through the existing `ModuleReady → LessonContent → Quiz` chain.
 * @param onKnowledgeSelect Tap target for `content_update` cards.
 * @param onRefresherStart Fired *before* [onShowRefresherQuiz] so the host
 *   can prime `LearnViewModel.selectModuleForQuiz(module)`.
 */
@Composable
fun ModulesScreen(
    modules: List<LearnModule>,
    chwId: String,
    onShowQuickLearn: () -> Unit,
    onShowRefresherQuiz: () -> Unit,
    onTrainingSelect: (LearnModule) -> Unit,
    onKnowledgeSelect: (LearnModule) -> Unit,
    onRefresherStart: (LearnModule) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val quickLearnViewModel: QuickLearnViewModel = viewModel(
        factory = QuickLearnViewModel.factory(context.applicationContext, chwId),
    )
    val quickQuestion by quickLearnViewModel.quickQuestion.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        quickQuestion?.let { qq ->
            QuizRefresherCard(
                questionText = qq.question.questionText,
                participantCount = 12, // Placeholder — backend count endpoint deferred.
                xpReward = qq.question.pointValue,
                onClick = onShowQuickLearn,
            )
        }

        RefresherList(
            modules = modules,
            onSelect = { module ->
                onRefresherStart(module)
                onShowRefresherQuiz()
            },
        )

        TrainingRow(
            modules = modules,
            onSelect = onTrainingSelect,
        )

        KnowledgeRow(
            modules = modules,
            onSelect = onKnowledgeSelect,
        )

        Spacer(Modifier.height(80.dp)) // Breathing room for FABs
    }
}
