package com.medtroniclabs.microcoaching.data.repository

import com.medtroniclabs.microcoaching.data.db.dao.ChatMessageDao
import com.medtroniclabs.microcoaching.data.db.entity.ChatMessageEntity
import com.medtroniclabs.microcoaching.ui.chat.ChatMessage
import com.medtroniclabs.microcoaching.ui.chat.ChatRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for chat message persistence.
 *
 * Not exposed directly to SPICE — SPICE accesses chat data via [CoachingDataRepository].
 */
open class ChatRepositoryImpl(private val dao: ChatMessageDao) {

    suspend fun saveMessage(message: ChatMessage): Long {
        return dao.insert(message.toEntity())
    }

    suspend fun getHistory(sessionId: String): List<ChatMessage> =
        dao.getBySession(sessionId).map { it.toModel() }

    fun observeSession(sessionId: String): Flow<List<ChatMessage>> =
        dao.observeSession(sessionId).map { list -> list.map { it.toModel() } }

    suspend fun getAllSessionIds(): List<String> = dao.getAllSessionIds()

    suspend fun getAllMessages(): List<ChatMessage> = dao.getAll().map { it.toModel() }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private fun ChatMessage.toEntity() = ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        role = role,
        text = text,
        timestampMs = timestampMs,
        traceId = traceId,
    )

    private fun ChatMessageEntity.toModel() = ChatMessage(
        id = id,
        sessionId = sessionId,
        role = role,
        text = text,
        timestampMs = timestampMs,
        traceId = traceId,
    )
}
