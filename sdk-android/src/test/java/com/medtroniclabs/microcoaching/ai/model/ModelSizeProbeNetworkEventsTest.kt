package com.medtroniclabs.microcoaching.ai.model

import com.medtroniclabs.microcoaching.network.events.NetworkEventCapture
import com.medtroniclabs.microcoaching.network.events.assertRecordsLast
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ModelSizeProbeNetworkEventsTest {

    @get:Rule
    val capture = NetworkEventCapture()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun variant() = ModelVariant(
        id = "probe-test",
        displayName = "Probe test",
        fileName = "m.bin",
        downloadUrl = server.url("/models/m.bin").toString(),
        sizeInBytes = 1,
        runtime = ModelRuntime.LITERT_LM,
        minDeviceMemoryGb = 1,
        requiresAccessToken = false,
        params = "1B",
    )

    @Test
    fun probe_headAnswered_recordsOneRow() {
        server.enqueue(MockResponse().setHeader("x-linked-size", SIZE.toString()))
        server.start()

        assertEquals(SIZE, ModelSizeProbe.probe(variant(), hfToken = ""))

        val row = capture.rows.single()
        assertEquals("HEAD", row.method)
        assertEquals(200, row.statusCode)
    }

    @Test
    fun probe_headRefused_recordsHeadThenRangedGet() {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/$SIZE").setBody("x"),
        )
        server.start()

        assertEquals(SIZE, ModelSizeProbe.probe(variant(), hfToken = ""))

        val rows = capture.rows
        assertEquals(listOf("HEAD" to 405, "GET" to 206), rows.map { it.method to it.statusCode })
    }

    @Test
    fun client_recordsLast() {
        assertRecordsLast(ModelSizeProbe.client)
    }

    private companion object {
        const val SIZE = 2_000_000L
    }
}
