package com.medtroniclabs.microcoaching.ai.download

import com.medtroniclabs.microcoaching.network.events.NetworkEventCapture
import com.medtroniclabs.microcoaching.network.events.assertRecordsLast
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableHttpDownloaderNetworkEventsTest {

    @get:Rule
    val capture = NetworkEventCapture()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun download_recordsOneRow() = runBlocking {
        server.enqueue(MockResponse().setBody("model-bytes"))
        server.start()
        val url = server.url("/models/m.bin")

        val result = ResumableHttpDownloader.download(
            url = url.toString(),
            outputFile = tmp.newFile("m.bin").also { it.delete() },
            minValidBytes = 1,
        ) { _, _, _ -> }

        assertSame(DownloadResult.Success, result)
        val row = capture.rows.single()
        assertEquals("GET", row.method)
        assertEquals("${url.scheme}://${url.host}:${url.port}/models/m.bin", row.url)
        assertEquals(200, row.statusCode)
        assertNull(row.errorType)
    }

    @Test
    fun newClient_isBuiltPerCallWithCaptureLast() {
        val first = ResumableHttpDownloader.newClient()
        val second = ResumableHttpDownloader.newClient()

        assertNotSame(first, second)
        assertRecordsLast(first)
        assertRecordsLast(second)
    }
}
