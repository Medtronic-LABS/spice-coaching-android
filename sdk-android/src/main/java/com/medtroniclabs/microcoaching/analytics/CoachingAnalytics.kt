package com.medtroniclabs.microcoaching.analytics

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * Where analytics events go. The SDK only talks to this interface; the one
 * Firebase-backed implementation ([FirebaseAnalyticsSink]) is loaded only after
 * the host app is confirmed to ship and configure Firebase.
 */
internal interface AnalyticsSink {
    fun logEvent(name: String, params: Map<String, Any>)
    fun logScreen(screenName: String)
}

/**
 * Optional product analytics (Google Analytics for Firebase) for the coaching flow.
 *
 * **Off by default.** Turned on with `MicroCoachingSDK.Builder.enableAnalytics(true)`.
 * When on, events go to the HOST app's default Firebase project — the SDK carries
 * no keys, no `google-services.json` and no Firebase dependency of its own
 * (`compileOnly`). Every failure mode degrades to a silent no-op:
 *
 *  - flag off                                   → nothing is sent
 *  - flag on, host has no firebase-analytics     → disabled, one warning logged
 *  - flag on, Firebase present but not configured→ disabled, one warning logged
 *  - any exception while logging                 → swallowed, coaching unaffected
 *
 * Privacy: callers pass only ids, counts, scores, flags and durations. Chat text,
 * feedback text, free-text requests and patient fields are never sent.
 *
 * This is separate from the SDK's own coaching-backend telemetry
 * ([com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder]), which is
 * unchanged.
 */
object CoachingAnalytics {

    private const val TAG = "CoachingAnalytics"
    private const val FIREBASE_ANALYTICS_CLASS = "com.google.firebase.analytics.FirebaseAnalytics"

    /** GA4 limits. */
    internal const val MAX_NAME_LENGTH = 40
    internal const val MAX_VALUE_LENGTH = 100

    @Volatile
    private var sink: AnalyticsSink? = null

    /** True when events are actually being sent. */
    val isActive: Boolean get() = sink != null

    // ── Session counters (rolled into coaching_session_ended) ────────────────
    @Volatile private var sessionStartedAtMs = 0L
    @Volatile private var modulesOpened = 0
    @Volatile private var quizzesCompleted = 0
    @Volatile private var chatQuestions = 0

    /**
     * Called once from `MicroCoachingSDK.Builder.build()`. Safe to call again on
     * re-init; the previous sink is dropped first.
     */
    internal fun init(context: Context, enabled: Boolean) {
        sink = null
        if (!enabled) return
        val created = try {
            // 1. Is the Firebase Analytics library in the host app at all?
            Class.forName(FIREBASE_ANALYTICS_CLASS)
            // 2. Only now touch the class that references Firebase types. It
            //    returns null when Firebase is present but not configured.
            FirebaseAnalyticsSink.createOrNull(context.applicationContext)
        } catch (t: Throwable) {
            // ClassNotFoundException, NoClassDefFoundError, IllegalStateException…
            null
        }
        sink = created
        if (created == null) {
            Log.w(
                TAG,
                "enableAnalytics=true but Firebase Analytics is not available or not " +
                    "configured in the host app — coaching analytics disabled.",
            )
        } else {
            Log.i(TAG, "Coaching analytics enabled (host app's Firebase project).")
        }
    }

    @VisibleForTesting
    internal fun setSinkForTesting(testSink: AnalyticsSink?) {
        sink = testSink
        resetSessionForTesting()
    }

    @VisibleForTesting
    internal fun resetSessionForTesting() {
        sessionStartedAtMs = 0L
        modulesOpened = 0
        quizzesCompleted = 0
        chatQuestions = 0
    }

    /** Logs one event. Null params are dropped; never throws. */
    fun event(name: String, vararg params: Pair<String, Any?>) {
        val target = sink ?: return
        try {
            when (name) {
                CoachingEvents.MODULE_OPENED -> modulesOpened++
                CoachingEvents.QUIZ_COMPLETED -> quizzesCompleted++
                CoachingEvents.CHAT_ASKED -> chatQuestions++
            }
            target.logEvent(name.take(MAX_NAME_LENGTH), sanitize(params.toMap()))
        } catch (t: Throwable) {
            Log.w(TAG, "event '$name' not sent: ${t.message}")
        }
    }

    /** Logs a `screen_view`. Never throws. */
    fun screen(screenName: String?) {
        val target = sink ?: return
        val name = screenName?.takeIf { it.isNotBlank() } ?: return
        try {
            target.logScreen(name.take(MAX_VALUE_LENGTH))
        } catch (t: Throwable) {
            Log.w(TAG, "screen '$name' not sent: ${t.message}")
        }
    }

    /** Marks the start of a coaching session (the coaching flow opened). */
    fun sessionStarted(nowMs: Long = System.currentTimeMillis()) {
        sessionStartedAtMs = nowMs
        modulesOpened = 0
        quizzesCompleted = 0
        chatQuestions = 0
    }

    /** Sends `coaching_session_ended` for the session started by [sessionStarted]. */
    fun sessionEnded(nowMs: Long = System.currentTimeMillis()) {
        val started = sessionStartedAtMs
        if (started == 0L) return
        event(
            CoachingEvents.SESSION_ENDED,
            CoachingParams.DURATION_MS to (nowMs - started).coerceAtLeast(0L),
            CoachingParams.MODULES_OPENED to modulesOpened,
            CoachingParams.QUIZZES_COMPLETED to quizzesCompleted,
            CoachingParams.CHAT_QUESTIONS to chatQuestions,
        )
        sessionStartedAtMs = 0L
    }

    /**
     * Nav route template → screen name: the part before the first `/`, so
     * `coaching_quiz_question/{questionIndex}` → `coaching_quiz_question`.
     * Route arguments (ids, dates) are never sent.
     */
    fun screenNameForRoute(route: String?): String? =
        route?.substringBefore('/')?.takeIf { it.isNotBlank() }

    /** `"online"` network state → true, anything else → false, null stays null. */
    fun isOnline(networkState: String?): Boolean? = networkState?.let { it == "online" }

    /**
     * GA-safe params: nulls dropped, booleans as "true"/"false" (same as the host
     * app's Flutter events), whole numbers as Long, decimals as Double, text capped
     * at 100 characters.
     */
    internal fun sanitize(params: Map<String, Any?>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in params) {
            if (value == null) continue
            val k = key.take(MAX_NAME_LENGTH)
            out[k] = when (value) {
                is Boolean -> if (value) "true" else "false"
                is Int -> value.toLong()
                is Long -> value
                is Short -> value.toLong()
                is Float -> value.toDouble()
                is Double -> value
                else -> value.toString().take(MAX_VALUE_LENGTH)
            }
        }
        return out
    }
}

/** Event names. One place, so a rename is a one-line change. */
object CoachingEvents {
    const val SESSION_ENDED = "coaching_session_ended"
    const val MODULE_OPENED = "coaching_module_opened"
    const val QUIZ_STARTED = "coaching_quiz_started"
    const val QUIZ_COMPLETED = "coaching_quiz_completed"
    const val CHAT_ASKED = "coaching_chat_asked"
    const val CHAT_ANSWERED = "coaching_chat_answered"
    const val CHAT_FEEDBACK = "coaching_chat_feedback"
    const val VIDEO_STARTED = "coaching_video_started"
    const val VIDEO_ENDED = "coaching_video_ended"
    const val DOCUMENT_VIEWED = "coaching_document_viewed"
    const val MODULE_REQUESTED = "coaching_module_requested"
    const val MODEL_DOWNLOAD = "coaching_model_download"
}

/** Property names — same meaning as the host app's GA properties. */
object CoachingParams {
    const val MODULE_ID = "module_id"
    const val MODULE_FAMILY_ID = "module_family_id"
    const val MODULE_VERSION = "module_version"
    const val CLINICAL_DOMAIN = "clinical_domain"
    const val SOURCE = "source"
    const val RESULT = "result"
    const val FAILURE_REASON = "failure_reason"
    const val SCORE_PCT = "score_pct"
    const val CORRECT = "correct"
    const val QUESTIONS = "questions"
    const val IS_ONLINE = "is_online"
    const val INFERENCE_MODE = "inference_mode"
    const val VALIDATOR_STATUS = "validator_status"
    const val FALLBACK_USED = "fallback_used"
    const val VOTE = "vote"
    const val HAS_COMMENT = "has_comment"
    const val VIDEO_ID = "video_id"
    const val PERCENT_WATCHED = "percent_watched"
    const val COMPLETED = "completed"
    const val WATCH_MS = "watch_ms"
    const val PAUSE_COUNT = "pause_count"
    const val REWATCH_COUNT = "rewatch_count"
    const val DOCUMENT_ID = "document_id"
    const val TIME_SPENT_MS = "time_spent_ms"
    const val DOWNLOADED = "downloaded"
    const val REQUEST_TYPE = "request_type"
    const val HAS_REASON = "has_reason"
    const val MODEL_ID = "model_id"
    const val DURATION_MS = "duration_ms"
    const val MODULES_OPENED = "modules_opened"
    const val QUIZZES_COMPLETED = "quizzes_completed"
    const val CHAT_QUESTIONS = "chat_questions"
}

/** Fixed values. */
object CoachingValues {
    const val SUCCESS = "success"
    const val FAILED = "failed"
    const val REFUSED = "refused"
    const val PASSED = "passed"
    const val TYPED = "typed"
    const val SUGGESTED = "suggested"
    const val LIBRARY = "library"
    const val REFRESHER = "refresher"
    const val COURSE = "course"
    const val UP = "up"
    const val DOWN = "down"
    const val EXISTING_MODULE = "existing_module"
    const val NEW_TOPIC = "new_topic"
    const val STORAGE_UNAVAILABLE = "storage_unavailable"
    const val DOWNLOAD_FAILED = "download_failed"
    const val SCREEN_VIDEO_PLAYER = "coaching_video_player"
    const val SCREEN_DOCUMENT_PREVIEW = "coaching_document_preview"
}
