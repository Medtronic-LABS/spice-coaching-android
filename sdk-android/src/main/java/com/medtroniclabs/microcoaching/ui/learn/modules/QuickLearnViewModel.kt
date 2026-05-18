package com.medtroniclabs.microcoaching.ui.learn.modules

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.ui.common.translatedText
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * State holder for the Quick learn banner + single-question bottom sheet.
 *
 * Observes [MicroCoachingSDK.morningModules] and exposes the first inline
 * quiz question of the top-priority module. When no module is surfaced (empty
 * list) [quickQuestion] is null and the banner hides.
 *
 * Telemetry: routes the single-answer event through
 * [LearnViewModel.recordQuickLearnAnswer] so we emit exactly one
 * `quiz_answered` row without disturbing module-completion state.
 */
class QuickLearnViewModel(
    private val learnViewModel: LearnViewModel,
    private val morningModulesSource: StateFlow<List<ModuleEntity>>,
    private val morningCardsSource: StateFlow<List<MorningCardCacheEntity>>,
) : ViewModel() {

    /**
     * Mutable backing flow for the answer state — `null` while the question
     * is unanswered, then a typed [AnswerOutcome] until the sheet auto-closes.
     */
    private val _answerState = MutableStateFlow<AnswerOutcome?>(null)
    val answerState: StateFlow<AnswerOutcome?> = _answerState.asStateFlow()

    /**
     * The question to display on the banner + inside the sheet. Derived from
     * the top module's first inline quiz row. Recomputes whenever the
     * upstream morning list changes.
     */
    val quickQuestion: StateFlow<QuickQuestion?> = morningModulesSource
        .map { modules -> firstQuestionOf(modules.firstOrNull()) }
        .let { flow ->
            // Convert cold map to a StateFlow with the initial computed value
            // so Compose can render synchronously on first frame.
            val initial = firstQuestionOf(morningModulesSource.value.firstOrNull())
            val stateFlow = MutableStateFlow(initial)
            viewModelScope.launch { flow.collect { stateFlow.value = it } }
            stateFlow.asStateFlow()
        }

    /** Look up the morning-card cache for the top module's source / gap id. */
    private fun morningCardFor(moduleId: String?): MorningCardCacheEntity? =
        if (moduleId == null) null
        else morningCardsSource.value.firstOrNull { it.moduleId == moduleId }

    /**
     * Record the CHW's answer for the current question and freeze a result
     * for the UI overlay. The bottom sheet uses [answerState] to drive the
     * 2-second feedback overlay before calling [reset].
     */
    fun submitAnswer(answerIndex: Int) {
        val current = quickQuestion.value ?: return
        if (_answerState.value != null) return // already answered
        learnViewModel.recordQuickLearnAnswer(
            module = current.module,
            question = current.question,
            answerIndex = answerIndex,
        )
        _answerState.value = AnswerOutcome(
            selectedIndex = answerIndex,
            isCorrect = answerIndex == current.question.correctIndex,
        )
    }

    /** Clear the answer overlay so the banner returns to its idle state. */
    fun reset() {
        _answerState.value = null
    }

    private fun firstQuestionOf(entity: ModuleEntity?): QuickQuestion? {
        if (entity == null) {
            android.util.Log.d(TAG, "no morning module available — banner hidden")
            return null
        }
        val sdk = MicroCoachingSDK.getInstance()
        val lang = if (sdk.config.language == Language.ENGLISH) "en" else "bn"
        val parsed = parseInlineQuiz(entity.quizJson, lang)
        if (parsed.isEmpty()) {
            android.util.Log.d(
                TAG,
                "morning module '${entity.titleBn}' (familyId=${entity.moduleFamilyId}, type=${entity.moduleType})" +
                    " has no inline questions — banner hidden. quiz_json len=${entity.quizJson.length}",
            )
            return null
        }
        val question = parsed.first()
        android.util.Log.i(
            TAG,
            "QuickLearn selected: lang=$lang sdkLang=${sdk.config.language} " +
                "module='${entity.titleBn}' (familyId=${entity.moduleFamilyId}) " +
                "source=${morningCardFor(entity.moduleId)?.source} " +
                "questionId=${question.id} text='${question.questionText.take(60)}' " +
                "options=${question.answers.size} correctIdx=${question.correctIndex}",
        )
        val card = morningCardFor(entity.moduleId)
        return QuickQuestion(
            module = entity.toMinimalLearnModule(
                behaviouralGapId = card?.behaviouralGapId,
                source = card?.source,
            ),
            question = question,
        )
    }

    companion object {
        private const val TAG = "QuickLearnVM"

        fun factory(context: Context, chwId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val sdk = MicroCoachingSDK.getInstance()
                    val learnVm = LearnViewModel.factory(context, chwId).create(LearnViewModel::class.java)
                    return QuickLearnViewModel(
                        learnViewModel = learnVm,
                        morningModulesSource = sdk.morningModules,
                        morningCardsSource = sdk.morningCardsItems,
                    ) as T
                }
            }
    }
}

/** Pairs the source module with the displayed question for telemetry. */
data class QuickQuestion(
    val module: LearnModule,
    val question: QuizQuestion,
)

/** Result of the user's single answer. */
data class AnswerOutcome(
    val selectedIndex: Int,
    val isCorrect: Boolean,
)

/**
 * Minimal mapper for the Quick learn flow — we only need the identifiers
 * required by [LearnViewModel.recordQuickLearnAnswer] (module family id,
 * module version id, clinical domain). The full
 * [com.medtroniclabs.microcoaching.ui.learn.LearnViewModel.toLearnModule]
 * mapper is private and does richer parsing we don't need here.
 */
private fun ModuleEntity.toMinimalLearnModule(
    behaviouralGapId: String? = null,
    source: String? = null,
): LearnModule = LearnModule(
    scenarioId = moduleFamilyId,
    title = translatedText(bn = titleBn, en = titleEn),
    body = translatedText(bn = descriptionBn ?: "", en = descriptionEn),
    clinicalDomain = domain,
    moduleId = moduleId,
    moduleVersion = version,
    moduleType = moduleType,
    behaviouralGapId = behaviouralGapId,
    source = source,
    cardsJson = cardsJson,
)
