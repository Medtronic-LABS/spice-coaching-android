package com.medtroniclabs.microcoaching.network.events

/**
 * One captured request, before it is stored. [url] is already sanitised by
 * [UrlSanitizer], so nothing downstream ever holds a raw URL. [statusCode] is
 * null for failures and for codes outside 100..599; [errorType] is null for a
 * plain HTTP response. [epoch] is the [NetworkEventRecorder.epoch] read when the
 * request started, so a clear that runs while the call is in flight discards it.
 */
internal data class NetworkEventRow(
    val startedAtMs: Long,
    val method: String,
    val url: String,
    val statusCode: Int?,
    val durationMs: Long,
    val errorType: NetworkErrorType?,
    val epoch: Int,
)

/** Takes captured rows. Implementations must not block the calling (OkHttp) thread. */
internal fun interface NetworkEventSink {
    fun offer(event: NetworkEventRow)
}
