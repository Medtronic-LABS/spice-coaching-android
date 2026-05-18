package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity

@Dao
interface CoachingEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CoachingEventEntity)

    /** All events not yet synced (sync_status = 'pending'). */
    @Query("SELECT * FROM coaching_event WHERE sync_status = 'pending' ORDER BY timestamp_local ASC")
    suspend fun getPending(): List<CoachingEventEntity>

    /** Mark a batch of events as synced by their UUID event_id. */
    @Query("UPDATE coaching_event SET sync_status = 'synced', synced_at = :syncedAt WHERE event_id IN (:eventIds)")
    suspend fun markSynced(eventIds: List<String>, syncedAt: Long = System.currentTimeMillis())

    /** Mark a batch of events as permanently failed after max retry exhaustion. */
    @Query("UPDATE coaching_event SET sync_status = 'failed' WHERE event_id IN (:eventIds)")
    suspend fun markFailed(eventIds: List<String>)

    /** Increment retry count for events that failed a single sync attempt. */
    @Query("UPDATE coaching_event SET retry_count = retry_count + 1 WHERE event_id IN (:eventIds)")
    suspend fun incrementRetryCount(eventIds: List<String>)

    /** All events for a session, ordered chronologically. */
    @Query("SELECT * FROM coaching_event WHERE session_id = :sessionId ORDER BY timestamp_local ASC")
    suspend fun getBySession(sessionId: String): List<CoachingEventEntity>

    /** All events, most recent first. */
    @Query("SELECT * FROM coaching_event ORDER BY timestamp_local DESC")
    suspend fun getAll(): List<CoachingEventEntity>

    /** Delete all events that have been successfully synced (30-day retention cleanup). */
    @Query("DELETE FROM coaching_event WHERE sync_status = 'synced'")
    suspend fun deleteSynced()

    @Query("DELETE FROM coaching_event")
    suspend fun deleteAll()
}
