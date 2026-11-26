package com.medtroniclabs.microcoaching.network.events

/** Time sources for the network log: wall clock for `started_at_ms`, monotonic for durations. */
internal interface NetworkClock {
    fun wallMs(): Long
    fun monoNs(): Long

    companion object {
        val System: NetworkClock = object : NetworkClock {
            override fun wallMs(): Long = java.lang.System.currentTimeMillis()
            override fun monoNs(): Long = java.lang.System.nanoTime()
        }
    }
}
