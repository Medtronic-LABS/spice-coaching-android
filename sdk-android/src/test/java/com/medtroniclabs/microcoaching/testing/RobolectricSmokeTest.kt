package com.medtroniclabs.microcoaching.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.LearningCompletionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Proves the test toolchain (Robolectric, MockWebServer, coroutines-test) runs in this module. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricSmokeTest {

    @Test
    fun inMemoryDatabase_insertsAndReadsBackOneRow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MicroCoachingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.learningCompletionDao()
            dao.insert(
                LearningCompletionEntity(
                    chwId = "chw-test",
                    kind = LearningCompletionEntity.KIND_VIDEO,
                    itemId = "video-1",
                    completedAt = 1_700_000_000_000L,
                ),
            )
            assertEquals(listOf(1_700_000_000_000L), dao.observeCompletionTimes("chw-test").first())
        } finally {
            db.close()
        }
    }

    @Test
    fun mockWebServer_roundTripsABody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("pong"))
            server.start()
            val request = Request.Builder().url(server.url("/ping")).build()
            val body = OkHttpClient().newCall(request).execute().use { it.body!!.string() }
            assertEquals("pong", body)
            assertEquals("/ping", server.takeRequest().path)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun runTest_advancesVirtualTime() = runTest {
        var done = false
        launch {
            delay(1000)
            done = true
        }
        advanceUntilIdle()
        assertEquals(true, done)
        assertEquals(1000L, currentTime)
    }
}
