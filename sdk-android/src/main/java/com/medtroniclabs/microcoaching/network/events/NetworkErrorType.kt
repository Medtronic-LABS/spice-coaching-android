package com.medtroniclabs.microcoaching.network.events

/**
 * The `error_type` vocabulary of the network-events contract v1. [wireName] is
 * the only place each value is spelled.
 */
internal enum class NetworkErrorType(val wireName: String) {
    CONNECTION_TIMEOUT("connectionTimeout"),
    CONNECTION_ERROR("connectionError"),
    RECEIVE_TIMEOUT("receiveTimeout"),
    BAD_CERTIFICATE("badCertificate"),
    CANCEL("cancel"),
    UNKNOWN("unknown"),
}
