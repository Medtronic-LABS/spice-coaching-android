package com.medtroniclabs.microcoaching.domain.streak

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How one day renders in the streak strip. */
enum class StreakDayStatus {
    /** Learned that day — any day, including a Friday/Saturday bonus day. */
    DONE,

    /** A past learning day with no learning — breaks the streak. */
    MISSED,

    /** Friday / Saturday with no learning: the streak holds (a rest day that was learned on is DONE). */
    REST,

    /** Today, a learning day, nothing completed yet — "still to do", not a miss. */
    PENDING,
}

data class StreakDay(
    val date: LocalDate,
    val status: StreakDayStatus,
    val isToday: Boolean,
)

data class LearningStreak(
    /** Consecutive days with a completion; rest days count when learned on, are skipped when not. */
    val currentStreak: Int,
    /** The window, oldest first, ending today. */
    val days: List<StreakDay>,
    /** DONE days in the window. */
    val learnedDays: Int,
    /** Days in the window that are already decided: DONE + MISSED (excludes unlearned rest days and a PENDING today). */
    val learningDays: Int,
    /** Most recent MISSED day in the window, for the "Missed …" nudge. */
    val lastMissedDay: LocalDate?,
    /** True when the CHW has never completed a training video. */
    val isNewLearner: Boolean,
) {
    val isTodayPending: Boolean get() = days.lastOrNull()?.status == StreakDayStatus.PENDING
}

/**
 * SK learning-streak rules (product decisions, 2026-11-05):
 *
 *  - A day counts when at least one training video was watched to the end.
 *  - Days follow the phone's local calendar.
 *  - Friday and Saturday are rest days ("bonus days", product 2026-11-13): learning on
 *    one adds +1 and shows as DONE; skipping one never breaks the streak (REST).
 *  - If today has no completion yet, today is PENDING and the streak shown is
 *    the one as of yesterday — it only breaks once the day is over.
 *
 * Pure so it unit-tests without Room or a clock.
 */
object LearningStreakCalculator {

    val REST_DAYS: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)

    const val WINDOW_DAYS = 7

    fun isRestDay(date: LocalDate): Boolean = date.dayOfWeek in REST_DAYS

    /**
     * Collapses completion timestamps to the calendar days they fall on in [zone].
     * `timestamp_local` is a plain epoch value (System.currentTimeMillis()), so the
     * device zone decides which day a completion near midnight belongs to.
     */
    fun activeDays(completionTimesMs: Collection<Long>, zone: ZoneId): Set<LocalDate> =
        completionTimesMs.mapTo(HashSet()) { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    fun compute(
        activeDays: Set<LocalDate>,
        today: LocalDate,
        windowDays: Int = WINDOW_DAYS,
    ): LearningStreak {
        val days = (windowDays - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            val isToday = offset == 0
            val status = when {
                date in activeDays -> StreakDayStatus.DONE // rest days included: bonus +1
                isRestDay(date) -> StreakDayStatus.REST
                isToday -> StreakDayStatus.PENDING
                else -> StreakDayStatus.MISSED
            }
            StreakDay(date, status, isToday)
        }

        return LearningStreak(
            currentStreak = currentStreak(activeDays, today),
            days = days,
            learnedDays = days.count { it.status == StreakDayStatus.DONE },
            learningDays = days.count {
                it.status == StreakDayStatus.DONE || it.status == StreakDayStatus.MISSED
            },
            lastMissedDay = days.lastOrNull { it.status == StreakDayStatus.MISSED }?.date,
            isNewLearner = activeDays.isEmpty(),
        )
    }

    private fun currentStreak(activeDays: Set<LocalDate>, today: LocalDate): Int {
        val earliest = activeDays.minOrNull() ?: return 0
        var streak = 0
        var date = today
        // Today only adds once it's done (rest day or not); an unfinished today
        // neither adds nor breaks.
        if (today in activeDays) streak++
        date = date.minusDays(1)
        while (!date.isBefore(earliest)) {
            when {
                date in activeDays -> streak++ // learning or bonus rest day
                isRestDay(date) -> Unit // skipped rest day: hold
                else -> break // missed learning day
            }
            date = date.minusDays(1)
        }
        return streak
    }
}
