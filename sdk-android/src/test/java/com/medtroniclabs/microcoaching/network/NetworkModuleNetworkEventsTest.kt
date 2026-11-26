package com.medtroniclabs.microcoaching.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.network.events.NetworkEventCapture
import com.medtroniclabs.microcoaching.network.events.assertRecordsLast
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkModuleNetworkEventsTest {

    @get:Rule
    val capture = NetworkEventCapture()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun config(debugLogging: Boolean) = MicroCoachingConfig(
        context = ApplicationProvider.getApplicationContext<Context>(),
        backendUrl = "https://api.example.test/",
        authToken = "fake-token",
        enableOtelDebugLogging = debugLogging,
    )

    @Test
    fun createOkHttpClient_recordsOneRow() {
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        val url = server.url("/api/modules")
        val client = NetworkModule.createOkHttpClient(config(debugLogging = false))

        client.newCall(Request.Builder().url(url).build()).execute().close()

        val row = capture.rows.single()
        assertEquals("GET", row.method)
        assertEquals("${url.scheme}://${url.host}:${url.port}/api/modules", row.url)
        assertEquals(200, row.statusCode)
    }

    @Test
    fun createOkHttpClient_recordsAfterAuthAndLogging() {
        val client = NetworkModule.createOkHttpClient(config(debugLogging = true))

        assertEquals(3, client.interceptors.size)
        assertTrue(client.interceptors[1] is HttpLoggingInterceptor)
        assertRecordsLast(client)
    }

    @Test
    fun createOkHttpClient_recordsAfterAuthWithoutLogging() {
        val client = NetworkModule.createOkHttpClient(config(debugLogging = false))

        assertEquals(2, client.interceptors.size)
        assertRecordsLast(client)
    }
}
