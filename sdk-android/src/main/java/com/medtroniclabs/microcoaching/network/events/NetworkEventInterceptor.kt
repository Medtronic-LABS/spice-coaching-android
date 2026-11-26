package com.medtroniclabs.microcoaching.network.events

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Application interceptor that records one [NetworkEventRow] per call: the
 * requested URL (sanitised here, on the calling thread), the final status and
 * the time to response headers, or the classified failure. Responses served
 * wholly from cache are not recorded. A cache entry revalidated by a 304 is
 * logged with the code the caller sees, 200, not the 304 on the wire.
 *
 * It calls `chain.proceed` exactly once and always returns the original
 * response or rethrows the original exception. It never reads headers, bodies,
 * cookies or exception messages.
 */
internal class NetworkEventInterceptor(
    private val sinkProvider: () -> NetworkEventSink?,
    private val clock: NetworkClock = NetworkClock.System,
    private val epoch: () -> Int = { NetworkEventRecorder.epoch },
) : Interceptor {

    private val dropped = AtomicLong()

    /** Rows lost because reading the clock, building or offering them threw. */
    val droppedCount: Long get() = dropped.get()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val call = chain.call()
        val start = start()
        try {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                recordFailure(request, start, call, e)
                throw e
            } catch (e: RuntimeException) {
                recordFailure(request, start, call, e)
                throw e
            }
            if (response.networkResponse != null) {
                val code = response.code
                if (code in VALID_STATUS) {
                    record(request, start, statusCode = code) { null }
                } else {
                    record(request, start, statusCode = null) { NetworkErrorType.UNKNOWN }
                }
            }
            return response
        } finally {
            ConnectionProbe.clear(call)
        }
    }

    /**
     * Reads the start time and the recorder epoch. The broad catch is a
     * deliberate boundary for the same reason as in [record]: if the injected
     * clock or epoch source throws, the call falls back to [NetworkClock.System]
     * and the recorder's own epoch instead of failing.
     */
    private fun start(): Start = try {
        Start(clock.wallMs(), clock.monoNs(), clock, epoch())
    } catch (e: Exception) {
        systemStart()
    } catch (e: LinkageError) {
        systemStart()
    }

    private fun systemStart(): Start = Start(
        NetworkClock.System.wallMs(),
        NetworkClock.System.monoNs(),
        NetworkClock.System,
        NetworkEventRecorder.epoch,
    )

    private fun recordFailure(request: Request, start: Start, call: Call, error: Exception) {
        record(request, start, statusCode = null) {
            NetworkErrorClassifier.classify(error, ConnectionProbe.isConnected(call), call.isCanceled())
        }
    }

    /**
     * Builds and offers one row. The broad catch is a deliberate boundary:
     * recording must never fail, delay or change the call it observes, so any
     * exception (checked ones included, which Kotlin sinks may throw) or
     * linkage error from the clock, the sink provider, the sink or while
     * building the row only bumps [droppedCount]. [VirtualMachineError] is
     * left to propagate. Nothing is logged, so no URL or message can leak.
     */
    private inline fun record(
        request: Request,
        start: Start,
        statusCode: Int?,
        errorType: () -> NetworkErrorType?,
    ) {
        try {
            val durationMs = ((start.clock.monoNs() - start.t0) / NANOS_PER_MILLI).coerceAtLeast(0L)
            val sink = sinkProvider() ?: return
            sink.offer(
                NetworkEventRow(
                    startedAtMs = start.wallMs,
                    method = request.method,
                    url = UrlSanitizer.sanitize(request.url.toString()),
                    statusCode = statusCode,
                    durationMs = durationMs,
                    errorType = errorType(),
                    epoch = start.epoch,
                ),
            )
        } catch (e: Exception) {
            dropped.incrementAndGet()
        } catch (e: LinkageError) {
            dropped.incrementAndGet()
        }
    }

    /**
     * When a call started, the clock that took the reading (so the duration uses
     * the same source) and the recorder epoch at that moment.
     */
    private class Start(val wallMs: Long, val t0: Long, val clock: NetworkClock, val epoch: Int)

    private companion object {
        val VALID_STATUS = 100..599
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
