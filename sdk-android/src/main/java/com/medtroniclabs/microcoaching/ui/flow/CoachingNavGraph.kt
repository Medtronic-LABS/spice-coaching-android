package com.medtroniclabs.microcoaching.ui.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet.QuickLearnBottomSheet
import com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet.RefresherBottomSheet
import com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet.RefresherQuizBottomSheet
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.learn.ModuleReadyScreen
import com.medtroniclabs.microcoaching.ui.learn.LessonCompleteScreen
import com.medtroniclabs.microcoaching.ui.learn.LessonPlayerScreen
import com.medtroniclabs.microcoaching.ui.learn.ModuleDetailScreen
import com.medtroniclabs.microcoaching.ui.quiz.QuizQuestionScreen
import com.medtroniclabs.microcoaching.ui.quiz.QuizResultScreen
import com.medtroniclabs.microcoaching.ui.onboarding.CoachMarkScreen
import com.medtroniclabs.microcoaching.ui.onboarding.OnboardingSlideScreen
import com.medtroniclabs.microcoaching.ui.onboarding.OnboardingViewModel

/**
 * The full coaching flow navigation graph.
 *
 * Hosted inside [CoachingFlowActivity]. All navigation is internal.
 *
 * @param navController The controller for this graph.
 * @param startRoute The first route to display.
 * @param chwId The CHW identifier — used by [LearnViewModel] for personalisation.
 * @param onFinish Called when the user taps "Back to SPICE" on the result screen.
 */
@Composable
fun CoachingNavGraph(
    navController: NavHostController,
    startRoute: String,
    chwId: String,
    fragmentManager: FragmentManager,
    viewModelStoreOwner: ViewModelStoreOwner,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val onboardingVm: OnboardingViewModel = viewModel()
    // Bind the LearnViewModel to the hosting Activity so out-of-graph callers
    // — RefresherQuizBottomSheet most importantly — resolve the SAME instance
    // via ViewModelProvider(requireActivity(), …). The activity is passed in
    // explicitly because `SdkLocaleHelper.wrap()` produces a detached
    // ContextImpl that can't be walked back to the activity.
    val learnVm: LearnViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        factory = LearnViewModel.factory(context, chwId),
    )
    android.util.Log.d(
        "CoachingNavGraph",
        "init: chwId=$chwId fragmentManager=${fragmentManager.javaClass.simpleName} " +
            "vmOwner=${viewModelStoreOwner.javaClass.simpleName}",
    )

    NavHost(navController = navController, startDestination = startRoute) {

        // ── Onboarding ─────────────────────────────────────────────────────────

        composable(CoachingRoute.CoachMark.route) {
            CoachMarkScreen(
                onDismiss = {
                    onboardingVm.markOnboarded()
                    navController.navigate(CoachingRoute.OnboardingSlides.route) {
                        popUpTo(CoachingRoute.CoachMark.route) { inclusive = true }
                    }
                }
            )
        }

        composable(CoachingRoute.OnboardingSlides.route) {
            val uiState by onboardingVm.uiState.collectAsState()
            OnboardingSlideScreen(
                uiState = uiState,
                onNext = onboardingVm::nextSlide,
                onSkip = {
                    onboardingVm.markSlideDone()
                    navController.navigate(CoachingRoute.ModuleReady.route) {
                        popUpTo(CoachingRoute.OnboardingSlides.route) { inclusive = true }
                    }
                },
                onDone = {
                    onboardingVm.markSlideDone()
                    navController.navigate(CoachingRoute.ModuleReady.route) {
                        popUpTo(CoachingRoute.OnboardingSlides.route) { inclusive = true }
                    }
                },
            )
        }

        // ── Learn ──────────────────────────────────────────────────────────────

        composable(CoachingRoute.ModuleReady.route) {
            val uiState by learnVm.uiState.collectAsState()
            ModuleReadyScreen(
                uiState = uiState,
                onModuleSelected = { module ->
                    // Fix 1: skip FocusedModuleContent — go straight to ModuleDetailScreen.
                    learnVm.selectModule(module)
                    learnVm.startLesson()
                    navController.navigate(CoachingRoute.LessonContent.route)
                },
                onStartLearning = { /* no-op — skipped by Fix 1 */ },
                onClose = onFinish,
                chwId = chwId,
                onRefresherStart = { module -> learnVm.selectModuleForQuiz(module) },
                onKnowledgeSelect = { module ->
                    learnVm.selectModule(module)
                    learnVm.startLesson()
                    navController.navigate(CoachingRoute.LessonContent.route)
                },
                onShowQuickLearn = {
                    android.util.Log.d("CoachingNavGraph", "Showing RefresherBottomSheet (question-first)")
                    RefresherBottomSheet.show(fragmentManager, chwId, fromHomeScreen = false)
                },
                onShowRefresherQuiz = {
                    android.util.Log.d("CoachingNavGraph", "Showing RefresherQuizBottomSheet")
                    RefresherQuizBottomSheet.show(fragmentManager, chwId)
                },
            )
        }

        composable(CoachingRoute.LessonContent.route) {
            val uiState by learnVm.uiState.collectAsState()
            ModuleDetailScreen(
                uiState = uiState,
                onContinueToQuiz = {
                    learnVm.startQuiz()
                    navController.navigate(CoachingRoute.QuizQuestion.routeFor(0))
                },
                onStartCourse = {
                    learnVm.startCourse()
                    navController.navigate(CoachingRoute.LessonPlayer.route)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(CoachingRoute.LessonPlayer.route) {
            val uiState by learnVm.uiState.collectAsState()
            val module = (uiState as? LearnUiState.LessonContent)?.module
            if (module != null) {
                // The module overload reads SDK language internally.
                LessonPlayerScreen(
                    module = module,
                    onBack = { navController.popBackStack() },
                    onStartQuiz = {
                        learnVm.startQuiz()
                        navController.navigate(CoachingRoute.QuizQuestion.routeFor(0))
                    },
                    onCardShown = { idx: Int -> learnVm.recordCardShown(idx) },
                )
            }
        }

        composable(CoachingRoute.LessonComplete.route) {
            LessonCompleteScreen(
                onBack = {
                    navController.navigate(CoachingRoute.ModuleReady.route) {
                        popUpTo(CoachingRoute.ModuleReady.route) { inclusive = false }
                    }
                }
            )
        }

        composable(
            route = CoachingRoute.QuizQuestion.route,
            arguments = listOf(
                navArgument(CoachingRoute.QuizQuestion.ARG_QUESTION_INDEX) {
                    type = NavType.IntType
                }
            ),
        ) { backStack ->
            val index = backStack.arguments?.getInt(CoachingRoute.QuizQuestion.ARG_QUESTION_INDEX) ?: 0
            val uiState by learnVm.uiState.collectAsState()
            QuizQuestionScreen(
                uiState = uiState,
                questionIndex = index,
                onAnswerSelected = { answerIndex -> learnVm.selectAnswer(index, answerIndex) },
                onNext = {
                    val nextIndex = index + 1
                    if (learnVm.hasQuestion(nextIndex)) {
                        navController.navigate(CoachingRoute.QuizQuestion.routeFor(nextIndex))
                    } else {
                        learnVm.finishQuiz()
                        navController.navigate(CoachingRoute.QuizResult.route) {
                            popUpTo(CoachingRoute.ModuleReady.route)
                        }
                    }
                },
                onBack = {
                    // Always return to module detail (Fix 6 — LessonPlayer shows
                    // white screen because it requires LessonContent state).
                    learnVm.restoreModuleDetail()
                    navController.navigate(CoachingRoute.LessonContent.route) {
                        popUpTo(CoachingRoute.LessonContent.route) { inclusive = false }
                    }
                },
            )
        }

        composable(CoachingRoute.QuizResult.route) {
            val uiState by learnVm.uiState.collectAsState()
            QuizResultScreen(
                uiState = uiState,
                isRefresherQuiz = learnVm.startedViaRefresher,
                onNextModule = {
                    learnVm.backToModuleList()
                    navController.navigate(CoachingRoute.ModuleReady.route) {
                        popUpTo(CoachingRoute.ModuleReady.route) { inclusive = true }
                    }
                },
                onBackToSpice = onFinish,
                onTryAgain = if (learnVm.startedViaCourse) {
                    {
                        // Restart from card 0 — navigate back to the lesson player.
                        navController.navigate(CoachingRoute.LessonPlayer.route) {
                            popUpTo(CoachingRoute.LessonPlayer.route) { inclusive = true }
                        }
                    }
                } else null,
            )
        }
    }
}

