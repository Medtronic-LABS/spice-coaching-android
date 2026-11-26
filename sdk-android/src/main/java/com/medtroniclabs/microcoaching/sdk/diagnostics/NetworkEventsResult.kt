package com.medtroniclabs.microcoaching.sdk.diagnostics

/** Outcome of a network-events call. [wire] is the contract v1 `status`; [STORE_ERROR] has none. */
enum class NetworkEventsStatus(val wire: String?) {
    OK("ok"),
    NOT_INITIALIZED("notInitialized"),

    /** The store failed; a host reports this as a platform error, not as a payload. */
    STORE_ERROR(null),
}

/**
 * One page of [events] (ascending ids) and [maxId], the highest stored id
 * (0 when the store is empty), read together. A [maxId] below the caller's
 * cursor means the store was cleared.
 */
data class NetworkEventsResult(
    val status: NetworkEventsStatus,
    val events: List<NetworkEvent>,
    val maxId: Long,
) {
    val sdkEventVersion: Int get() = SDK_EVENT_VERSION

    /**
     * The contract v1 response map for `StandardMessageCodec`. Meant for [NetworkEventsStatus.OK]
     * and [NetworkEventsStatus.NOT_INITIALIZED]; for [NetworkEventsStatus.STORE_ERROR] the
     * `status` is null, which the app treats as a failed read.
     */
    fun toContractMap(): Map<String, Any?> = mapOf(
        "status" to status.wire,
        "sdk_event_version" to sdkEventVersion,
        "max_id" to maxId,
        "events" to events.map { event ->
            mapOf(
                "id" to event.id,
                "started_at_ms" to event.startedAtMs,
                "method" to event.method,
                "url" to event.url,
                "status_code" to event.statusCode,
                "duration_ms" to event.durationMs,
                "error_type" to event.errorType,
            )
        },
    )

    companion object {
        /** The network-events contract version; a breaking change bumps it. */
        const val SDK_EVENT_VERSION = 1
    }
}

/** Outcome of a clear: every row with an id up to [clearedThroughId] was deleted (0 if none). */
data class NetworkEventsClearResult(
    val status: NetworkEventsStatus,
    val clearedThroughId: Long,
)
