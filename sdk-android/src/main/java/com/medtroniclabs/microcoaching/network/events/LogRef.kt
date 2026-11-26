package com.medtroniclabs.microcoaching.network.events

import java.security.MessageDigest

/**
 * A short, stable reference for a storage key or object path, safe to write to
 * logcat. Logs carry this instead of the value itself (an object URL or path).
 */
internal object LogRef {
    private const val LENGTH = 8

    fun of(value: String): String =
        fromDigest(
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) },
        )

    /** The reference for a value whose lowercase-hex SHA-256 [digestHex] the caller already has. */
    fun fromDigest(digestHex: String): String = digestHex.take(LENGTH)
}
