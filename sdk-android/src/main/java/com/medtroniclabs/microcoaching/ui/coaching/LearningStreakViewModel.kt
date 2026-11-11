package com.medtroniclabs.microcoaching.ui.coaching

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.domain.streak.LearningStreak
import com.medtroniclabs.microcoaching.domain.streak.LearningStreakCalculator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Drives the SK learning-streak card. Reactively reads training-video completions
 * from `coaching_event`, so finishing a video updates the card without a refresh,
 * and re-evaluates at local midnight so a screen left open rolls over to the new day.
 */
class LearningStreakViewModel(
    chwId: String,
    dao: CoachingEventDao,
) : ViewModel() {

    /** null until the first read lands — the card renders nothing rather than a flash of 0. */
    val state: StateFlow<LearningStreak?> =
        combine(dao.observeVideoCompletionTimes(chwId), localToday()) { times, today ->
            val activeDays = LearningStreakCalculator.activeDays(times, ZoneId.systemDefault())
            LearningStreakCalculator.compute(activeDays, today)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun localToday(): Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            val now = ZonedDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            delay(java.time.Duration.between(now, nextMidnight).toMillis() + 1_000)
        }
    }.distinctUntilChanged()

    companion object {
        fun factory(chwId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LearningStreakViewModel(
                    chwId = chwId,
                    dao = MicroCoachingSDK.getInstance().database.coachingEventDao(),
                ) as T
        }
    }
}
