package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v36 → v37: add the bounded `network_event` log of sanitised SDK network calls.
 * No secondary index (every read and purge is a primary-key range) and no CHECK
 * constraints (Room cannot express them, so fresh and upgraded installs would differ).
 *
 * The CREATE statement must match Room's generated schema exactly (exportSchema is
 * off) — it mirrors `MicroCoachingDatabase_Impl.createAllTables` for [NetworkEventEntity].
 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `network_event` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`started_at_ms` INTEGER NOT NULL, " +
                "`method` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`status_code` INTEGER, " +
                "`duration_ms` INTEGER NOT NULL, " +
                "`error_type` TEXT)",
        )
    }
}
