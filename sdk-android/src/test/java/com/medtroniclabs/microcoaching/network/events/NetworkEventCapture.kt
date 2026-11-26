package com.medtroniclabs.microcoaching.network.events

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import java.util.Collections

/**
 * Publishes a capturing sink to [NetworkEventSinkHolder] for one test and
 * removes it afterwards, so a test can count the rows an SDK client records.
 */
class NetworkEventCapture : ExternalResource() {

    private val captured: MutableList<NetworkEventRow> = Collections.synchronizedList(mutableListOf())

    internal val rows: List<NetworkEventRow> get() = synchronized(captured) { captured.toList() }

    override fun before() {
        captured.clear()
        NetworkEventSinkHolder.sink = NetworkEventSink { captured += it }
    }

    override fun after() {
        NetworkEventSinkHolder.sink = null
    }
}

/**
 * The capture interceptor must be the last application interceptor, so the
 * request it records is the one that goes on the wire (spec §7a), and the
 * connection probe must be installed once as a network interceptor.
 */
internal fun assertRecordsLast(client: OkHttpClient) {
    assertTrue(
        "NetworkEventInterceptor must be the last application interceptor, was ${client.interceptors}",
        client.interceptors.lastOrNull() is NetworkEventInterceptor,
    )
    assertEquals(1, client.interceptors.count { it is NetworkEventInterceptor })
    assertEquals(1, client.networkInterceptors.count { it === ConnectionProbe })
}
