package com.medtroniclabs.microcoaching.domain.telemetry

import android.util.Log
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import java.util.UUID

private const val TAG = "EventRecorder"

/**
 * Derives the [event_family] grouping required by the backend from an [eventType] string.
 *
 * Values: coaching | learning | clinical_observed | digital | system
 */
/**
 * Map a backend-canonical event_type to its event_family bucket. Values match
 * the deployed `CoachingEventType` / `DigitalEventType` / `EventFamily` enums
 * exactly so the backend ingestion path can deserialise into typed enums for
 * aggregation rather than falling back to `string`.
 */
fun eventFamilyFor(eventType: String): String = when (eventType) {
    "card_shown", "card_skipped", "card_accepted", "counselling_used",
    "audio_played", "quiz_started", "quiz_answered" -> "coaching"
    "module_delivered", "module_card_viewed", "module_quiz_attempted",
    "module_completed" -> "learning"
    "risk_flag_observed", "spice_action_observed",
    "equipment_anomaly_observed" -> "clinical_observed"
    "sync_attempt", "sync_started", "sync_completed",
    "form_submit", "login_attempt", "digital_help_used" -> "digital"
    else -> "system"  // session_start, session_end, llm_inference, unknown
}

/**
 * Append-only coaching event recorder. The single write path for all CHW interaction events.
 *
 * Replaces [TelemetryManager] for coaching-domain events. Writes directly to Room;
 * the OutboundSyncWorker (Phase B) batches pending rows to POST /telemetry/events.
 *
 * One instance per coaching session — constructed with the session ID and CHW ID
 * that remain constant for the session's lifetime.
 *
 * No batching, no in-memory buffering — each call is an immediate DB insert on the
 * caller's coroutine. Callers are responsible for calling from an IO dispatcher.
 */
class EventRecorder(
    private val dao: CoachingEventDao,
    val sessionId: String,
    private val chwId: String,
    private val sdkVersion: String = BuildConfig.SDK_VERSION,
) {

    suspend fun recordSessionStart() {
        dao.insert(build(eventType = "session_start"))
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[$sessionId] session_start saved — chw=${chwId.sha256Short()}")
        }
    }

    suspend fun recordSessionEnd() {
        dao.insert(build(eventType = "session_end"))
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[$sessionId] session_end saved — chw=${chwId.sha256Short()}")
        }
    }

    suspend fun recordCardViewed(
        scenarioId: String,
        clinicalDomain: String,
        cardType: String,
        triggerType: String,
        inferenceMode: String,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        moduleVersion: Int? = null,
        cardFamilyId: String? = null,
    ) {
        // Backend canonical: module_card_viewed (when module-sourced) or
        // card_shown (legacy). v3 module flow always uses the former.
        val type = if (moduleFamilyId != null) "module_card_viewed" else "card_shown"
        dao.insert(
            build(
                eventType = type,
                scenarioId = scenarioId,
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                triggerType = triggerType,
                inferenceMode = inferenceMode,
                moduleFamilyId = moduleFamilyId,
                moduleId = moduleId,
                moduleVersion = moduleVersion,
                cardFamilyId = cardFamilyId,
            )
        )
        Log.d(TAG, "[$sessionId] $type saved — scenario=$scenarioId domain=$clinicalDomain mode=$inferenceMode module=$moduleFamilyId card=$cardFamilyId")
    }

    suspend fun recordCardDismissed(
        scenarioId: String,
        clinicalDomain: String,
        cardType: String,
    ) {
        // Backend uses `card_skipped` as the canonical dismissed-from-surface event.
        dao.insert(
            build(
                eventType = "card_skipped",
                scenarioId = scenarioId,
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                outcome = "skip",
            )
        )
        Log.d(TAG, "[$sessionId] card_skipped saved — scenario=$scenarioId")
    }

    suspend fun recordCardAccepted(
        scenarioId: String,
        clinicalDomain: String,
        cardType: String,
        inferenceMode: String,
    ) {
        dao.insert(
            build(
                eventType = "card_accepted",
                scenarioId = scenarioId,
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                inferenceMode = inferenceMode,
                outcome = "accepted",
            )
        )
        Log.d(TAG, "[$sessionId] card_accepted saved — scenario=$scenarioId")
    }

    suspend fun recordCounsellingUsed(
        scenarioId: String,
        clinicalDomain: String,
        fallbackUsed: Boolean = false,
        validatorStatus: String? = null,
    ) {
        dao.insert(
            build(
                eventType = "counselling_used",
                scenarioId = scenarioId,
                clinicalDomain = clinicalDomain,
                outcome = "used",
                validatorStatus = validatorStatus,
                fallbackUsed = fallbackUsed,
            )
        )
        Log.d(TAG, "[$sessionId] counselling_used saved — scenario=$scenarioId fallback=$fallbackUsed validator=$validatorStatus")
    }

    suspend fun recordQuizStarted(
        scenarioId: String,
        clinicalDomain: String,
    ) {
        dao.insert(
            build(
                eventType = "quiz_started",
                scenarioId = scenarioId,
                clinicalDomain = clinicalDomain,
                cardType = "QUIZ",
            )
        )
        Log.d(TAG, "[$sessionId] quiz_started saved — scenario=$scenarioId")
    }

    suspend fun recordQuizAnswered(
        scenarioId: String,
        clinicalDomain: String,
        questionId: String,
        selectedOption: Int,
        isCorrect: Boolean,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        moduleVersion: Int? = null,
        quizFamilyId: String? = null,
        quizScorePct: Float? = null,
    ) {
        dao.insert(
            build(
                eventType = "quiz_answered",
                scenarioId = scenarioId,
                clinicalDomain = clinicalDomain,
                cardType = "QUIZ",
                quizQuestionId = questionId,
                selectedOption = selectedOption,
                isCorrect = isCorrect,
                outcome = if (isCorrect) "correct" else "incorrect",
                moduleFamilyId = moduleFamilyId,
                moduleId = moduleId,
                moduleVersion = moduleVersion,
                quizFamilyId = quizFamilyId,
                quizScorePct = quizScorePct,
            )
        )
        Log.d(TAG, "[$sessionId] quiz_answered saved — q=$questionId option=$selectedOption correct=$isCorrect score=$quizScorePct")
    }

    suspend fun recordChatbotEvent(
        inferenceMode: String,
        validatorStatus: String? = null,
        fallbackUsed: Boolean = false,
        networkState: String? = null,
    ) {
        dao.insert(
            build(
                eventType = "chatbot",
                inferenceMode = inferenceMode,
                validatorStatus = validatorStatus,
                fallbackUsed = fallbackUsed,
                networkState = networkState,
            )
        )
        Log.d(TAG, "[$sessionId] chatbot saved — mode=$inferenceMode validator=$validatorStatus fallback=$fallbackUsed")
    }

    suspend fun recordModuleStarted(
        scenarioId: String,
        clinicalDomain: String,
    ) {
        // Backend canonical event for module surfacing is `module_delivered`.
        dao.insert(
            build(
                eventType = "module_delivered",
                scenarioId = scenarioId,
                clinicalDomain = clinicalDomain,
            )
        )
        Log.d(TAG, "[$sessionId] module_delivered saved — scenario=$scenarioId")
    }

    suspend fun recordModuleCompleted(
        scenarioId: String,
        clinicalDomain: String,
    ) {
        dao.insert(
            build(
                eventType = "module_completed",
                scenarioId = scenarioId,
                clinicalDomain = clinicalDomain,
            )
        )
        Log.d(TAG, "[$sessionId] module_completed saved — scenario=$scenarioId")
    }

    /**
     * Distinct from [recordModuleCompleted]: emitted when the CHW finishes the
     * quiz portion of a module regardless of whether the module is fully
     * completed. Carries the score so the backend can update
     * `chw_module_completion`.
     */
    suspend fun recordQuizCompleted(
        moduleFamilyId: String,
        moduleId: String?,
        moduleVersion: Int?,
        quizScorePct: Float,
        passed: Boolean,
        behaviouralGapId: String? = null,
    ) {
        // Backend canonical event for a finished quiz attempt is
        // `module_quiz_attempted`; the outcome carries pass/fail.
        dao.insert(
            build(
                eventType = "module_quiz_attempted",
                cardType = "quiz",
                outcome = if (passed) "correct" else "wrong",
                moduleFamilyId = moduleFamilyId,
                moduleId = moduleId,
                moduleVersion = moduleVersion,
                quizScorePct = quizScorePct,
                behaviouralGapId = behaviouralGapId,
            )
        )
        Log.d(TAG, "[$sessionId] module_quiz_attempted saved — module=$moduleFamilyId score=$quizScorePct passed=$passed gap=$behaviouralGapId")
    }

    // ── Private builder ───────────────────────────────────────────────────────

    private fun build(
        eventType: String,
        scenarioId: String? = null,
        clinicalDomain: String? = null,
        cardType: String? = null,
        triggerType: String? = null,
        inferenceMode: String? = null,
        quizQuestionId: String? = null,
        selectedOption: Int? = null,
        isCorrect: Boolean? = null,
        outcome: String? = null,
        validatorStatus: String? = null,
        fallbackUsed: Boolean? = null,
        networkState: String? = null,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        cardFamilyId: String? = null,
        quizFamilyId: String? = null,
        moduleVersion: Int? = null,
        quizScorePct: Float? = null,
        behaviouralGapId: String? = null,
    ) = CoachingEventEntity(
        eventId = UUID.randomUUID().toString(),
        sdkVersion = sdkVersion,
        eventFamily = eventFamilyFor(eventType),
        sessionId = sessionId,
        chwId = chwId,
        eventType = eventType,
        scenarioId = scenarioId,
        clinicalDomain = clinicalDomain,
        cardType = cardType,
        triggerType = triggerType,
        inferenceMode = inferenceMode,
        quizQuestionId = quizQuestionId,
        selectedOption = selectedOption,
        isCorrect = isCorrect,
        outcome = outcome,
        validatorStatus = validatorStatus,
        fallbackUsed = fallbackUsed,
        networkState = networkState,
        moduleFamilyId = moduleFamilyId,
        moduleId = moduleId,
        cardFamilyId = cardFamilyId,
        quizFamilyId = quizFamilyId,
        moduleVersion = moduleVersion,
        quizScorePct = quizScorePct,
        behaviouralGapId = behaviouralGapId,
    )
}
