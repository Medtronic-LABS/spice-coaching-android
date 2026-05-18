package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted chat message in the SDK's Room database.
 *
 * Privacy note: message text is stored locally only.
 * It is never included in OTel span attributes.
 * SPICE can export this data via [CoachingDataRepository.exportAllData].
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["session_id"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Session this message belongs to. */
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /** "user" or "assistant". */
    @ColumnInfo(name = "role")
    val role: String,

    /** The message text (stored locally; not transmitted in telemetry). */
    @ColumnInfo(name = "text")
    val text: String,

    /** Unix epoch millis. */
    @ColumnInfo(name = "timestamp_ms")
    val timestampMs: Long = System.currentTimeMillis(),

    /**
     * OTel trace ID of the inference span associated with this message.
     * Null for user messages. Allows correlating UI events to OTel spans.
     */
    @ColumnInfo(name = "trace_id")
    val traceId: String? = null,

    /** Optional patient ID context (from SPICE) — stored as hashed value only. */
    @ColumnInfo(name = "patient_id_hash")
    val patientIdHash: String? = null,
)
