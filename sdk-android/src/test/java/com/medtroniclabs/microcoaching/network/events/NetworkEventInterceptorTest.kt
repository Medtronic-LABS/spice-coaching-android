package com.medtroniclabs.microcoaching.network.events

import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/** Spec §10 `NetworkEventInterceptorTest`: real OkHttp calls against MockWebServer. */
class NetworkEventInterceptorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val rows: MutableList<NetworkEventRow> = Collections.synchronizedList(mutableListOf())
    private val capture = NetworkEventSink { rows += it }
    private val clock = FakeClock()

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        NetworkEventSinkHolder.sink = null
        server.shutdown()
    }

    private fun builder(
        sinkProvider: () -> NetworkEventSink? = { capture },
    ): OkHttpClient.Builder = OkHttpClient.Builder()
        .addInterceptor(NetworkEventInterceptor(sinkProvider, clock))
        .addNetworkInterceptor(ConnectionProbe)

    private fun get(client: OkHttpClient, url: HttpUrl): Response =
        client.newCall(Request.Builder().url(url).build()).execute()

    private fun origin(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"

    private fun expectFailure(client: OkHttpClient, request: Request): Throwable {
        try {
            client.newCall(request).execute().close()
        } catch (e: IOException) {
            return e
        }
        fail("expected the call to fail")
        throw AssertionError()
    }

    private fun singleRow(): NetworkEventRow {
        assertEquals(1, rows.size)
        return rows.single()
    }

    // --- success rows -----------------------------------------------------

    @Test
    fun status200_404_500_recordOneRowEach() {
        listOf(200, 404, 500).forEach { server.enqueue(MockResponse().setResponseCode(it).setBody("b")) }
        server.start()
        val client = builder().build()
        val url = server.url("/api/items")

        listOf(200, 404, 500).forEach { get(client, url).use { r -> assertEquals(it, r.code) } }

        assertEquals(3, rows.size)
        rows.zip(listOf(200, 404, 500)).forEach { (row, code) ->
            assertEquals("GET", row.method)
            assertEquals("${origin(url)}/api/items", row.url)
            assertEquals(code, row.statusCode)
            assertNull(row.errorType)
            assertEquals(FakeClock.WALL_MS, row.startedAtMs)
            assertEquals(FakeClock.STEP_MS, row.durationMs)
        }
    }

    @Test
    fun durationIsNeverNegative_whenTheMonotonicClockGoesBack() {
        server.enqueue(MockResponse())
        server.start()
        val backwards = object : NetworkClock {
            private val next = AtomicLong(1_000_000_000L)
            override fun wallMs() = FakeClock.WALL_MS
            override fun monoNs() = next.getAndAdd(-5_000_000L)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(NetworkEventInterceptor({ capture }, backwards))
            .build()

        get(client, server.url("/x")).close()

        assertEquals(0L, singleRow().durationMs)
    }

    @Test
    fun presignedUrl_storesNoQuery_andNoSignatureOrCredential() {
        server.enqueue(MockResponse().setBody("img"))
        server.start()
        val url = server.url(
            "/bucket/photo.png?X-Goog-Algorithm=GOOG4-RSA-SHA256" +
                "&X-Goog-Credential=fakesvc%40fake-project.example%2F20261126%2Fauto%2Fstorage%2Fgoog4_request" +
                "&X-Goog-Date=20261126T000000Z&X-Goog-Expires=900&X-Goog-SignedHeaders=host" +
                "&X-Goog-Signature=$FAKE_SIGNATURE",
        )

        get(builder().build(), url).close()

        val row = singleRow()
        assertEquals("${origin(url)}/bucket/photo.png", row.url)
        assertNoneContain(row, FAKE_SIGNATURE, "fakesvc", "fake-project", "goog4_request", "X-Goog", "?")
    }

    @Test
    fun redirect_recordsOneRowWithTheOriginalUrlAndFinalStatus() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(MockResponse().setBody("done"))
        server.start()
        val url = server.url("/start")

        get(builder().build(), url).use { assertEquals("done", it.body!!.string()) }

        val row = singleRow()
        assertEquals("${origin(url)}/start", row.url)
        assertEquals(200, row.statusCode)
        assertNull(row.errorType)
    }

    @Test
    fun cacheHit_recordsNothing() {
        server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=600").setBody("cached"))
        server.start()
        val client = builder().cache(Cache(tmp.newFolder("http-cache"), 1024L * 1024L)).build()
        val url = server.url("/cacheable")

        get(client, url).use { assertEquals("cached", it.body!!.string()) }
        get(client, url).use {
            assertEquals("cached", it.body!!.string())
            assertNull(it.networkResponse)
        }

        assertEquals(1, server.requestCount)
        assertEquals(1, rows.size)
    }

    @Test
    fun conditional304_recordsOneRow() {
        server.enqueue(MockResponse().setHeader("Cache-Control", "no-cache").setHeader("ETag", "\"v1\"").setBody("v1"))
        server.enqueue(MockResponse().setResponseCode(304))
        server.start()
        val client = builder().cache(Cache(tmp.newFolder("http-cache"), 1024L * 1024L)).build()
        val url = server.url("/validated")
        get(client, url).use { assertEquals("v1", it.body!!.string()) }
        rows.clear()

        get(client, url).use {
            assertEquals("v1", it.body!!.string())
            assertEquals(304, it.networkResponse!!.code)
        }

        // The status is the response the caller sees (`resp.code`), which OkHttp
        // rebuilds from the validated cache entry.
        val row = singleRow()
        assertEquals(200, row.statusCode)
        assertNull(row.errorType)
    }

    @Test
    fun outOfRangeStatus_isStoredAsNullWithUnknown() {
        server.enqueue(MockResponse().setStatus("HTTP/1.1 999 Odd"))
        server.start()

        get(builder().build(), server.url("/odd")).use { assertEquals(999, it.code) }

        val row = singleRow()
        assertNull(row.statusCode)
        assertEquals(NetworkErrorType.UNKNOWN, row.errorType)
    }

    @Test
    fun authorizationHeaderAndBody_neverReachAStoredField() {
        server.enqueue(MockResponse().setHeader("Set-Cookie", "session=$COOKIE_MARKER").setBody(RESPONSE_MARKER))
        server.start()
        val request = Request.Builder()
            .url(server.url("/submit"))
            .header("Authorization", "Bearer $TOKEN_MARKER")
            .header("Cookie", "sid=$COOKIE_MARKER")
            .post("{\"note\":\"$BODY_MARKER\"}".toRequestBody("application/json".toMediaType()))
            .build()

        builder().build().newCall(request).execute().use { assertEquals(RESPONSE_MARKER, it.body!!.string()) }

        val row = singleRow()
        assertEquals("POST", row.method)
        assertNoneContain(row, TOKEN_MARKER, BODY_MARKER, COOKIE_MARKER, RESPONSE_MARKER, "Bearer")
    }

    // --- failure rows -----------------------------------------------------

    @Test
    fun readTimeout_isReceiveTimeout() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val client = builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        val url = server.url("/slow")

        val error = expectFailure(client, Request.Builder().url(url).build())

        assertTrue(error is SocketTimeoutException)
        val row = singleRow()
        assertEquals(NetworkErrorType.RECEIVE_TIMEOUT, row.errorType)
        assertNull(row.statusCode)
        assertEquals("${origin(url)}/slow", row.url)
        assertEquals(FakeClock.STEP_MS, row.durationMs)
    }

    @Test
    fun connectTimeout_isConnectionTimeout() {
        val timingOut = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) = throw SocketTimeoutException()
            }
            override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) =
                throw UnsupportedOperationException()
            override fun createSocket(host: InetAddress?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) =
                throw UnsupportedOperationException()
        }
        val client = builder().socketFactory(timingOut).dns(loopbackDns).build()

        expectFailure(client, Request.Builder().url("http://api.example.test/v1/sync").build())

        val row = singleRow()
        assertEquals(NetworkErrorType.CONNECTION_TIMEOUT, row.errorType)
        assertEquals("http://api.example.test/v1/sync", row.url)
    }

    @Test
    fun refusedPort_isConnectionError() {
        server.start()
        val url = server.url("/gone")
        server.shutdown()

        expectFailure(builder().build(), Request.Builder().url(url).build())

        assertEquals(NetworkErrorType.CONNECTION_ERROR, singleRow().errorType)
    }

    @Test
    fun unknownHost_isConnectionError() {
        val failingDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException()
        }

        expectFailure(builder().dns(failingDns).build(), Request.Builder().url("https://nowhere.example.test/").build())

        val row = singleRow()
        assertEquals(NetworkErrorType.CONNECTION_ERROR, row.errorType)
        assertNull(row.statusCode)
    }

    @Test
    fun untrustedCertificate_isBadCertificate() {
        val serverCert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(serverCert).build().sslSocketFactory(), false)
        server.enqueue(MockResponse())
        server.start(InetAddress.getByName("localhost"), 0)
        val otherRoot = HeldCertificate.Builder().build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(otherRoot.certificate).build()
        val client = builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()

        expectFailure(client, Request.Builder().url(server.url("/secure")).build())

        assertEquals(NetworkErrorType.BAD_CERTIFICATE, singleRow().errorType)
    }

    @Test
    fun canceledBeforeExecute_isCancel() {
        server.enqueue(MockResponse())
        server.start()
        val call = builder().build().newCall(Request.Builder().url(server.url("/c")).build())
        call.cancel()

        try {
            call.execute().close()
            fail("expected cancel")
        } catch (_: IOException) {
            // expected: the call was canceled
        }

        assertEquals(NetworkErrorType.CANCEL, singleRow().errorType)
    }

    @Test
    fun canceledInFlight_isCancel() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val call = builder().readTimeout(10, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(server.url("/c")).build())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            scheduler.schedule({ call.cancel() }, 300, TimeUnit.MILLISECONDS)
            try {
                call.execute().close()
                fail("expected cancel")
            } catch (_: IOException) {
                // expected: the call was canceled mid-flight
            }
        } finally {
            scheduler.shutdownNow()
        }

        assertEquals(NetworkErrorType.CANCEL, singleRow().errorType)
    }

    @Test
    fun failure_rethrowsTheSameIOExceptionInstance() {
        val thrown = IOException()
        val client = builder().addInterceptor { throw thrown }.build()

        val caught = expectFailure(client, Request.Builder().url("http://api.example.test/").build())

        assertSame(thrown, caught)
        assertEquals(NetworkErrorType.UNKNOWN, singleRow().errorType)
    }

    @Test
    fun runtimeFailure_rethrowsTheSameInstance_andRecordsUnknown() {
        val thrown = IllegalStateException()
        val client = builder().addInterceptor { throw thrown }.build()

        try {
            client.newCall(Request.Builder().url("http://api.example.test/").build()).execute().close()
            fail("expected the runtime exception")
        } catch (e: IllegalStateException) {
            assertSame(thrown, e)
        }

        val row = singleRow()
        assertEquals(NetworkErrorType.UNKNOWN, row.errorType)
        assertNull(row.statusCode)
    }

    @Test
    fun runtimeFailureOnACanceledCall_isCancel() {
        val thrown = IllegalStateException()
        val client = builder().addInterceptor { chain -> chain.call().cancel(); throw thrown }.build()

        try {
            client.newCall(Request.Builder().url("http://api.example.test/").build()).execute().close()
            fail("expected the runtime exception")
        } catch (e: IllegalStateException) {
            assertSame(thrown, e)
        }

        assertEquals(NetworkErrorType.CANCEL, singleRow().errorType)
    }

    // --- never fails or changes a call -------------------------------------

    @Test
    fun throwingSink_stillReturnsTheResponseWithItsBody() {
        server.enqueue(MockResponse().setBody("intact"))
        server.start()
        val interceptor = NetworkEventInterceptor({ NetworkEventSink { throw IllegalStateException() } }, clock)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).addNetworkInterceptor(ConnectionProbe).build()

        get(client, server.url("/ok")).use {
            assertEquals(200, it.code)
            assertEquals("intact", it.body!!.string())
        }
        assertEquals(1L, interceptor.droppedCount)
    }

    @Test
    fun throwingSink_stillRethrowsTheOriginalFailure() {
        val thrown = IOException()
        val client = OkHttpClient.Builder()
            .addInterceptor(NetworkEventInterceptor({ NetworkEventSink { throw IllegalStateException() } }, clock))
            .addInterceptor { throw thrown }
            .build()

        assertSame(thrown, expectFailure(client, Request.Builder().url("http://api.example.test/").build()))
    }

    @Test
    fun sinkThrowingCheckedException_stillReturnsTheResponseWithItsBody() {
        server.enqueue(MockResponse().setBody("intact"))
        server.start()
        val interceptor = NetworkEventInterceptor({ NetworkEventSink { throw IOException() } }, clock)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).addNetworkInterceptor(ConnectionProbe).build()

        get(client, server.url("/ok")).use {
            assertEquals(200, it.code)
            assertEquals("intact", it.body!!.string())
        }
        assertEquals(1L, interceptor.droppedCount)
    }

    @Test
    fun sinkThrowingCheckedException_stillRethrowsTheOriginalFailure() {
        val thrown = IOException()
        val interceptor = NetworkEventInterceptor({ NetworkEventSink { throw IOException() } }, clock)
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { throw thrown }
            .build()

        assertSame(thrown, expectFailure(client, Request.Builder().url("http://api.example.test/").build()))
        assertEquals(1L, interceptor.droppedCount)
    }

    @Test
    fun sinkThrowingLinkageError_stillReturnsTheResponse() {
        server.enqueue(MockResponse().setBody("intact"))
        server.start()
        val interceptor = NetworkEventInterceptor({ NetworkEventSink { throw NoClassDefFoundError() } }, clock)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        get(client, server.url("/ok")).use { assertEquals("intact", it.body!!.string()) }
        assertEquals(1L, interceptor.droppedCount)
    }

    @Test
    fun throwingSinkProvider_neitherFailsNorChangesTheCall() {
        server.enqueue(MockResponse().setBody("intact"))
        server.start()
        val interceptor = NetworkEventInterceptor({ throw IOException() }, clock)
        val okClient = OkHttpClient.Builder().addInterceptor(interceptor).build()

        get(okClient, server.url("/ok")).use { assertEquals("intact", it.body!!.string()) }

        val thrown = IOException()
        val failingClient = OkHttpClient.Builder()
            .addInterceptor(NetworkEventInterceptor({ throw IllegalStateException() }, clock))
            .addInterceptor { throw thrown }
            .build()
        assertSame(thrown, expectFailure(failingClient, Request.Builder().url("http://api.example.test/").build()))
        assertEquals(1L, interceptor.droppedCount)
    }

    @Test
    fun throwingClock_neitherFailsNorChangesTheCall_andStillRecords() {
        server.enqueue(MockResponse().setBody("intact"))
        server.start()
        val broken = object : NetworkClock {
            override fun wallMs(): Long = throw IllegalStateException()
            override fun monoNs(): Long = throw IllegalStateException()
        }
        val client = OkHttpClient.Builder().addInterceptor(NetworkEventInterceptor({ capture }, broken)).build()

        get(client, server.url("/ok")).use { assertEquals("intact", it.body!!.string()) }

        val row = singleRow()
        assertEquals(200, row.statusCode)
        assertTrue(row.startedAtMs > 0L)
        assertTrue(row.durationMs >= 0L)
    }

    @Test
    fun clockThrowingOnlyAtTheEnd_dropsTheRowButKeepsTheCall() {
        server.enqueue(MockResponse().setBody("intact"))
        server.start()
        val readings = AtomicInteger()
        val failsLate = object : NetworkClock {
            override fun wallMs(): Long = FakeClock.WALL_MS
            override fun monoNs(): Long =
                if (readings.getAndIncrement() == 0) 0L else throw IllegalStateException()
        }
        val interceptor = NetworkEventInterceptor({ capture }, failsLate)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        get(client, server.url("/ok")).use { assertEquals("intact", it.body!!.string()) }

        assertTrue(rows.isEmpty())
        assertEquals(1L, interceptor.droppedCount)
    }

    @Test
    fun nullSink_noCrashNoRow() {
        server.enqueue(MockResponse().setBody("fine"))
        server.start()

        get(builder { null }.build(), server.url("/ok")).use { assertEquals("fine", it.body!!.string()) }

        assertTrue(rows.isEmpty())
    }

    @Test
    fun downstreamInterceptor_seesExactlyOneProceed_evenAcrossARedirect() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        server.enqueue(MockResponse().setBody("x"))
        server.start()
        val proceeds = AtomicInteger()
        val client = builder()
            .addInterceptor(Interceptor { chain -> proceeds.incrementAndGet(); chain.proceed(chain.request()) })
            .build()

        get(client, server.url("/first")).close()

        assertEquals(1, proceeds.get())
        assertEquals(1, rows.size)
    }

    @Test
    fun probeSet_isEmptyAfterSuccessfulFailedAndCanceledCalls() {
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val client = builder().readTimeout(300, TimeUnit.MILLISECONDS).build()

        get(client, server.url("/ok")).close()
        expectFailure(client, Request.Builder().url(server.url("/timeout")).build())
        val call = client.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(server.url("/cancel")).build())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            scheduler.schedule({ call.cancel() }, 200, TimeUnit.MILLISECONDS)
            try {
                call.execute().close()
            } catch (_: IOException) {
                // expected: canceled mid-flight
            }
        } finally {
            scheduler.shutdownNow()
        }

        assertEquals(3, rows.size)
        assertEquals(0, ConnectionProbe.pendingCount())
    }

    // --- the installer ------------------------------------------------------

    @Test
    fun instrument_readsTheHolderSinkAtCallTime() {
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        server.start()
        val client = NetworkEvents.instrument(OkHttpClient.Builder()).build()
        val url = server.url("/late-bind")

        get(client, url).close()
        assertTrue(rows.isEmpty())

        NetworkEventSinkHolder.sink = capture
        get(client, url).close()

        val row = singleRow()
        assertEquals("${origin(url)}/late-bind", row.url)
        assertEquals(200, row.statusCode)
        assertSame(capture, NetworkEventSinkHolder.current())
    }

    @Test
    fun instrument_isIdempotent() {
        val builder = OkHttpClient.Builder()
        NetworkEvents.instrument(builder)
        NetworkEvents.instrument(builder)

        val client = builder.build()

        assertEquals(1, client.interceptors.count { it is NetworkEventInterceptor })
        assertEquals(1, client.networkInterceptors.count { it === ConnectionProbe })
    }

    @Test
    fun instrument_addsTheApplicationInterceptorAndTheProbe() {
        val client = NetworkEvents.instrument(OkHttpClient.Builder()).build()

        assertTrue(client.interceptors.single() is NetworkEventInterceptor)
        assertSame(ConnectionProbe, client.networkInterceptors.single())
    }

    private fun assertNoneContain(row: NetworkEventRow, vararg secrets: String) {
        val fields = listOf(
            row.method, row.url, row.statusCode.toString(), row.durationMs.toString(),
            row.startedAtMs.toString(), row.errorType?.wireName.toString(), row.toString(),
        )
        secrets.forEach { secret ->
            fields.forEach { assertFalse("stored field leaks a marker", it.contains(secret)) }
        }
    }

    private val loopbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    }

    /** Fixed wall clock; the monotonic clock advances [STEP_MS] per reading. */
    private class FakeClock : NetworkClock {
        private val mono = AtomicLong(1_000_000_000L)
        override fun wallMs(): Long = WALL_MS
        override fun monoNs(): Long = mono.getAndAdd(STEP_MS * 1_000_000L)

        companion object {
            const val WALL_MS = 1_764_115_200_000L
            const val STEP_MS = 7L
        }
    }

    private companion object {
        const val FAKE_SIGNATURE = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a6978"
        const val TOKEN_MARKER = "fake-token-AUTHMARK"
        const val BODY_MARKER = "BODYMARK-fake-note"
        const val COOKIE_MARKER = "COOKIEMARK"
        const val RESPONSE_MARKER = "RESPONSEMARK"
    }
}
