package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only, device-local log of every learning item the CHW **finished** — the
 * SK learning-streak source (product rule: a day counts when the SK finishes at
 * least one learning item; finishing, not passing).
 *
 * One row per finish, never updated or deleted: re-doing a refresher adds a new day
 * instead of overwriting an old one (unlike [ChwModuleCompletionEntity], which keeps
 * only the latest pass). Deliberately **not** telemetry — nothing here is synced, so
 * it cannot double-count gap folds or learning points on the backend.
 *
 * Written at the three finish points:
 *  - [KIND_VIDEO] — a training video played to the end (`VideoProgressReporter`),
 *  - [KIND_QUIZ]  — the last question of a module or refresher quiz answered,
 *    pass or fail (`MicroCoachingSDK.onModuleQuizCompleted`),
 *  - [KIND_CARDS] — the last card of a quiz-less module or cards-only refresher
 *    reached (`MicroCoachingSDK.onModuleCardsCompleted`).
 *
 * MIGRATION_35_36 back-fills past video completions from `coaching_event` so
 * existing streaks survive the upgrade.
 */
@Entity(
    tableName = "learning_completion",
    indices = [Index(value = ["chw_id", "completed_at"])],
)
data class LearningCompletionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "chw_id")
    val chwId: String,

    /** [KIND_VIDEO], [KIND_QUIZ] or [KIND_CARDS]. */
    @ColumnInfo(name = "kind")
    val kind: String,

    /** Video id or module family id; null for back-filled rows. */
    @ColumnInfo(name = "item_id")
    val itemId: String?,

    /** Epoch millis (device clock) — the streak maps it to the local calendar day. */
    @ColumnInfo(name = "completed_at")
    val completedAt: Long,
) {
    companion object {
        const val KIND_VIDEO = "video"
        const val KIND_QUIZ = "quiz"
        const val KIND_CARDS = "cards"
    }
}
