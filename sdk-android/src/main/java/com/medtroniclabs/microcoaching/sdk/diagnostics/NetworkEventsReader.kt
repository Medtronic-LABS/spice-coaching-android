package com.medtroniclabs.microcoaching.sdk.diagnostics

import com.medtroniclabs.microcoaching.data.db.entity.NetworkEventEntity
import com.medtroniclabs.microcoaching.network.events.NetworkEventLog
import com.medtroniclabs.microcoaching.network.events.NetworkEventRecorderNotBoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The logic behind `MicroCoachingSDK.getNetworkEvents` and `clearNetworkEvents`
 * (spec §7e, §7h). Neither call throws: an unbound store maps to
 * [NetworkEventsStatus.NOT_INITIALIZED] and any other [Exception] from the store
 * to [NetworkEventsStatus.STORE_ERROR]. Only the caller's own cancellation and
 * [Error]s propagate. The store does its I/O on its own (IO) dispatcher.
 */
internal class NetworkEventsReader(
    private val isSdkInitialized: () -> Boolean,
    private val log: NetworkEventLog,
) {

    suspend fun read(afterId: Long, limit: Int): NetworkEventsResult {
        if (!isSdkInitialized()) return NOT_INITIALIZED
        val page = try {
            log.read(afterId.coerceAtLeast(0), limit.coerceIn(1, MAX_LIMIT))
        } catch (e: NetworkEventRecorderNotBoundException) {
            return NOT_INITIALIZED
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return STORE_ERROR
        } catch (e: Exception) {
            return STORE_ERROR
        }
        return NetworkEventsResult(NetworkEventsStatus.OK, page.rows.map { it.toNetworkEvent() }, page.maxId)
    }

    /** Works whenever the store is bound, even before the SDK is initialised. */
    suspend fun clear(): NetworkEventsClearResult = try {
        NetworkEventsClearResult(NetworkEventsStatus.OK, log.clear())
    } catch (e: NetworkEventRecorderNotBoundException) {
        NetworkEventsClearResult(NetworkEventsStatus.NOT_INITIALIZED, 0)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        CLEAR_STORE_ERROR
    } catch (e: Exception) {
        CLEAR_STORE_ERROR
    }

    private companion object {
        /** The contract's largest page. */
        const val MAX_LIMIT = 500

        val NOT_INITIALIZED = NetworkEventsResult(NetworkEventsStatus.NOT_INITIALIZED, emptyList(), 0)
        val STORE_ERROR = NetworkEventsResult(NetworkEventsStatus.STORE_ERROR, emptyList(), 0)
        val CLEAR_STORE_ERROR = NetworkEventsClearResult(NetworkEventsStatus.STORE_ERROR, 0)
    }
}

private fun NetworkEventEntity.toNetworkEvent() =
    NetworkEvent(id, startedAtMs, method, url, statusCode, durationMs, errorType)
