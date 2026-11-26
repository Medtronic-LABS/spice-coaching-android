package com.medtroniclabs.microcoaching.data.asset

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.network.events.NetworkEventCapture
import com.medtroniclabs.microcoaching.network.events.assertRecordsLast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetCacheNetworkEventsTest {

    @get:Rule
    val capture = NetworkEventCapture()

    private lateinit var server: MockWebServer
    private lateinit var db: MicroCoachingDatabase
    private val scope = CoroutineScope(Job())

    @Before
    fun setUp() {
        server = MockWebServer()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MicroCoachingDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        server.shutdown()
    }

    private fun cache() = AssetCache(
        context = ApplicationProvider.getApplicationContext(),
        dao = db.cachedAssetDao(),
        scope = scope,
        minFreeBytes = 0,
    ).also { it.isOnline = { true } }

    @Test
    fun download_recordsOneRowWithoutTheSignature() = runBlocking {
        // Chunked, so the free-space guard sees an unknown length and checks only the floor.
        server.enqueue(MockResponse().setChunkedBody("png-bytes", 4))
        server.start()
        val url = server.url("/assets/thumb.png?X-Amz-Signature=fake-signature-marker")

        val file = cache().localFileForUrl(url.toString())

        assertNotNull(file)
        val row = capture.rows.single()
        assertEquals("GET", row.method)
        assertEquals("${url.scheme}://${url.host}:${url.port}/assets/thumb.png", row.url)
        assertEquals(200, row.statusCode)
        assertFalse(row.url.contains("fake-signature-marker"))
    }

    @Test
    fun downloadClient_recordsLast() {
        assertRecordsLast(cache().downloadClient)
    }
}
