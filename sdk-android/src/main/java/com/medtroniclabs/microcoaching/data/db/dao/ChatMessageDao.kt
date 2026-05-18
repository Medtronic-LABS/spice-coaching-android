package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp_ms ASC")
    suspend fun getBySession(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp_ms ASC")
    fun observeSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT session_id FROM chat_messages GROUP BY session_id ORDER BY MIN(timestamp_ms) DESC")
    suspend fun getAllSessionIds(): List<String>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp_ms DESC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}
