package com.medtroniclabs.microcoaching.network.events

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.medtroniclabs.microcoaching.data.db.dao.NetworkEventDao
import com.medtroniclabs.microcoaching.data.db.entity.NetworkEventEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Spec §7d / §10 `NetworkEventRecorderTest`: the bounded single writer, driven by
 * a [StandardTestDispatcher] so the consumer only runs when the test advances it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkEventRecorderTest {

    private val dao = FakeDao()
    private val logged: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var opens = 0

    @Before
    fun setUp() = NetworkEventRecorder.resetForTesting()

    @After
    fun tearDown() = NetworkEventRecorder.resetForTesting()

    private fun TestScope.bindFake() {
        NetworkEventRecorder.bindForTesting(
            dispatcher = StandardTestDispatcher(testScheduler),
            openStore = {
                opens++
                NetworkEventStore(dao) { block -> block() }
            },
            log = { logged += it },
        )
    }

    /** A row whose request started now, under the current epoch. */
    private fun row(i: Int, error: NetworkErrorType? = null) = NetworkEventRow(
        startedAtMs = 1_000L + i,
        method = "GET",
        url = "https://example.test/$i",
        statusCode = if (error == null) 200 else null,
        durationMs = 5,
        errorType = error,
        epoch = NetworkEventRecorder.epoch,
    )

    @Test
    fun batchesHoldAtMost64Rows() = runTest {
        bindFake()
        repeat(150) { NetworkEventRecorder.offer(row(it)) }

        advanceUntilIdle()

        assertEquals(listOf(64, 64, 22), dao.batchSizes)
        assertEquals((0 until 150).map { 1_000L + it }, dao.rows.map { it.startedAtMs })
        assertEquals(0L, NetworkEventRecorder.droppedCount)
    }

    @Test
    fun rowsAreMappedWithTheErrorWireName() = runTest {
        bindFake()
        NetworkEventRecorder.offer(row(1, NetworkErrorType.RECEIVE_TIMEOUT))

        advanceUntilIdle()

        val stored = dao.rows.single()
        assertEquals(1_001L, stored.startedAtMs)
        assertEquals("GET", stored.method)
        assertEquals("https://example.test/1", stored.url)
        assertNull(stored.statusCode)
        assertEquals(5L, stored.durationMs)
        assertEquals("receiveTimeout", stored.errorType)
    }

    @Test
    fun twoHundredParallelOffersGiveTwoHundredRows() = runTest {
        bindFake()
        val gate = CountDownLatch(1)
        val workers = (0 until 8).map { t ->
            thread {
                gate.await()
                repeat(25) { NetworkEventRecorder.offer(row(t * 25 + it)) }
            }
        }
        gate.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

        advanceUntilIdle()

        assertEquals(200, dao.rows.size)
        assertEquals((0 until 200).map { 1_000L + it }.toSet(), dao.rows.map { it.startedAtMs }.toSet())
        assertEquals(0L, NetworkEventRecorder.droppedCount)
    }

    @Test
    fun aBlockedDaoNeverBlocksOffersAndOverflowIsCounted() = runTest {
        bindFake()
        val release = CompletableDeferred<Unit>()
        dao.insertGate = release
        NetworkEventRecorder.offer(row(0))
        advanceUntilIdle() // the writer now holds row 0 inside a blocked insert

        val started = System.nanoTime()
        repeat(500) { NetworkEventRecorder.offer(row(it + 1)) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue("500 offers took $elapsedMs ms", elapsedMs < 2_000)
        assertEquals(500L - 256L, NetworkEventRecorder.droppedCount)

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(1 + 256, dao.rows.size)
        // DROP_OLDEST keeps the newest queued rows.
        assertEquals(1_000L + 500, dao.rows.last().startedAtMs)
    }

    @Test
    fun storeFailuresAreSwallowedCountedAndLoggedOncePerType() = runTest {
        bindFake()
        dao.failures += SQLiteException("disk I/O secret detail")
        dao.failures += IllegalStateException("closed")
        dao.failures += SQLiteException("again")

        for (i in 0 until 4) {
            NetworkEventRecorder.offer(row(i))
            advanceUntilIdle()
        }

        assertEquals(listOf(1_003L), dao.rows.map { it.startedAtMs })
        assertEquals(3L, NetworkEventRecorder.droppedCount)
        assertEquals(listOf("SQLiteException", "IllegalStateException"), logged)
        assertFalse(logged.any { it.contains("secret") })
    }

    @Test
    fun cancellingTheWriterMidWriteIsNotTreatedAsAStoreFailure() = runTest {
        bindFake()
        dao.insertGate = CompletableDeferred()
        NetworkEventRecorder.offer(row(0))
        advanceUntilIdle() // in flight, suspended in insertAndPurge

        NetworkEventRecorder.resetForTesting() // cancels the writer scope
        advanceUntilIdle()

        assertTrue(logged.isEmpty())
        assertEquals(0L, NetworkEventRecorder.droppedCount)
    }

    @Test
    fun anUnexpectedStoreExceptionDoesNotStopTheWriter() = runTest {
        bindFake()
        dao.failures += IllegalArgumentException("unexpected")

        NetworkEventRecorder.offer(row(0))
        advanceUntilIdle()
        NetworkEventRecorder.offer(row(1))
        advanceUntilIdle()

        assertEquals(listOf(1_001L), dao.rows.map { it.startedAtMs })
        assertEquals(1L, NetworkEventRecorder.droppedCount)
        assertEquals(listOf("IllegalArgumentException"), logged)
    }

    @Test
    fun aCancellationExceptionFromTheStoreWhileActiveDoesNotStopTheWriter() = runTest {
        bindFake()
        dao.failures += CancellationException("store-side cancel")

        NetworkEventRecorder.offer(row(0))
        advanceUntilIdle()
        NetworkEventRecorder.offer(row(1))
        advanceUntilIdle()

        assertEquals(listOf(1_001L), dao.rows.map { it.startedAtMs })
        assertEquals(1L, NetworkEventRecorder.droppedCount)
    }

    @Test
    fun aFailingStoreOpenIsSwallowedAndRetried() = runTest {
        var attempts = 0
        NetworkEventRecorder.bindForTesting(
            dispatcher = StandardTestDispatcher(testScheduler),
            openStore = {
                if (attempts++ == 0) throw IllegalStateException("migration failed")
                NetworkEventStore(dao) { block -> block() }
            },
            log = { logged += it },
        )

        NetworkEventRecorder.offer(row(0))
        advanceUntilIdle()
        NetworkEventRecorder.offer(row(1))
        advanceUntilIdle()

        assertEquals(listOf(1_001L), dao.rows.map { it.startedAtMs })
        assertEquals(1L, NetworkEventRecorder.droppedCount)
        assertEquals(listOf("IllegalStateException"), logged)
    }

    @Test
    fun eventsQueuedBeforeClearAreNotWrittenAfterIt() = runTest {
        bindFake()
        val epochBefore = NetworkEventRecorder.epoch
        val release = CompletableDeferred<Unit>()
        dao.insertGate = release
        NetworkEventRecorder.offer(row(0))
        advanceUntilIdle() // row 0 is in flight and holds the write lock
        repeat(3) { NetworkEventRecorder.offer(row(it + 1)) } // queued, old epoch

        launch { NetworkEventRecorder.clear() }
        advanceUntilIdle() // clear has bumped the epoch and waits for the lock
        release.complete(Unit)
        advanceUntilIdle() // clear runs, then the queued batch reaches the lock after it
        NetworkEventRecorder.offer(row(10))
        advanceUntilIdle()

        assertEquals(epochBefore + 1, NetworkEventRecorder.epoch)
        assertEquals(listOf("insert", "clear", "insert"), dao.ops)
        assertEquals(listOf(1_010L), dao.rows.map { it.startedAtMs })
    }

    @Test
    fun clearWaitsForTheInFlightBatchAndReturnsTheClearedThroughId() = runTest {
        bindFake()
        val release = CompletableDeferred<Unit>()
        dao.insertGate = release
        repeat(2) { NetworkEventRecorder.offer(row(it)) }
        advanceUntilIdle() // batch of 2 is in flight, blocked inside insertAndPurge

        var clearedThrough = -1L
        launch { clearedThrough = NetworkEventRecorder.clear() }
        advanceUntilIdle()
        assertEquals(listOf("insert"), dao.ops)
        assertEquals(-1L, clearedThrough)

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("insert", "clear"), dao.ops)
        assertEquals(2L, clearedThrough)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun cancellingTheCallerWhileClearWaitsForTheLockStillDeletes() = runTest {
        bindFake()
        val release = CompletableDeferred<Unit>()
        dao.insertGate = release
        NetworkEventRecorder.offer(row(0))
        advanceUntilIdle() // row 0 is in flight and holds the write lock

        val clearing = launch { NetworkEventRecorder.clear() }
        advanceUntilIdle() // the epoch is bumped; clear waits for the lock
        clearing.cancel()
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(clearing.isCancelled)
        assertEquals(listOf("insert", "clear"), dao.ops)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun anUnboundRecorderDropsAndCounts() {
        assertNull(NetworkEventRecorder.current())

        repeat(3) { NetworkEventRecorder.offer(row(it)) }

        assertEquals(3L, NetworkEventRecorder.droppedCount)
    }

    @Test(expected = NetworkEventRecorderNotBoundException::class)
    fun clearOnAnUnboundRecorderThrowsTyped() = runTest {
        NetworkEventRecorder.clear()
    }

    @Test
    fun bindIsIdempotentLazyAndPublishesTheSink() = runTest {
        bindFake()
        var secondOpens = 0
        NetworkEventRecorder.bindForTesting(
            dispatcher = StandardTestDispatcher(testScheduler),
            openStore = {
                secondOpens++
                NetworkEventStore(FakeDao()) { block -> block() }
            },
            log = {},
        )

        assertEquals(0, opens)
        assertSame(NetworkEventRecorder, NetworkEventRecorder.current())
        assertSame(NetworkEventRecorder, NetworkEventSinkHolder.current())

        repeat(2) { NetworkEventRecorder.offer(row(it)) }
        advanceUntilIdle()

        assertEquals(1, opens)
        assertEquals(0, secondOpens)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun bindWithAContextNeverOpensTheDatabaseOnTheCallerThread() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath(DATABASE_FILE)
        dbFile.delete()
        val caller = Thread.currentThread()
        val openThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        val opened = CountDownLatch(1)
        NetworkEventRecorder.openStoreForContext = { _ ->
            openThreads += Thread.currentThread()
            opened.countDown()
            NetworkEventStore(dao) { block -> block() }
        }

        NetworkEventRecorder.bind(context)
        NetworkEventRecorder.bind(context)

        assertSame(NetworkEventRecorder, NetworkEventSinkHolder.current())
        assertTrue(openThreads.isEmpty())
        assertFalse(dbFile.exists())

        NetworkEventRecorder.offer(row(0))
        assertTrue(opened.await(10, TimeUnit.SECONDS))

        assertEquals(1, openThreads.size)
        assertTrue(openThreads.single() !== caller)
    }

    /** In-memory [NetworkEventDao] with AUTOINCREMENT-like ids, failure injection and a gate. */
    private class FakeDao : NetworkEventDao {
        val rows = Collections.synchronizedList(mutableListOf<NetworkEventEntity>())
        val batchSizes = mutableListOf<Int>()
        val ops = mutableListOf<String>()
        val failures = ArrayDeque<RuntimeException>()
        var insertGate: CompletableDeferred<Unit>? = null
        private var nextId = 1L

        override suspend fun insertAll(rows: List<NetworkEventEntity>) {
            rows.forEach { this.rows += it.copy(id = nextId++) }
        }

        override suspend fun purge(keep: Int) {
            val max = rows.maxOfOrNull { it.id } ?: return
            rows.removeAll { it.id <= max - keep }
        }

        override suspend fun insertAndPurge(rows: List<NetworkEventEntity>, keep: Int) {
            failures.removeFirstOrNull()?.let { throw it }
            ops += "insert"
            batchSizes += rows.size
            insertGate?.await()
            insertGate = null
            insertAll(rows)
            purge(keep)
        }

        override suspend fun after(afterId: Long, limit: Int): List<NetworkEventEntity> =
            rows.filter { it.id > afterId }.sortedBy { it.id }.take(limit)

        override suspend fun maxId(): Long = rows.maxOfOrNull { it.id } ?: 0L

        override suspend fun deleteAll() {
            ops += "clear"
            rows.clear()
        }
    }

    private companion object {
        const val DATABASE_FILE = "microcoaching.db"
    }
}
