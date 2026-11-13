package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.LearningCompletionEntity
import kotlinx.coroutines.flow.Flow

/** Read/append access to the device-local [LearningCompletionEntity] log. */
@Dao
interface LearningCompletionDao {

    @Insert
    suspend fun insert(completion: LearningCompletionEntity)

    /**
     * Every finish timestamp for [chwId] — the learning-streak source. Full history is
     * safe and cheap: the table is append-only, local-only, and holds at most a few
     * rows per learning day.
     */
    @Query("SELECT completed_at FROM learning_completion WHERE chw_id = :chwId")
    fun observeCompletionTimes(chwId: String): Flow<List<Long>>
}
