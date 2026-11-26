package com.medtroniclabs.microcoaching.network.events

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Application interceptor that records one [NetworkEventRow] per call: the
 * requested URL (sanitised here, on the calling thread), the final status and
 * the time to response headers, or the classified failure. Responses served
 * wholly from cache are not recorded.
 *
 * It calls `chain.proceed` exactly once and always returns the original
 * response or rethrows the original exception. It never reads headers, bodies,
 * cookies or exception messages.
 */
internal class NetworkEventInterceptor(
    private val sinkProvider: () -> NetworkEventSink?,
    private val clock: NetworkClock = NetworkClock.System,
) : Interceptor {

    private val dropped = AtomicLong()

    /** Rows lost because building or offering them threw. */
    val droppedCount: Long get() = dropped.get()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val call = chain.call()
        val startedAtMs = clock.wallMs()
        val t0 = clock.monoNs()
        try {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                record(request, startedAtMs, t0, statusCode = null) {
                    NetworkErrorClassifier.classify(e, ConnectionProbe.isConnected(call), call.isCanceled())
                }
                throw e
            } catch (e: RuntimeException) {
                record(request, startedAtMs, t0, statusCode = null) { NetworkErrorType.UNKNOWN }
                throw e
            }
            if (response.networkResponse != null) {
                val code = response.code
                if (code in VALID_STATUS) {
                    record(request, startedAtMs, t0, statusCode = code) { null }
                } else {
                    record(request, startedAtMs, t0, statusCode = null) { NetworkErrorType.UNKNOWN }
                }
            }
            return response
        } finally {
            ConnectionProbe.clear(call)
        }
    }

    /**
     * Builds and offers one row. The broad catch is a deliberate boundary:
     * recording must never fail, delay or change the call it observes, so any
     * runtime failure in the sink or while building the row only bumps
     * [droppedCount]. Nothing is logged, so no URL or message can leak.
     */
    private inline fun record(
        request: Request,
        startedAtMs: Long,
        t0: Long,
        statusCode: Int?,
        errorType: () -> NetworkErrorType?,
    ) {
        try {
            val durationMs = ((clock.monoNs() - t0) / NANOS_PER_MILLI).coerceAtLeast(0L)
            val sink = sinkProvider() ?: return
            sink.offer(
                NetworkEventRow(
                    startedAtMs = startedAtMs,
                    method = request.method,
                    url = UrlSanitizer.sanitize(request.url.toString()),
                    statusCode = statusCode,
                    durationMs = durationMs,
                    errorType = errorType(),
                ),
            )
        } catch (e: RuntimeException) {
            dropped.incrementAndGet()
        }
    }

    private companion object {
        val VALID_STATUS = 100..599
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
