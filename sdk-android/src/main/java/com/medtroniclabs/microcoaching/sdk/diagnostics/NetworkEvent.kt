package com.medtroniclabs.microcoaching.sdk.diagnostics

/**
 * One SDK network call as stored on the device. [url] is already sanitised.
 * [statusCode] is null when no HTTP response arrived; [errorType] is then one of
 * `connectionTimeout`, `connectionError`, `receiveTimeout`, `badCertificate`,
 * `cancel` or `unknown`, and null otherwise. [durationMs] is the time to the
 * response headers and is never negative.
 */
data class NetworkEvent(
    val id: Long,
    val startedAtMs: Long,
    val method: String,
    val url: String,
    val statusCode: Int?,
    val durationMs: Long,
    val errorType: String?,
)
