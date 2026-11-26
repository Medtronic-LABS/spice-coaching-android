package com.medtroniclabs.microcoaching.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.medtroniclabs.microcoaching.data.db.migration.MIGRATION_36_37
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration36To37Test {

    private fun tableSql(db: SupportSQLiteDatabase): String =
        db.query("SELECT sql FROM sqlite_master WHERE name = 'network_event'").use {
            it.moveToFirst()
            it.getString(0)
        }

    private fun blankDb(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun version_is37() {
        assertEquals(37, MICRO_COACHING_ROOM_VERSION)
    }

    @Test
    fun migrationDdl_matchesRoomGeneratedDdl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, MicroCoachingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val roomSql = tableSql(room.openHelper.writableDatabase)
        room.close()

        val blank = blankDb()
        MIGRATION_36_37.migrate(blank)
        val migratedSql = tableSql(blank)
        blank.close()

        assertEquals(roomSql, migratedSql)
    }

    @Test
    fun migration_isIdempotent() {
        val blank = blankDb()
        MIGRATION_36_37.migrate(blank)
        MIGRATION_36_37.migrate(blank)
        blank.close()
    }
}
