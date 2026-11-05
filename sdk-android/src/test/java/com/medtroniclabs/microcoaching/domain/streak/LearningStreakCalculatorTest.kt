package com.medtroniclabs.microcoaching.domain.streak

import com.medtroniclabs.microcoaching.domain.streak.StreakDayStatus.DONE
import com.medtroniclabs.microcoaching.domain.streak.StreakDayStatus.MISSED
import com.medtroniclabs.microcoaching.domain.streak.StreakDayStatus.PENDING
import com.medtroniclabs.microcoaching.domain.streak.StreakDayStatus.REST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pins the SK learning-streak rules. Calendar used throughout (2026):
 * Thu 29 Oct · Fri 30 (rest) · Sat 31 (rest) · Sun 1 Nov · Mon 2 · Tue 3 · Wed 4 · Thu 5 · Fri 6 (rest).
 */
class LearningStreakCalculatorTest {

    private fun d(month: Int, day: Int) = LocalDate.of(2026, month, day)
    private fun compute(today: LocalDate, vararg active: LocalDate) =
        LearningStreakCalculator.compute(active.toSet(), today)

    @Test
    fun `new learner has a zero streak and today still to do`() {
        val s = compute(today = d(11, 5))
        assertEquals(0, s.currentStreak)
        assertTrue(s.isNewLearner)
        assertTrue(s.isTodayPending)
        assertEquals(7, s.days.size)
        assertEquals(d(11, 5), s.days.last().date)
        assertTrue(s.days.last().isToday)
    }

    @Test
    fun `consecutive learning days including today all count`() {
        val s = compute(d(11, 5), d(11, 1), d(11, 2), d(11, 3), d(11, 4), d(11, 5))
        assertEquals(5, s.currentStreak)
        assertEquals(DONE, s.days.last().status)
        assertFalse(s.isNewLearner)
    }

    @Test
    fun `unfinished today shows yesterday's streak instead of breaking it`() {
        val s = compute(d(11, 5), d(11, 1), d(11, 2), d(11, 3), d(11, 4))
        assertEquals(4, s.currentStreak)
        assertEquals(PENDING, s.days.last().status)
        assertTrue(s.isTodayPending)
    }

    @Test
    fun `friday and saturday hold the streak`() {
        // Thu 29 → (Fri, Sat rest) → Sun 1 → Mon 2 = 3
        val s = compute(d(11, 2), d(10, 29), d(11, 1), d(11, 2))
        assertEquals(3, s.currentStreak)
        assertEquals(REST, s.days.single { it.date == d(10, 30) }.status)
        assertEquals(REST, s.days.single { it.date == d(10, 31) }.status)
    }

    @Test
    fun `learning on a rest day does not add to the streak`() {
        val s = compute(d(11, 1), d(10, 30), d(10, 31), d(11, 1))
        assertEquals(1, s.currentStreak)
        assertEquals(REST, s.days.single { it.date == d(10, 30) }.status)
    }

    @Test
    fun `a missed learning day breaks the streak`() {
        val s = compute(d(11, 4), d(11, 2), d(11, 4))
        assertEquals(1, s.currentStreak)
        assertEquals(MISSED, s.days.single { it.date == d(11, 3) }.status)
        assertEquals(d(11, 3), s.lastMissedDay)
    }

    @Test
    fun `today being a rest day is neither pending nor counted`() {
        val s = compute(d(11, 6), d(11, 4), d(11, 5))
        assertEquals(2, s.currentStreak)
        assertEquals(REST, s.days.last().status)
        assertFalse(s.isTodayPending)
    }

    @Test
    fun `window tallies skip rest days and an unfinished today`() {
        // Window Fri 30 Oct … Thu 5 Nov. Learning days decided: Sun, Mon, Tue, Wed (Thu pending).
        val s = compute(d(11, 5), d(11, 1), d(11, 2), d(11, 4))
        assertEquals(3, s.learnedDays)
        assertEquals(4, s.learningDays)
        assertEquals(d(11, 3), s.lastMissedDay)
        assertEquals(1, s.currentStreak) // Wed only; Tue was missed
    }

    @Test
    fun `no misses in the window means no missed day`() {
        val s = compute(d(11, 5), d(10, 29), d(11, 1), d(11, 2), d(11, 3), d(11, 4), d(11, 5))
        assertNull(s.lastMissedDay)
        assertEquals(6, s.currentStreak)
    }

    @Test
    fun `streak can run longer than the 7-day window`() {
        val start = d(10, 1)
        val active = generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(d(11, 5)) }
            .filterNot { LearningStreakCalculator.isRestDay(it) }
            .toSet()
        val s = LearningStreakCalculator.compute(active, d(11, 5))
        assertEquals(active.size, s.currentStreak)
        assertTrue(s.currentStreak > 7)
    }
}
