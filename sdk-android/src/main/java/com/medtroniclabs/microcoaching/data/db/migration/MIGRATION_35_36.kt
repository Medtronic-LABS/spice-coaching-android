package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v35 → v36: add the append-only `learning_completion` log that the SK learning
 * streak now reads (a day counts when any learning item is finished — a training
 * video, a module/refresher quiz, or a cards-only lesson).
 *
 * The streak used to read training-video completions straight from `coaching_event`,
 * so those rows are copied in as `kind = 'video'` (item_id unknown → NULL). Without
 * this back-fill every SK's streak would reset to zero on upgrade. Past quiz/card
 * finishes are not recoverable reliably (per-answer events, overwritten completion
 * rows), so they start counting from this version on.
 *
 * The CREATE statements must match Room's generated schema exactly (exportSchema is
 * off, so there is no JSON to diff against) — they mirror
 * `MicroCoachingDatabase_Impl.createAllTables` for [LearningCompletionEntity].
 */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `learning_completion` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`chw_id` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`item_id` TEXT, " +
                "`completed_at` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_learning_completion_chw_id_completed_at` " +
                "ON `learning_completion` (`chw_id`, `completed_at`)",
        )
        db.execSQL(
            "INSERT INTO `learning_completion` (`chw_id`, `kind`, `item_id`, `completed_at`) " +
                "SELECT `chw_id`, 'video', NULL, `timestamp_local` FROM `coaching_event` " +
                "WHERE `event_type` = 'video_progress_updated' AND `outcome` = 'completed'",
        )
    }
}
