package com.medtroniclabs.microcoaching.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens a real version-36 database file through [MicroCoachingDatabase.getInstance]
 * and checks that the registered migrations carry it to 37 without the
 * destructive fallback: a dropped `addMigrations(MIGRATION_36_37)` would wipe
 * every table, including unsynced outbound queues, and only this test would notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicroCoachingDatabaseMigrationRegistrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = clean()

    @After
    fun tearDown() = clean()

    private fun clean() {
        resetSingleton()
        context.deleteDatabase(DB_NAME)
    }

    private fun resetSingleton() {
        MicroCoachingDatabase::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    /** Builds the current schema, seeds rows, then rewinds the file to version 36. */
    private fun createVersion36File() {
        val room = Room.databaseBuilder(context, MicroCoachingDatabase::class.java, DB_NAME).build()
        room.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO badge (badge_id, chw_id, sequence, rank) VALUES ('b1', 'chw-1', 1, 1)")
            execSQL("INSERT INTO badge (badge_id, chw_id, sequence, rank) VALUES ('b2', 'chw-1', 2, 2)")
        }
        room.close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DB_NAME).path, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.execSQL("DROP TABLE network_event")
            db.execSQL("PRAGMA user_version = 36")
        }
    }

    private fun count(db: MicroCoachingDatabase, sql: String): Int =
        db.openHelper.writableDatabase.query(sql).use {
            it.moveToFirst()
            it.getInt(0)
        }

    @Test
    fun versionThirtySixFile_isMigratedNotWiped() {
        createVersion36File()

        val db = MicroCoachingDatabase.getInstance(context)
        val helper = db.openHelper.writableDatabase

        assertEquals("rows in other tables survive", 2, count(db, "SELECT COUNT(*) FROM badge"))
        assertEquals(
            "network_event exists",
            1,
            count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'network_event'"),
        )
        assertEquals(37, helper.version)
        assertEquals(MICRO_COACHING_ROOM_VERSION, helper.version)
        db.close()
    }

    private companion object {
        const val DB_NAME = "microcoaching.db"
    }
}
