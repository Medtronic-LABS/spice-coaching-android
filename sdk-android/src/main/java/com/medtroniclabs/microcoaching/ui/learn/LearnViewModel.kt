package com.medtroniclabs.microcoaching.ui.learn

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.repository.GapProfileRepository
import com.medtroniclabs.microcoaching.data.repository.GapProfileRepositoryImpl
import com.medtroniclabs.microcoaching.data.repository.ModuleRepository
import com.medtroniclabs.microcoaching.data.repository.ModuleRepositoryImpl
import com.medtroniclabs.microcoaching.domain.telemetry.eventFamilyFor
import com.medtroniclabs.microcoaching.network.QuizAnswerRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * ViewModel for the v3 module → lesson → quiz → result flow.
 *
 * Module-only. The scenario-cache fallback was removed in 0.3.0; all rendering
 * now comes from `module_cache` rows synced via `/sync/modules`. Per-CHW
 * completion state is held in memory for the session; persistent
 * [com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity]
 * writes are wired up in a later phase.
 *
 * @param chwId The CHW currently using the app. Defaults to "unknown_chw" so
 *   the flow never crashes when SPICE hasn't supplied the ID yet.
 */
class LearnViewModel(
    private val context: Context,
    private val chwId: String,
    private val gapRepo: GapProfileRepository,
    private val moduleRepo: ModuleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LearnUiState>(LearnUiState.Loading)
    val uiState: StateFlow<LearnUiState> = _uiState.asStateFlow()

    /** Questions for the currently active module — loaded when the quiz starts. */
    private var activeQuestions: List<QuizQuestion> = emptyList()

    /** The module the CHW is currently working through. */
    private var activeModule: LearnModule? = null

    /** In-memory module status map: moduleFamilyId → "assigned"|"in_progress"|"completed". */
    private val statusByModule: MutableMap<String, String> = mutableMapOf()

    /** True when the CHW entered the quiz via [startCourse] (lesson-player path). Used by
     *  [CoachingNavGraph] to decide whether "Try Again" should restart the full course. */
    var startedViaCourse: Boolean = false
        private set

    /** True when the CHW entered the quiz via [selectModuleForQuiz] from the refresher list.
     *  Used by [QuizResultScreen] to show "Back to Refreshers" instead of "More Modules". */
    var startedViaRefresher: Boolean = false
        private set

    private var _quizCorrectCount = 0
    private var _quizTotalCount = 0

    init {
        viewModelScope.launch { initialise() }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private suspend fun initialise() {
        try {
            val sdk = MicroCoachingSDK.getInstance()
            val moduleCount = moduleRepo.countActive()
            if (moduleCount == 0 && sdk.config.backendUrl.isNotBlank()) {
                Log.i(TAG, "module_cache empty on Learn open — triggering inbound sync.")
                sdk.syncCoordinator.triggerNow()
            }
            observeModules(sdk)
        } catch (e: Exception) {
            Log.e(TAG, "Initialisation failed: ${e.message}", e)
            _uiState.value = LearnUiState.Error(localized(R.string.learn_error_failed_load))
        }
    }

    private suspend fun observeModules(sdk: MicroCoachingSDK) {
        moduleRepo.getAllActive().collectLatest { modules ->
            val gapEntries = gapRepo.getAllForChw(chwId)
            val activeGapKeys = gapEntries.filter { it.gapActive }.map { it.behaviouralGapId }.toSet()

            // Enrich each module with morning-card source / gap id so the
            // refresher list can show the GAP badge and telemetry can carry the gap id.
            val morningCardsByModuleId = sdk.database.morningCardCacheDao()
                .getAllOrderedOnce()
                .associateBy { it.moduleId }

            val mapped = modules.mapNotNull { entity ->
                val status = statusByModule[entity.moduleFamilyId] ?: "assigned"
                val card = morningCardsByModuleId[entity.moduleId]
                entity.toLearnModule(
                    status = status,
                    gapCode = null,
                    behaviouralGapId = card?.behaviouralGapId,
                    source = card?.source,
                )
            }.sortedWith(
                compareBy(
                    { if (activeGapKeys.contains(it.scenarioId)) 0 else 1 },
                    {
                        when (it.status) {
                            "in_progress" -> 0
                            "assigned" -> 1
                            "completed" -> 2
                            else -> 3
                        }
                    },
                )
            )

            val refresherCount = mapped.count {
                it.moduleType == "refresher" && !it.inlineQuestions.isNullOrEmpty()
            }
            val refresherEmpty = mapped.count {
                it.moduleType == "refresher" && it.inlineQuestions.isNullOrEmpty()
            }
            val trainingCount = mapped.count { it.moduleType != "refresher" && it.moduleType != "content_update" }
            val knowledgeCount = mapped.count { it.moduleType == "content_update" }
            val sourceGap = mapped.count { it.source == "gap" }
            val sourceFallback = mapped.count { it.source == "fallback" }
            val sourceNull = mapped.count { it.source == null }
            Log.i(
                TAG,
                "observeModules: total=${mapped.size} refreshers=$refresherCount " +
                    "(skipped_empty=$refresherEmpty) training=$trainingCount knowledge=$knowledgeCount | " +
                    "source: gap=$sourceGap fallback=$sourceFallback null=$sourceNull",
            )

            _uiState.value = if (mapped.isEmpty()) {
                LearnUiState.Error(emptyMessage(sdk))
            } else {
                LearnUiState.ModuleList(mapped)
            }
        }
    }

    private fun emptyMessage(sdk: MicroCoachingSDK): String = if (sdk.config.backendUrl.isBlank()) {
        localized(R.string.learn_empty_no_backend)
    } else {
        localized(R.string.learn_empty_downloading)
    }

    private fun localized(@androidx.annotation.StringRes resId: Int): String {
        val sdkLanguage = MicroCoachingSDK.getInstance().language
        val ctx = com.medtroniclabs.microcoaching.ui.SdkLocaleHelper.wrap(context, sdkLanguage)
        return ctx.getString(resId)
    }

    // ── Navigation transitions ────────────────────────────────────────────────

    fun selectModule(module: LearnModule) {
        activeModule = module
        statusByModule[module.scenarioId] = "in_progress"
        _uiState.value = LearnUiState.ModuleReady(module)
        viewModelScope.launch {
            // Surfacing a module to the CHW maps to backend `module_delivered`.
            recordEvent(
                eventType = "module_delivered",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.scenarioId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                cardFamilyId = module.cardFamilyId,
            )
        }
    }

    /**
     * Restores state to [LearnUiState.LessonContent] without emitting telemetry.
     * Called when the CHW presses back from the quiz to return to module detail.
     */
    fun restoreModuleDetail() {
        val module = activeModule ?: return
        _uiState.value = LearnUiState.LessonContent(module)
    }

    /**
     * Marks the active module as in-progress when the CHW taps "Start Course".
     * Navigation to [LessonPlayerScreen] is handled by [CoachingNavGraph].
     */
    fun startCourse() {
        val module = activeModule ?: return
        statusByModule[module.scenarioId] = "in_progress"
        startedViaCourse = true
    }

    /**
     * Parses the active module's `cardsJson` into a typed [LessonCard] list.
     * Used by [CoachingNavGraph] to supply cards to [LessonPlayerScreen].
     */
    fun getCurrentCards(): List<LessonCard> =
        parseLessonCards(activeModule?.cardsJson ?: "[]")

    /**
     * Emits a `module_card_viewed` telemetry event when the CHW views a card
     * in [LessonPlayerScreen]. Called via `LaunchedEffect(currentIndex)`.
     */
    fun recordCardShown(cardIndex: Int) {
        val module = activeModule ?: return
        viewModelScope.launch {
            recordEvent(
                eventType = "module_card_viewed",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.scenarioId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
            )
        }
    }

    fun startLesson() {
        val module = activeModule ?: return
        _uiState.value = LearnUiState.LessonContent(module)
        viewModelScope.launch {
            // Recording the first card view as the CHW enters the lesson body.
            recordEvent(
                eventType = "module_card_viewed",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.scenarioId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                cardFamilyId = module.cardFamilyId,
            )
        }
    }

    fun startQuiz() {
        val module = activeModule ?: return
        _quizCorrectCount = 0
        _quizTotalCount = 0
        viewModelScope.launch {
            activeQuestions = module.inlineQuestions ?: emptyList()
            _uiState.value = LearnUiState.QuizInProgress(questions = activeQuestions)
            recordEvent(
                eventType = "quiz_started",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                moduleFamilyId = module.scenarioId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
            )
        }
    }

    /**
     * Refresher shortcut: jump straight from the module list to the quiz,
     * skipping the lesson-content state. Used by the v0.3.2 RefresherQuiz
     * bottom sheet. Telemetry path: `module_delivered` → `quiz_started` →
     * `quiz_answered` (×N) → `module_quiz_attempted` → `module_completed`.
     */
    fun selectModuleForQuiz(module: LearnModule) {
        activeModule = module
        statusByModule[module.scenarioId] = "in_progress"
        startedViaRefresher = true
        _quizCorrectCount = 0
        _quizTotalCount = 0
        val questions = module.inlineQuestions.orEmpty()
        Log.d(
            TAG,
            "selectModuleForQuiz: family=${module.scenarioId} moduleId=${module.moduleId} " +
                "type=${module.moduleType} questionCount=${questions.size}",
        )
        viewModelScope.launch {
            recordEvent(
                eventType = "module_delivered",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.scenarioId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                cardFamilyId = module.cardFamilyId,
            )
            activeQuestions = questions
            _uiState.value = LearnUiState.QuizInProgress(questions = activeQuestions)
            recordEvent(
                eventType = "quiz_started",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                moduleFamilyId = module.scenarioId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
            )
        }
    }

    /**
     * One-shot taste used by the Quick learn banner. Emits `quiz_answered`
     * AND `module_quiz_attempted` (W3) so a wrong banner answer forms a gap
     * state on the backend — enabling the refresher to surface on the next
     * morning-cards call.
     */
    fun recordQuickLearnAnswer(
        module: LearnModule,
        question: QuizQuestion,
        answerIndex: Int,
    ) {
        val isCorrect = answerIndex == question.correctIndex
        viewModelScope.launch {
            recordEvent(
                eventType = "quiz_answered",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                quizQuestionId = question.id,
                selectedOption = answerIndex,
                isCorrect = isCorrect,
                moduleFamilyId = module.scenarioId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                quizFamilyId = question.id,
            )
            // Single-question attempt counts as a full quiz attempt for gap
            // computation: score is 100% (correct) or 0% (incorrect).
            recordEvent(
                eventType = "module_quiz_attempted",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                moduleFamilyId = module.scenarioId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                quizScorePct = if (isCorrect) 1f else 0f,
                outcomeOverride = if (isCorrect) "correct" else "wrong",
                behaviouralGapId = module.behaviouralGapId,
            )
            MicroCoachingSDK.getInstance().flushTelemetryNow()
        }
    }

    fun selectAnswer(questionIndex: Int, answerIndex: Int) {
        _uiState.update { state ->
            if (state is LearnUiState.QuizInProgress) {
                state.copy(answers = state.answers + (questionIndex to answerIndex))
            } else state
        }
        val question = activeQuestions.getOrNull(questionIndex) ?: return
        val isCorrect = answerIndex == question.correctIndex
        _quizTotalCount++
        if (isCorrect) _quizCorrectCount++
        val scorePct = _quizCorrectCount.toFloat() / _quizTotalCount
        viewModelScope.launch {
            recordEvent(
                eventType = "quiz_answered",
                clinicalDomain = activeModule?.clinicalDomain,
                cardType = "quiz",
                quizQuestionId = question.id,
                selectedOption = answerIndex,
                isCorrect = isCorrect,
                moduleFamilyId = activeModule?.scenarioId,
                moduleId = activeModule?.moduleId,
                moduleVersion = activeModule?.moduleVersion,
                quizFamilyId = question.id,
                quizScorePct = scorePct,
            )
        }
    }

    fun hasQuestion(index: Int): Boolean = index < activeQuestions.size

    fun finishQuiz() {
        val state = _uiState.value as? LearnUiState.QuizInProgress ?: return
        val module = activeModule ?: return

        val sdk = MicroCoachingSDK.getInstance()
        val passThreshold = sdk.config.quizPassThreshold
        val correctCount = state.questions.indices.count { idx ->
            state.answers[idx] == state.questions[idx].correctIndex
        }
        val scorePercent = if (state.questions.isEmpty()) 0
        else (correctCount * 100) / state.questions.size
        val passed = scorePercent >= passThreshold

        val badge = when {
            scorePercent >= 80 -> localized(R.string.badge_expert)
            scorePercent >= passThreshold -> localized(R.string.badge_learner)
            else -> localized(R.string.badge_practice)
        }

        statusByModule[module.scenarioId] = if (passed) "completed" else "in_progress"

        _uiState.value = LearnUiState.QuizResult(
            scorePercent = scorePercent,
            correctCount = correctCount,
            totalCount = state.questions.size,
            badgeLabel = badge,
            completedScenarioId = module.scenarioId,
            questions = state.questions,
            answers = state.answers,
        )

        viewModelScope.launch {
            state.questions.forEachIndexed { idx, question ->
                val isCorrect = state.answers[idx] == question.correctIndex
                gapRepo.recordQuizAnswer(
                    chwId = chwId,
                    behaviouralGapId = activeModule?.behaviouralGapId,
                    clinicalDomain = module.clinicalDomain,
                    isCorrect = isCorrect,
                )
            }
            // Persist module completion + emit quiz_completed telemetry.
            sdk.onModuleQuizCompleted(
                moduleFamilyId = module.scenarioId,
                moduleId = module.moduleId,
                scoreFraction = scorePercent / 100f,
                passed = passed,
            )
            recordEvent(
                eventType = "module_quiz_attempted",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                moduleFamilyId = module.scenarioId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                quizScorePct = scorePercent / 100f,
                outcomeOverride = if (passed) "correct" else "wrong",
                behaviouralGapId = module.behaviouralGapId,
            )
            if (passed) {
                recordEvent(
                    eventType = "module_completed",
                    clinicalDomain = module.clinicalDomain,
                    cardType = "info",
                    moduleFamilyId = module.scenarioId,
                    moduleId = module.moduleId,
                    moduleVersion = module.moduleVersion,
                )
            }

            // Quiz attempt is a meaningful milestone — flush the batch
            // immediately rather than waiting for the 15-min WorkManager tick.
            // Couples nicely with the gradeQuizAnswer round-trip below: by the
            // time the host gets the result screen, the backend has both the
            // graded answer AND the full telemetry batch.
            sdk.flushTelemetryNow()

            if (sdk.config.backendUrl.isNotBlank()) {
                Log.d(TAG, "gradeQuizAnswer: firing for ${state.questions.size} question(s) — chwId=$chwId backendUrl=${sdk.config.backendUrl}")
                state.questions.forEachIndexed { idx, question ->
                    runCatching {
                        val resp = sdk.apiService.gradeQuizAnswer(
                            QuizAnswerRequest(
                                quizQuestionId = question.id,
                                selectedIndex = state.answers[idx] ?: -1,
                                chwId = chwId,
                            )
                        )
                        Log.d(TAG, "gradeQuizAnswer: q=${question.id} → HTTP ${resp.code()}")
                    }.onFailure { e ->
                        Log.w(TAG, "gradeQuizAnswer: q=${question.id} → FAILED: ${e.message}")
                    }
                }
            } else {
                Log.d(TAG, "gradeQuizAnswer: skipped — backendUrl is blank")
            }
        }
    }

    fun backToModuleList() {
        activeModule = null
        activeQuestions = emptyList()
        startedViaCourse = false
        startedViaRefresher = false
        _uiState.value = LearnUiState.Loading
        viewModelScope.launch { initialise() }
    }

    // ── Event recording ───────────────────────────────────────────────────────

    private suspend fun recordEvent(
        eventType: String,
        clinicalDomain: String? = null,
        cardType: String? = null,
        quizQuestionId: String? = null,
        selectedOption: Int? = null,
        isCorrect: Boolean? = null,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        moduleVersion: Int? = null,
        cardFamilyId: String? = null,
        quizFamilyId: String? = null,
        quizScorePct: Float? = null,
        outcomeOverride: String? = null,
        behaviouralGapId: String? = null,
    ) {
        try {
            val db = MicroCoachingSDK.getInstance().database
            val sdkVersion = try {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName ?: "0.0"
            } catch (_: Exception) { "0.0" }

            val outcome = outcomeOverride ?: when {
                eventType == "quiz_answered" && isCorrect != null ->
                    if (isCorrect) "correct" else "incorrect"
                else -> null
            }

            db.coachingEventDao().insert(
                CoachingEventEntity(
                    eventId = UUID.randomUUID().toString(),
                    sdkVersion = sdkVersion,
                    eventFamily = eventFamilyFor(eventType),
                    sessionId = sessionId,
                    chwId = chwId,
                    eventType = eventType,
                    clinicalDomain = clinicalDomain,
                    cardType = cardType,
                    triggerType = "morning",
                    inferenceMode = "cached",
                    quizQuestionId = quizQuestionId,
                    selectedOption = selectedOption,
                    isCorrect = isCorrect,
                    outcome = outcome,
                    moduleFamilyId = moduleFamilyId,
                    moduleId = moduleId,
                    moduleVersion = moduleVersion,
                    cardFamilyId = cardFamilyId,
                    quizFamilyId = quizFamilyId,
                    quizScorePct = quizScorePct,
                    behaviouralGapId = behaviouralGapId,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record event '$eventType': ${e.message}")
        }
    }

    // ── Language ──────────────────────────────────────────────────────────────

    private val lang: String
        get() = if (MicroCoachingSDK.getInstance().config.language == Language.ENGLISH) "en" else "bn"

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun ModuleEntity.toLearnModule(
        status: String,
        gapCode: String?,
        behaviouralGapId: String? = null,
        source: String? = null,
    ): LearnModule? {
        val l = lang
        val firstCard = try {
            Json.parseToJsonElement(cardsJson).jsonArray.firstOrNull()?.jsonObject
        } catch (_: Exception) { null }

        val title = firstCard?.get("title_$l")?.jsonPrimitive?.contentOrNullSafe()
            ?: firstCard?.get("title_bn")?.jsonPrimitive?.contentOrNullSafe()
            ?: (if (l == "en") titleEn else null) ?: titleBn ?: titleEn ?: return null
        val body = firstCard?.get("body_$l")?.jsonPrimitive?.contentOrNullSafe()
            ?: firstCard?.get("body_bn")?.jsonPrimitive?.contentOrNullSafe()
            ?: (if (l == "en") descriptionEn else null) ?: descriptionBn ?: descriptionEn ?: ""
        val nextStep = firstCard?.get("next_action_$l")?.jsonPrimitive?.contentOrNullSafe()
            ?: firstCard?.get("next_action_bn")?.jsonPrimitive?.contentOrNullSafe() ?: ""

        val inlineQuestions = parseInlineQuiz(quizJson, l)
        val quizIds = inlineQuestions.map { it.id }

        val firstCardFamilyId = firstCard?.get("card_family_id")?.jsonPrimitive
            ?.contentOrNullSafe()

        // content_update fields — present only on cards whose parent module is
        // a content_update type. Pulled from the first card row so the
        // Knowledge preview screen can render the protocol-change framing.
        val previousPracticeBn = firstCard?.get("previous_practice_bn")?.jsonPrimitive?.contentOrNullSafe()
        val currentPracticeBn = firstCard?.get("current_practice_bn")?.jsonPrimitive?.contentOrNullSafe()
        val rationaleForChangeBn = firstCard?.get("rationale_for_change_bn")?.jsonPrimitive?.contentOrNullSafe()
        val nextActionBn = firstCard?.get("next_action_bn")?.jsonPrimitive?.contentOrNullSafe()

        return LearnModule(
            scenarioId = moduleFamilyId,
            title = title,
            body = body,
            clinicalDomain = gapCode ?: domain ?: "general",
            warningSigns = emptyList(),
            nextStep = nextStep,
            referralDestination = null,
            quizIds = quizIds,
            status = status,
            inlineQuestions = inlineQuestions.takeIf { it.isNotEmpty() },
            moduleId = moduleId,
            moduleVersion = version,
            cardFamilyId = firstCardFamilyId,
            moduleType = moduleType,
            estimatedMinutes = estimatedMinutes,
            previousPracticeBn = previousPracticeBn,
            currentPracticeBn = currentPracticeBn,
            rationaleForChangeBn = rationaleForChangeBn,
            nextActionBn = nextActionBn,
            behaviouralGapId = behaviouralGapId,
            source = source,
            cardsJson = cardsJson,
        )
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is JsonNull) null else content

    // ── Companion / Factory ───────────────────────────────────────────────────

    companion object {
        private const val TAG = "LearnViewModel"

        private val sessionId: String = UUID.randomUUID().toString()

        fun factory(context: Context, chwId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val db = MicroCoachingSDK.getInstance().database
                    return LearnViewModel(
                        context = context.applicationContext,
                        chwId = chwId,
                        gapRepo = GapProfileRepositoryImpl(db.chwGapProfileDao()),
                        moduleRepo = ModuleRepositoryImpl(db.moduleDao(), db.behaviouralGapDao()),
                    ) as T
                }
            }
    }
}
