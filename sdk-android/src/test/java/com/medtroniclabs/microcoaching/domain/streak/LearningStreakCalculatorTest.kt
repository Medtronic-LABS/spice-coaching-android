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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

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
    fun `learning on a rest day is a bonus day that adds to the streak`() {
        // Fri 30 + Sat 31 (both learned) + Sun 1 = 3
        val s = compute(d(11, 1), d(10, 30), d(10, 31), d(11, 1))
        assertEquals(3, s.currentStreak)
        assertEquals(DONE, s.days.single { it.date == d(10, 30) }.status)
        assertEquals(DONE, s.days.single { it.date == d(10, 31) }.status)
    }

    @Test
    fun `first ever completion on a rest-day today counts immediately`() {
        // Reported case: nothing before, one training on Friday 6 Nov → 1-day streak, not 0.
        val s = compute(d(11, 6), d(11, 6))
        assertEquals(1, s.currentStreak)
        assertEquals(DONE, s.days.last().status)
        assertFalse(s.isNewLearner)
    }

    @Test
    fun `a skipped rest day between learned days still holds`() {
        // Thu 29 → Fri 30 learned (bonus) → Sat 31 skipped (hold) → Sun 1 = 3
        val s = compute(d(11, 1), d(10, 29), d(10, 30), d(11, 1))
        assertEquals(3, s.currentStreak)
        assertEquals(DONE, s.days.single { it.date == d(10, 30) }.status)
        assertEquals(REST, s.days.single { it.date == d(10, 31) }.status)
    }

    @Test
    fun `a bonus rest day does not rescue a missed learning day before it`() {
        // Wed 4 learned, Thu 5 missed, Fri 6 learned (today) → only Fri counts = 1
        val s = compute(d(11, 6), d(11, 4), d(11, 6))
        assertEquals(1, s.currentStreak)
        assertEquals(MISSED, s.days.single { it.date == d(11, 5) }.status)
    }

    @Test
    fun `a missed learning day breaks the streak`() {
        val s = compute(d(11, 4), d(11, 2), d(11, 4))
        assertEquals(1, s.currentStreak)
        assertEquals(MISSED, s.days.single { it.date == d(11, 3) }.status)
        assertEquals(d(11, 3), s.lastMissedDay)
    }

    @Test
    fun `an unlearned rest-day today is neither pending nor counted`() {
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

    // ── activeDays: epoch timestamp → local calendar day ──────────────────────

    private val dhaka = ZoneId.of("Asia/Dhaka") // UTC+6, no DST
    private fun at(zone: ZoneId, month: Int, day: Int, h: Int, m: Int, s: Int = 0) =
        ZonedDateTime.of(2026, month, day, h, m, s, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `completions either side of local midnight land on different days`() {
        val days = LearningStreakCalculator.activeDays(
            listOf(at(dhaka, 11, 4, 23, 59, 59), at(dhaka, 11, 5, 0, 0, 1)),
            dhaka,
        )
        assertEquals(setOf(d(11, 4), d(11, 5)), days)
    }

    @Test
    fun `day is decided by the device zone, not UTC`() {
        // 20:00 UTC on 4 Nov is 02:00 on 5 Nov in Dhaka.
        val t = at(ZoneOffset.UTC, 11, 4, 20, 0)
        assertEquals(setOf(d(11, 5)), LearningStreakCalculator.activeDays(listOf(t), dhaka))
        assertEquals(setOf(d(11, 4)), LearningStreakCalculator.activeDays(listOf(t), ZoneOffset.UTC))
    }

    @Test
    fun `several completions on one day collapse to a single active day`() {
        val days = LearningStreakCalculator.activeDays(
            listOf(at(dhaka, 11, 5, 9, 0), at(dhaka, 11, 5, 13, 30), at(dhaka, 11, 5, 21, 15)),
            dhaka,
        )
        assertEquals(setOf(d(11, 5)), days)
    }

    @Test
    fun `no completions means no active days`() {
        assertTrue(LearningStreakCalculator.activeDays(emptyList(), dhaka).isEmpty())
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
