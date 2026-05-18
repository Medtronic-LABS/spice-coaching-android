package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MorningCardCacheDao {

    /** Live-observable list of all cached morning cards in backend priority order. */
    @Query("SELECT * FROM morning_card_cache ORDER BY rank ASC")
    fun getAllOrdered(): Flow<List<MorningCardCacheEntity>>

    /** One-shot read — used by [LearnViewModel] and [QuickLearnViewModel] to enrich modules. */
    @Query("SELECT * FROM morning_card_cache ORDER BY rank ASC")
    suspend fun getAllOrderedOnce(): List<MorningCardCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MorningCardCacheEntity>)

    @Query("DELETE FROM morning_card_cache")
    suspend fun clearAll()

    /** Null when the cache has never been populated. */
    @Query("SELECT MAX(fetched_at) FROM morning_card_cache")
    suspend fun latestFetchedAt(): Long?

    @Query("SELECT COUNT(*) FROM morning_card_cache")
    suspend fun count(): Int
}
