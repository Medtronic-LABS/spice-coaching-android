package com.medtroniclabs.microcoaching.network.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRefTest {
    private val key = "https://storage.example.test/bucket/ingest/thumbnails/a.png"

    @Test fun isShortStableLowercaseHex() {
        val ref = LogRef.of(key)
        assertEquals(8, ref.length)
        assertTrue(ref.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(ref, LogRef.of(key))
    }

    @Test fun doesNotContainTheInputAndDiffersPerKey() {
        assertFalse(LogRef.of(key).contains("example"))
        assertNotEquals(LogRef.of(key), LogRef.of("$key.other"))
    }
}
