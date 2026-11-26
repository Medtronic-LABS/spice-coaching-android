package com.medtroniclabs.microcoaching.sdk.diagnostics

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.dao.NetworkEventDao
import com.medtroniclabs.microcoaching.data.db.entity.NetworkEventEntity
import com.medtroniclabs.microcoaching.network.events.NetworkEventRecorder
import com.medtroniclabs.microcoaching.network.events.NetworkEventRow
import com.medtroniclabs.microcoaching.network.events.NetworkEventStore
import com.medtroniclabs.microcoaching.network.events.NetworkEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec §7h: `clearNetworkEvents` on user switch, against a real Room store. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClearNetworkEventsTest {

    private lateinit var fixture: RoomNetworkEventFixture
    private lateinit var server: MockWebServer
    private val reader = NetworkEventsReader({ true }, NetworkEventRecorder)

    @Before
    fun setUp() {
        NetworkEventRecorder.resetForTesting()
        fixture = RoomNetworkEventFixture()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        NetworkEventRecorder.resetForTesting()
        fixture.close()
    }

    /** An instrumented client; [beforeProceed] runs after the request has started being recorded. */
    private fun client(beforeProceed: () -> Unit = {}): OkHttpClient =
        NetworkEvents.instrument(OkHttpClient.Builder())
            .addInterceptor { chain ->
                beforeProceed()
                chain.proceed(chain.request())
            }
            .build()

    private fun call(client: OkHttpClient) {
        server.enqueue(MockResponse().setResponseCode(204))
        client.newCall(Request.Builder().url(server.url("/sync")).build()).execute().close()
    }

    private fun row(i: Int) = NetworkEventRow(
        startedAtMs = 1_700_000_000_000L + i,
        method = "POST",
        url = "https://example.test/sync",
        statusCode = 200,
        durationMs = 3,
        errorType = null,
        epoch = NetworkEventRecorder.epoch,
    )

    @Test
    fun clear_returnsTheClearedThroughIdAndEmptiesTheStore() = runBlocking {
        fixture.bind()
        fixture.seed(5)

        val result = reader.clear()

        assertEquals(NetworkEventsClearResult(NetworkEventsStatus.OK, 5), result)
        assertEquals(emptyList<NetworkEventEntity>(), fixture.dao.after(0, 500))
        val after = reader.read(afterId = 0, limit = 10)
        assertEquals(NetworkEventsStatus.OK, after.status)
        assertEquals(0L, after.maxId)
    }

    @Test
    fun aStaleQueuedEventIsNotWrittenAndANewEventGetsAHigherId() = runTest {
        val gate = CompletableDeferred<Unit>()
        val real = fixture.dao
        val gated = object : NetworkEventDao by real {
            override suspend fun insertAndPurge(rows: List<NetworkEventEntity>, keep: Int) {
                gate.await()
                real.insertAndPurge(rows, keep)
            }
        }
        fixture.bind(StandardTestDispatcher(testScheduler), store = {
            NetworkEventStore(gated) { block -> fixture.db.withTransaction { block() } }
        })
        NetworkEventRecorder.offer(row(1))
        advanceUntilIdle() // row 1 is in flight and holds the write lock
        NetworkEventRecorder.offer(row(2)) // queued under the old epoch

        var cleared: NetworkEventsClearResult? = null
        launch { cleared = reader.clear() }
        advanceUntilIdle() // the epoch is bumped; clear waits for the lock
        gate.complete(Unit)
        advanceUntilIdle() // row 1 commits, clear deletes it, row 2 is stale

        assertEquals(NetworkEventsClearResult(NetworkEventsStatus.OK, 1), cleared)
        assertEquals(emptyList<NetworkEventEntity>(), fixture.dao.after(0, 500))

        NetworkEventRecorder.offer(row(3))
        advanceUntilIdle()

        val stored = fixture.dao.after(0, 500)
        assertEquals(listOf(1_700_000_000_003L), stored.map { it.startedAtMs })
        assertTrue(stored.single().id > cleared!!.clearedThroughId)
    }

    @Test
    fun aCallThatStartedBeforeAClearIsNotStoredAfterIt() = runBlocking {
        fixture.bind()
        var cleared: NetworkEventsClearResult? = null

        call(client(beforeProceed = { runBlocking { cleared = reader.clear() } }))

        assertEquals(NetworkEventsClearResult(NetworkEventsStatus.OK, 0), cleared)
        assertEquals(emptyList<NetworkEventEntity>(), fixture.dao.after(0, 500))
    }

    @Test
    fun aCallThatStartsAfterAClearIsStored() = runBlocking {
        fixture.bind()
        fixture.seed(2)
        assertEquals(NetworkEventsClearResult(NetworkEventsStatus.OK, 2), reader.clear())

        call(client())

        val stored = fixture.dao.after(0, 500).single()
        assertEquals(204, stored.statusCode)
        assertTrue(stored.id > 2)
    }

    @Test
    fun callsBeforeBindAreDroppedAndCountedThenRecordedOnceBound() = runBlocking {
        val client = client()

        call(client)
        assertEquals(1L, NetworkEventRecorder.droppedCount)

        fixture.bind()
        call(client)

        assertEquals(listOf(204), fixture.dao.after(0, 500).map { it.statusCode })
        assertEquals(1L, NetworkEventRecorder.droppedCount)
    }

    @Test
    fun clear_worksWhenTheSdkIsNotInitializedButTheStoreIsBound() = runBlocking {
        fixture.bind()
        fixture.seed(2)

        val result = NetworkEventsReader({ false }, NetworkEventRecorder).clear()

        assertEquals(NetworkEventsClearResult(NetworkEventsStatus.OK, 2), result)
        assertEquals(emptyList<NetworkEventEntity>(), fixture.dao.after(0, 500))
    }

    @Test
    fun clear_onAnUnboundStoreIsNotInitialized() = runBlocking {
        assertEquals(NetworkEventsClearResult(NetworkEventsStatus.NOT_INITIALIZED, 0), reader.clear())
    }

    @Test
    fun clear_storeErrorIsStoreErrorAndNeverThrows() = runBlocking {
        val failures = ArrayDeque(listOf(SQLiteException("disk"), IllegalStateException("closed"), RuntimeException("odd"), NoClassDefFoundError("sqlite")))
        fixture.bind(store = { NetworkEventStore(fixture.dao) { _ -> throw failures.removeFirst() } })

        repeat(4) {
            assertEquals(NetworkEventsClearResult(NetworkEventsStatus.STORE_ERROR, 0), reader.clear())
        }
    }

    @Test
    fun companionDelegates_whenNothingIsBoundOrBuilt() = runBlocking {
        assertFalse(MicroCoachingSDK.isInitialized())

        assertEquals(NetworkEventsStatus.NOT_INITIALIZED, MicroCoachingSDK.getNetworkEvents(0, 10).status)
        assertEquals(NetworkEventsStatus.NOT_INITIALIZED, MicroCoachingSDK.clearNetworkEvents().status)
    }

    @Test
    fun companionClear_usesTheBoundStoreWithoutAnSdk() = runBlocking {
        fixture.bind()
        fixture.seed(3)

        assertEquals(NetworkEventsClearResult(NetworkEventsStatus.OK, 3), MicroCoachingSDK.clearNetworkEvents())
    }
}
