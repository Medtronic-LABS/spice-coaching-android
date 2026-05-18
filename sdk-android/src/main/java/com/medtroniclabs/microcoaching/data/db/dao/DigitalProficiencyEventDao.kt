package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity

@Dao
interface DigitalProficiencyEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: DigitalProficiencyEventEntity)

    @Query("SELECT * FROM digital_proficiency_event WHERE sync_status = 'pending' ORDER BY timestamp_local ASC")
    suspend fun getPending(): List<DigitalProficiencyEventEntity>

    @Query("UPDATE digital_proficiency_event SET sync_status = 'synced', synced_at = :syncedAt WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, syncedAt: Long = System.currentTimeMillis())

    @Query("UPDATE digital_proficiency_event SET sync_status = 'failed' WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("UPDATE digital_proficiency_event SET retry_count = retry_count + 1 WHERE id IN (:ids)")
    suspend fun incrementRetryCount(ids: List<String>)

    @Query("SELECT * FROM digital_proficiency_event WHERE chw_id = :chwId ORDER BY timestamp_local DESC")
    suspend fun getByChw(chwId: String): List<DigitalProficiencyEventEntity>

    @Query("DELETE FROM digital_proficiency_event WHERE sync_status = 'synced'")
    suspend fun deleteSynced()

    @Query("DELETE FROM digital_proficiency_event")
    suspend fun deleteAll()
}
