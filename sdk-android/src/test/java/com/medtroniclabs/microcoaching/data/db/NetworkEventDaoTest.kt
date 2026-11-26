package com.medtroniclabs.microcoaching.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtroniclabs.microcoaching.data.db.dao.NetworkEventDao
import com.medtroniclabs.microcoaching.data.db.entity.NetworkEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkEventDaoTest {

    private lateinit var db: MicroCoachingDatabase
    private lateinit var dao: NetworkEventDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MicroCoachingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.networkEventDao()
    }

    @After
    fun tearDown() = db.close()

    private fun rows(count: Int, from: Long = 0) = (0 until count).map {
        NetworkEventEntity(
            startedAtMs = from + it,
            method = "GET",
            url = "https://example.test/$it",
            statusCode = 200,
            durationMs = 5,
            errorType = null,
        )
    }

    @Test
    fun insertAndPurge_keepsTheNewest500() = runBlocking {
        dao.insertAndPurge(rows(550))

        val all = dao.after(0, 1000)
        assertEquals(500, all.size)
        assertEquals(51L, all.first().id)
        assertEquals(550L, all.last().id)
    }

    @Test
    fun after_ordersAscendingAndHonoursLimit() = runBlocking {
        dao.insertAll(rows(10))

        val page = dao.after(3, 4)

        assertEquals(listOf(4L, 5L, 6L, 7L), page.map { it.id })
    }

    @Test
    fun maxId_isZeroWhenEmpty() = runBlocking {
        assertEquals(0L, dao.maxId())
    }

    @Test
    fun idsKeepGrowingAfterDeleteAll() = runBlocking {
        dao.insertAll(rows(3))
        dao.deleteAll()
        dao.insertAll(rows(1))

        assertEquals(listOf(4L), dao.after(0, 10).map { it.id })
        assertEquals(4L, dao.maxId())
    }
}
