package com.medtroniclabs.microcoaching.network.events

/**
 * Read and clear access to the stored network events, behind which the public
 * diagnostics API sits. Both calls throw [NetworkEventRecorderNotBoundException]
 * while no store is bound, and let store failures propagate.
 */
internal interface NetworkEventLog {
    suspend fun read(afterId: Long, limit: Int): NetworkEventPage

    /** Deletes every stored row; returns the highest id deleted (0 if none). */
    suspend fun clear(): Long
}
