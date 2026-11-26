package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One sanitised SDK network call, device-local and bounded (the newest 500 rows are
 * kept by `NetworkEventDao.insertAndPurge`). [id] is monotonic and never reused, so it
 * doubles as the pull cursor. [errorType] holds a `NetworkErrorType` wire name.
 */
@Entity(tableName = "network_event")
internal data class NetworkEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long,

    @ColumnInfo(name = "method")
    val method: String,

    /** Sanitised, at most 2048 characters. */
    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "status_code")
    val statusCode: Int?,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "error_type")
    val errorType: String?,
)
