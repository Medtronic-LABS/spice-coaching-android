package com.medtroniclabs.microcoaching.ui.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.learn.modules.ModulesScreen
import com.medtroniclabs.microcoaching.ui.learn.modules.components.ModuleCard
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme
import com.medtroniclabs.microcoaching.ui.theme.SurfaceBackground

/**
 * Entry screen for the Learn flow.
 *
 * - [LearnUiState.Loading] → spinner
 * - [LearnUiState.Error] → error message
 * - [LearnUiState.ModuleList] → scrollable list of scenario cards (gap-prioritised)
 *   or the v0.3.2 [ModulesScreen] when [chwId] is set
 *
 * [LearnUiState.ModuleReady] is no longer rendered here — the nav graph skips
 * directly to [ModuleDetailScreen] when a module is tapped (Fix 1).
 */
@Composable
fun ModuleReadyScreen(
    uiState: LearnUiState,
    onModuleSelected: (LearnModule) -> Unit = {},
    onStartLearning: () -> Unit = {},
    onClose: (() -> Unit)? = null,
    chwId: String? = null,
    onRefresherStart: (LearnModule) -> Unit = {},
    onKnowledgeSelect: (LearnModule) -> Unit = onModuleSelected,
    onShowQuickLearn: () -> Unit = {},
    onShowRefresherQuiz: () -> Unit = {},
) {
    val lastModuleList = remember { mutableStateOf<List<LearnModule>?>(null) }
    if (uiState is LearnUiState.ModuleList) {
        lastModuleList.value = uiState.modules
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBackground),
    ) {
        val showHeader = onClose != null && (
            uiState is LearnUiState.ModuleList ||
                (lastModuleList.value != null &&
                    (uiState is LearnUiState.QuizInProgress || uiState is LearnUiState.QuizResult))
            )
        if (showHeader) {
            SdkScreenHeader(
                title = stringResource(R.string.modules_screen_title),
                onBack = onClose!!,
            )
        }

        when (uiState) {
            is LearnUiState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.learn_loading_modules),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            }

            is LearnUiState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            is LearnUiState.ModuleList -> {
                if (chwId != null) {
                    ModulesScreen(
                        modules = uiState.modules,
                        chwId = chwId,
                        onShowQuickLearn = onShowQuickLearn,
                        onShowRefresherQuiz = onShowRefresherQuiz,
                        onTrainingSelect = onModuleSelected,
                        onKnowledgeSelect = onKnowledgeSelect,
                        onRefresherStart = onRefresherStart,
                    )
                } else {
                    ModuleListContent(
                        modules = uiState.modules,
                        onModuleSelected = onModuleSelected,
                    )
                }
            }

            is LearnUiState.QuizInProgress,
            is LearnUiState.QuizResult -> {
                val cached = lastModuleList.value
                if (cached != null && chwId != null) {
                    ModulesScreen(
                        modules = cached,
                        chwId = chwId,
                        onShowQuickLearn = onShowQuickLearn,
                        onShowRefresherQuiz = onShowRefresherQuiz,
                        onTrainingSelect = onModuleSelected,
                        onKnowledgeSelect = onKnowledgeSelect,
                        onRefresherStart = onRefresherStart,
                    )
                }
            }

            // ModuleReady state is intentionally not rendered here — the nav
            // graph skips directly to LessonContent when a module is selected.
            else -> Unit
        }
    }
}

// ── Internal list content ──────────────────────────────────────────────────────

@Composable
internal fun ModuleListContent(
    modules: List<LearnModule>,
    onModuleSelected: (LearnModule) -> Unit,
) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text(
            text = stringResource(R.string.learn_module_list_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.learn_module_list_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(modules, key = { it.scenarioId }) { module ->
            ModuleCard(
                module = module,
                onClick = { onModuleSelected(module) },
            )
        }
    }
}

// ── Previews ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun PreviewModuleReadyScreen_List() {
    val modules = listOf(
        LearnModule("1", "Hypertension Screening", "How to use a digital BP monitor.", "hypertension", status = "in_progress"),
        LearnModule("2", "Maternal Danger Signs", "Identifying pre-eclampsia.", "maternal_health", status = "assigned"),
        LearnModule("3", "Diabetes Referral", "When to refer.", "diabetes", status = "completed"),
    )
    MicroCoachingTheme {
        ModuleReadyScreen(uiState = LearnUiState.ModuleList(modules))
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewModuleReadyScreen_Loading() {
    MicroCoachingTheme {
        ModuleReadyScreen(uiState = LearnUiState.Loading)
    }
}
