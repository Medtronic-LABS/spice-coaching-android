package com.medtroniclabs.microcoaching.network.events

import java.security.MessageDigest

/**
 * A short, stable reference for a storage key or object path, safe to write to
 * logcat. Logs carry this instead of the value itself (an object URL or path).
 */
internal object LogRef {
    private const val LENGTH = 8

    fun of(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(LENGTH)
}
