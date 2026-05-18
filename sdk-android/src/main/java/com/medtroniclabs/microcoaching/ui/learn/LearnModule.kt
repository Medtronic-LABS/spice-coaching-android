package com.medtroniclabs.microcoaching.ui.learn

/**
 * UI model for a learning module (backed by [ScenarioCacheEntity]).
 *
 * Replaces [StubModule] — all content comes from Room DB populated via inbound scenario sync.
 *
 * @param scenarioId Stable key matching [ScenarioCacheEntity.scenarioId].
 * @param title Display title from bangla_card.title.
 * @param body Module description from bangla_card.body.
 * @param clinicalDomain Used for domain colour/chip display.
 * @param warningSigns List of Bangla warning sign strings.
 * @param nextStep CHW action text in Bangla.
 * @param referralDestination Referral facility name in Bangla, or null.
 * @param quizIds IDs of quiz questions linked to this module.
 * @param status Learning path status: assigned | in_progress | completed.
 */
data class LearnModule(
    val scenarioId: String,
    val title: String,
    val body: String,
    val clinicalDomain: String,
    val warningSigns: List<String> = emptyList(),
    val nextStep: String = "",
    val referralDestination: String? = null,
    val quizIds: List<String> = emptyList(),
    val status: String = "assigned",
    /**
     * Quiz questions inlined with the module bundle (v3.3 module_cache origin).
     *
     * When non-null, [com.medtroniclabs.microcoaching.ui.learn.LearnViewModel.startQuiz]
     * uses these directly instead of querying [quizIds] against the legacy
     * `quiz_question_cache`. Modules from the v3.3 pipeline ship cards and quiz
     * together — there's no separate quiz table.
     */
    val inlineQuestions: List<QuizQuestion>? = null,
    /** Version-specific module UUID (Module.id). Null until backend exposes it in the sync bundle. */
    val moduleId: String? = null,
    /** Module content version at the time this module was synced. */
    val moduleVersion: Int? = null,
    /** card_family_id of the first (walkthrough) card. Used for card_shown telemetry. */
    val cardFamilyId: String? = null,
    /**
     * Backend `module_type` enum value: "refresher" | "content_update" |
     * "digital_proficiency". Drives section assignment on the v0.3.2 modules
     * screen and determines whether the tap path goes directly to quiz
     * (refresher) or through the lesson-content flow.
     */
    val moduleType: String = "refresher",
    /** Estimated minutes for non-refresher modules. Drives the Training-card meta line. */
    val estimatedMinutes: Int? = null,
    /** content_update fields — populated only when [moduleType] == "content_update". */
    val previousPracticeBn: String? = null,
    val currentPracticeBn: String? = null,
    val rationaleForChangeBn: String? = null,
    val nextActionBn: String? = null,
    /**
     * Gap ID carried from the morning-cards response when this module was surfaced because of
     * a behavioural gap. Forwarded into [TelemetryEventPayload.payloadJson] on
     * `module_quiz_attempted` events so the backend can resolve the gap state.
     * Null for modules opened outside the gap-driven morning surface.
     */
    val behaviouralGapId: String? = null,
    /**
     * Surface source from the morning-cards response: "gap" | "fallback" | null.
     * Used to display the GAP badge on the refresher tile.
     */
    val source: String? = null,
    /**
     * Raw `cards_json` from [ModuleEntity] — forwarded so [LessonPlayerScreen] and
     * [ModuleDetailScreen] can parse the card list without a DB round-trip.
     * Defaults to `"[]"` when not available (e.g. QuickLearn minimal mapping).
     */
    val cardsJson: String = "[]",
)

/**
 * UI model for a single quiz question (backed by [QuizQuestionCacheEntity]).
 *
 * Replaces [StubQuiz].
 *
 * @param id Stable question ID from the backend.
 * @param questionText The question shown to the CHW (Bangla).
 * @param answers List of 3–4 Bangla answer option strings.
 * @param correctIndex Zero-based index of the correct answer.
 * @param explanation Bangla explanation shown after the CHW answers.
 * @param pointValue Score points for a correct answer.
 */
data class QuizQuestion(
    val id: String,
    val questionText: String,
    val answers: List<String>,
    val correctIndex: Int,
    val explanation: String = "",
    val caseSetup: String = "",
    val pointValue: Int = 10,
)
