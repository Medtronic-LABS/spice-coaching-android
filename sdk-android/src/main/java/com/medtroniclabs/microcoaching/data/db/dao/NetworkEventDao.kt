package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.medtroniclabs.microcoaching.data.db.entity.NetworkEventEntity

/** Append, bounded-purge and cursor-read access to the [NetworkEventEntity] log. */
@Dao
interface NetworkEventDao {

    @Insert
    suspend fun insertAll(rows: List<NetworkEventEntity>)

    /** Keeps the newest [keep] rows; ids are monotonic, so this is a primary-key range. */
    @Query("DELETE FROM network_event WHERE id <= (SELECT MAX(id) FROM network_event) - :keep")
    suspend fun purge(keep: Int)

    @Transaction
    suspend fun insertAndPurge(rows: List<NetworkEventEntity>, keep: Int = DEFAULT_KEEP) {
        insertAll(rows)
        purge(keep)
    }

    @Query("SELECT * FROM network_event WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun after(afterId: Long, limit: Int): List<NetworkEventEntity>

    @Query("SELECT COALESCE(MAX(id), 0) FROM network_event")
    suspend fun maxId(): Long

    @Query("DELETE FROM network_event")
    suspend fun deleteAll()

    companion object {
        const val DEFAULT_KEEP = 500
    }
}
