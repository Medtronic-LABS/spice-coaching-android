package com.medtroniclabs.microcoaching.analytics

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachingAnalyticsTest {

    private class RecordingSink : AnalyticsSink {
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        val screens = mutableListOf<String>()
        override fun logEvent(name: String, params: Map<String, Any>) { events += name to params }
        override fun logScreen(screenName: String) { screens += screenName }
    }

    private class ThrowingSink : AnalyticsSink {
        override fun logEvent(name: String, params: Map<String, Any>) = throw IllegalStateException("boom")
        override fun logScreen(screenName: String) = throw IllegalStateException("boom")
    }

    @After
    fun tearDown() = CoachingAnalytics.setSinkForTesting(null)

    // ── Safeguards ───────────────────────────────────────────────────────────

    @Test
    fun `enabled but Firebase not on the classpath - disabled, no crash`() {
        // firebase-analytics is compileOnly, so it is absent from the unit-test
        // runtime — exactly a host app that never added Firebase.
        CoachingAnalytics.init(ContextWrapper(null), enabled = true)
        assertFalse(CoachingAnalytics.isActive)
        CoachingAnalytics.event(CoachingEvents.QUIZ_STARTED, CoachingParams.MODULE_ID to "m1")
        CoachingAnalytics.screen("coaching_quiz_result")
        CoachingAnalytics.sessionStarted(1_000L)
        CoachingAnalytics.sessionEnded(2_000L)
    }

    @Test
    fun `disabled - nothing is sent`() {
        CoachingAnalytics.init(ContextWrapper(null), enabled = false)
        assertFalse(CoachingAnalytics.isActive)
    }

    @Test
    fun `a sink that throws never breaks the caller`() {
        CoachingAnalytics.setSinkForTesting(ThrowingSink())
        CoachingAnalytics.event(CoachingEvents.QUIZ_STARTED)
        CoachingAnalytics.screen("coaching_lesson_player")
    }

    // ── Behaviour ────────────────────────────────────────────────────────────

    @Test
    fun `params are GA-safe - nulls dropped, booleans as text, numbers widened, text capped`() {
        val sink = RecordingSink()
        CoachingAnalytics.setSinkForTesting(sink)
        CoachingAnalytics.event(
            CoachingEvents.QUIZ_COMPLETED,
            CoachingParams.MODULE_ID to "m1",
            CoachingParams.SCORE_PCT to 80,
            CoachingParams.COMPLETED to true,
            CoachingParams.FAILURE_REASON to null,
            CoachingParams.PERCENT_WATCHED to 12.5f,
            "long_text" to "x".repeat(150),
        )
        val (name, params) = sink.events.single()
        assertEquals(CoachingEvents.QUIZ_COMPLETED, name)
        assertEquals("m1", params[CoachingParams.MODULE_ID])
        assertEquals(80L, params[CoachingParams.SCORE_PCT])
        assertEquals("true", params[CoachingParams.COMPLETED])
        assertFalse(params.containsKey(CoachingParams.FAILURE_REASON))
        assertEquals(12.5, params[CoachingParams.PERCENT_WATCHED])
        assertEquals(100, (params["long_text"] as String).length)
    }

    @Test
    fun `route templates become screen names without arguments`() {
        assertEquals("coaching_quiz_question", CoachingAnalytics.screenNameForRoute("coaching_quiz_question/{questionIndex}"))
        assertEquals("coaching_po_sk_detail", CoachingAnalytics.screenNameForRoute("coaching_po_sk_detail/{skId}/{from}/{to}"))
        assertEquals("coaching_module_ready", CoachingAnalytics.screenNameForRoute("coaching_module_ready"))
        assertNull(CoachingAnalytics.screenNameForRoute(null))
    }

    @Test
    fun `session summary counts modules, quizzes and chat questions`() {
        val sink = RecordingSink()
        CoachingAnalytics.setSinkForTesting(sink)
        CoachingAnalytics.sessionStarted(10_000L)
        CoachingAnalytics.event(CoachingEvents.MODULE_OPENED)
        CoachingAnalytics.event(CoachingEvents.MODULE_OPENED)
        CoachingAnalytics.event(CoachingEvents.QUIZ_COMPLETED)
        CoachingAnalytics.event(CoachingEvents.CHAT_ASKED)
        CoachingAnalytics.sessionEnded(70_000L)
        CoachingAnalytics.sessionEnded(80_000L) // already ended — ignored

        val ended = sink.events.filter { it.first == CoachingEvents.SESSION_ENDED }
        assertEquals(1, ended.size)
        val p = ended.single().second
        assertEquals(60_000L, p[CoachingParams.DURATION_MS])
        assertEquals(2L, p[CoachingParams.MODULES_OPENED])
        assertEquals(1L, p[CoachingParams.QUIZZES_COMPLETED])
        assertEquals(1L, p[CoachingParams.CHAT_QUESTIONS])
    }

    @Test
    fun `online flag maps from the SDK network state`() {
        assertEquals(true, CoachingAnalytics.isOnline("online"))
        assertEquals(false, CoachingAnalytics.isOnline("offline"))
        assertNull(CoachingAnalytics.isOnline(null))
        assertTrue(CoachingEvents.QUIZ_COMPLETED.length <= CoachingAnalytics.MAX_NAME_LENGTH)
    }
}
