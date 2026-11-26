package com.medtroniclabs.microcoaching.network.events

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.dao.NetworkEventDao
import com.medtroniclabs.microcoaching.data.db.entity.NetworkEventEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The process-wide [NetworkEventSink] that persists captured rows (spec §7d).
 *
 * [offer] is O(1) and never blocks: rows go into a bounded queue that drops its
 * oldest entry on overflow. One consumer drains the queue in batches of at most
 * [BATCH_SIZE] and writes each batch in one transaction. Writes and [clear] share
 * one lock, and every queued row carries the [epoch] it was offered in, so rows
 * queued before a clear are never written after it.
 *
 * The writer runs on its own scope, not the SDK's, because the SDK scope is
 * cancelled on shutdown and re-init. Rows lost to overflow, to an unbound
 * recorder or to a failed write are counted in [droppedCount]. Failures are
 * logged by exception type only, once per type per process.
 */
internal object NetworkEventRecorder : NetworkEventSink, NetworkEventLog {

    const val BATCH_SIZE = 64
    const val QUEUE_CAPACITY = 256
    private const val TAG = "NetworkEventRecorder"

    private val dropped = AtomicLong()
    private val epochCounter = AtomicInteger()
    private val loggedTypes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var writer: Writer? = null

    /** Opens the Room-backed store; the only place [bind] touches the database. Runs on the writer. */
    @VisibleForTesting
    internal var openStoreForContext: (Context) -> NetworkEventStore = ::openRoomStore

    private fun openRoomStore(context: Context) = NetworkEventStore.of(MicroCoachingDatabase.getInstance(context))

    /** Rows lost process-wide: queue overflow, offered while unbound, or a failed write. */
    val droppedCount: Long get() = dropped.get()

    /** Bumped by every [clear]; rows offered under an older epoch are discarded at write. */
    val epoch: Int get() = epochCounter.get()

    /**
     * Starts the writer and publishes this recorder to [NetworkEventSinkHolder].
     * Idempotent. Keeps only the application context; the database is opened
     * lazily on the writer's IO thread, never on the caller's.
     */
    fun bind(context: Context) {
        val app = context.applicationContext
        val open = openStoreForContext
        start(Dispatchers.IO, { open(app) }) { type ->
            Log.w(TAG, "Network event write failed: $type")
        }
    }

    /** This recorder once bound, else null. */
    fun current(): NetworkEventSink? = if (writer != null) this else null

    override fun offer(event: NetworkEventRow) {
        val w = writer
        if (w == null) {
            dropped.incrementAndGet()
            return
        }
        // DROP_OLDEST: trySend only fails once the channel is closed, and the
        // overflow victim is counted by onUndeliveredElement.
        if (!w.channel.trySend(Queued(event, epochCounter.get())).isSuccess) dropped.incrementAndGet()
    }

    /**
     * Deletes every stored row and returns the highest id deleted (0 if none).
     * Rows still queued are discarded; a batch already being written finishes
     * first and is deleted too. Store failures propagate to the caller.
     *
     * The epoch is bumped before the write lock is taken, so even when the
     * delete then fails, rows queued before this call are discarded and are
     * not counted in [droppedCount].
     *
     * @throws NetworkEventRecorderNotBoundException if [bind] has not run. It
     *   is an [IllegalStateException] subclass, so a caller that also maps
     *   store failures (IllegalStateException) must catch it first.
     */
    override suspend fun clear(): Long {
        val w = writer ?: throw NetworkEventRecorderNotBoundException()
        epochCounter.incrementAndGet()
        return withContext(w.dispatcher) { w.lock.withLock { w.store().clear() } }
    }

    /**
     * Reads one page on the writer's dispatcher, under the write lock so it
     * never sees a half-applied clear. Store failures propagate to the caller.
     *
     * @throws NetworkEventRecorderNotBoundException if [bind] has not run.
     */
    override suspend fun read(afterId: Long, limit: Int): NetworkEventPage {
        val w = writer ?: throw NetworkEventRecorderNotBoundException()
        return withContext(w.dispatcher) { w.lock.withLock { w.store().page(afterId, limit) } }
    }

    @VisibleForTesting
    internal fun bindForTesting(
        dispatcher: CoroutineDispatcher,
        openStore: () -> NetworkEventStore,
        log: (String) -> Unit,
    ) = start(dispatcher, openStore, log)

    @VisibleForTesting
    internal fun resetForTesting() {
        synchronized(this) {
            writer?.scope?.cancel()
            writer = null
            openStoreForContext = ::openRoomStore
            if (NetworkEventSinkHolder.sink === this) NetworkEventSinkHolder.sink = null
            dropped.set(0)
            epochCounter.set(0)
            loggedTypes.clear()
        }
    }

    private fun start(
        dispatcher: CoroutineDispatcher,
        openStore: () -> NetworkEventStore,
        log: (String) -> Unit,
    ) {
        if (writer == null) {
            synchronized(this) {
                if (writer == null) writer = Writer(dispatcher, openStore, log).also { it.launch() }
            }
        }
        NetworkEventSinkHolder.sink = this
    }

    private fun logOnce(error: Throwable, log: (String) -> Unit) {
        val type = error.javaClass.simpleName
        if (loggedTypes.add(type)) log(type)
    }

    private class Queued(val row: NetworkEventRow, val epoch: Int)

    /** The queue, its single consumer and the lazily opened store. */
    private class Writer(
        val dispatcher: CoroutineDispatcher,
        private val openStore: () -> NetworkEventStore,
        private val log: (String) -> Unit,
    ) {
        val lock = Mutex()
        val channel = Channel<Queued>(QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST) { dropped.incrementAndGet() }
        val scope = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> logOnce(e, log) },
        )

        /** Only touched while holding [lock]. */
        private var store: NetworkEventStore? = null

        fun store(): NetworkEventStore = store ?: openStore().also { store = it }

        fun launch() {
            scope.launch {
                for (first in channel) {
                    val batch = ArrayList<Queued>(BATCH_SIZE)
                    batch += first
                    while (batch.size < BATCH_SIZE) batch += channel.tryReceive().getOrNull() ?: break
                    write(batch)
                }
            }
        }

        /**
         * Writes one batch. Any [Exception] from opening or writing the store
         * (SQLException, IllegalStateException for a closed DB or failed
         * migration, or anything unexpected) costs only this batch, so the
         * consumer never dies. A [CancellationException] is rethrown only when
         * this writer is being cancelled; one thrown by the store while the
         * scope is active is an ordinary failure. Errors propagate.
         */
        private suspend fun write(batch: List<Queued>) = lock.withLock {
            val current = epochCounter.get()
            val rows = batch.filter { it.epoch == current }.map { it.row.toEntity() }
            if (rows.isEmpty()) return@withLock
            try {
                store().insertAndPurge(rows)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                failed(e, rows.size)
            } catch (e: Exception) {
                failed(e, rows.size)
            }
        }

        private fun failed(e: Exception, lost: Int) {
            dropped.addAndGet(lost.toLong())
            logOnce(e, log)
        }
    }
}

/** One read of the store: [rows] after a cursor and [maxId] (0 when empty), consistent with each other. */
internal class NetworkEventPage(val rows: List<NetworkEventEntity>, val maxId: Long)

/** Thrown by [NetworkEventRecorder.read] and [NetworkEventRecorder.clear] before the recorder is bound. */
internal class NetworkEventRecorderNotBoundException :
    IllegalStateException("Network event recorder is not bound")

/** The recorder's view of the database: the DAO plus a transaction runner. */
internal class NetworkEventStore(
    private val dao: NetworkEventDao,
    private val inTransaction: suspend (suspend () -> Long) -> Long,
) {
    suspend fun insertAndPurge(rows: List<NetworkEventEntity>) =
        dao.insertAndPurge(rows, NetworkEventDao.DEFAULT_KEEP)

    /** Rows with an id above [afterId] (ascending, at most [limit]) and the highest id, in one transaction. */
    suspend fun page(afterId: Long, limit: Int): NetworkEventPage {
        var rows = emptyList<NetworkEventEntity>()
        val maxId = inTransaction {
            rows = dao.after(afterId, limit)
            dao.maxId()
        }
        return NetworkEventPage(rows, maxId)
    }

    /** Deletes all rows in one transaction; returns the highest id deleted (0 if none). */
    suspend fun clear(): Long = inTransaction {
        val through = dao.maxId()
        dao.deleteAll()
        through
    }

    companion object {
        fun of(db: MicroCoachingDatabase) =
            NetworkEventStore(db.networkEventDao()) { block -> db.withTransaction { block() } }
    }
}

private fun NetworkEventRow.toEntity() = NetworkEventEntity(
    startedAtMs = startedAtMs,
    method = method,
    url = url,
    statusCode = statusCode,
    durationMs = durationMs,
    errorType = errorType?.wireName,
)
