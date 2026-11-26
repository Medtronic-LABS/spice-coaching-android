package com.medtroniclabs.microcoaching.sdk.diagnostics

import android.database.sqlite.SQLiteException
import com.medtroniclabs.microcoaching.network.events.NetworkEventRecorder
import com.medtroniclabs.microcoaching.network.events.NetworkEventStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec §7e / §7g: the `getNetworkEvents` read path and the contract v1 map. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkEventsReaderTest {

    private lateinit var fixture: RoomNetworkEventFixture
    private var sdkInitialized = true
    private val reader = NetworkEventsReader({ sdkInitialized }, NetworkEventRecorder)

    @Before
    fun setUp() {
        NetworkEventRecorder.resetForTesting()
        fixture = RoomNetworkEventFixture()
    }

    @After
    fun tearDown() {
        NetworkEventRecorder.resetForTesting()
        fixture.close()
    }

    private fun assertNotInitialized(result: NetworkEventsResult) {
        assertEquals(NetworkEventsStatus.NOT_INITIALIZED, result.status)
        assertEquals(emptyList<NetworkEvent>(), result.events)
        assertEquals(0L, result.maxId)
    }

    @Test
    fun sdkNotInitialized_isNotInitializedEvenWithStoredRows() = runBlocking {
        fixture.bind()
        fixture.seed(3)
        sdkInitialized = false

        assertNotInitialized(reader.read(afterId = 0, limit = 10))
    }

    @Test
    fun unboundStore_isNotInitialized() = runBlocking {
        assertNotInitialized(reader.read(afterId = 0, limit = 10))
    }

    @Test
    fun emptyStore_isOkWithNoEventsAndMaxIdZero() = runBlocking {
        fixture.bind()

        val result = reader.read(afterId = 0, limit = 10)

        assertEquals(NetworkEventsStatus.OK, result.status)
        assertEquals(emptyList<NetworkEvent>(), result.events)
        assertEquals(0L, result.maxId)
    }

    @Test
    fun rowsAreMappedToPublicEvents() = runBlocking {
        fixture.bind()
        fixture.seed(2, errorEvery = 2)

        val events = reader.read(afterId = 0, limit = 10).events

        assertEquals(
            listOf(
                NetworkEvent(1, 1_700_000_000_001L, "GET", "https://example.test/items/{id}", 200, 1, null),
                NetworkEvent(2, 1_700_000_000_002L, "GET", "https://example.test/items/{id}", null, 2, "receiveTimeout"),
            ),
            events,
        )
    }

    @Test
    fun negativeAfterId_isTreatedAsZero() = runBlocking {
        fixture.bind()
        fixture.seed(3)

        val result = reader.read(afterId = -5, limit = 10)

        assertEquals(listOf(1L, 2L, 3L), result.events.map { it.id })
        assertEquals(3L, result.maxId)
    }

    @Test
    fun limitIsClampedToOneThroughFiveHundred() = runBlocking {
        fixture.bind()
        fixture.seed(600)

        assertEquals(1, reader.read(afterId = 0, limit = 0).events.size)
        assertEquals(1, reader.read(afterId = 0, limit = -3).events.size)
        val capped = reader.read(afterId = 0, limit = 10_000)
        assertEquals(500, capped.events.size)
        assertEquals(600L, capped.maxId)
    }

    @Test
    fun afterIdBeyondMax_isEmptyWithTheRealMaxId() = runBlocking {
        fixture.bind()
        fixture.seed(4)

        val result = reader.read(afterId = 99, limit = 10)

        assertEquals(NetworkEventsStatus.OK, result.status)
        assertEquals(emptyList<NetworkEvent>(), result.events)
        assertEquals(4L, result.maxId)
    }

    @Test
    fun fiveHundredRows_pageInThreeCalls() = runBlocking {
        fixture.bind()
        fixture.seed(500)

        val pages = mutableListOf<NetworkEventsResult>()
        var cursor = 0L
        repeat(3) {
            val page = reader.read(afterId = cursor, limit = 200)
            pages += page
            cursor = page.events.last().id
        }

        assertEquals(listOf(200, 200, 100), pages.map { it.events.size })
        assertEquals((1L..500L).toList(), pages.flatMap { page -> page.events.map { it.id } })
        assertTrue(pages.all { it.maxId == 500L && it.status == NetworkEventsStatus.OK })
        assertEquals(emptyList<NetworkEvent>(), reader.read(afterId = cursor, limit = 200).events)
    }

    @Test
    fun storeErrors_areStoreErrorAndNeverThrow() = runBlocking {
        val failures = ArrayDeque(
            listOf(
                SQLiteException("disk"),
                IllegalStateException("closed"),
                IllegalArgumentException("odd"),
                NoClassDefFoundError("sqlite"),
            ),
        )
        fixture.bind(store = { NetworkEventStore(fixture.dao) { _ -> throw failures.removeFirst() } })

        repeat(4) {
            val result = reader.read(afterId = 0, limit = 10)
            assertEquals(NetworkEventsStatus.STORE_ERROR, result.status)
            assertEquals(emptyList<NetworkEvent>(), result.events)
            assertEquals(0L, result.maxId)
        }
    }

    @Test
    fun aCancellationExceptionFromTheStoreWhileActive_isStoreError() = runBlocking {
        fixture.bind(store = { NetworkEventStore(fixture.dao) { _ -> throw CancellationException("store") } })

        assertEquals(NetworkEventsStatus.STORE_ERROR, reader.read(afterId = 0, limit = 10).status)
    }

    @Test
    fun toContractMap_matchesTheV1Schema() = runBlocking {
        fixture.bind()
        fixture.seed(2, errorEvery = 2)

        val map = reader.read(afterId = 0, limit = 10).toContractMap()

        assertEquals(setOf("status", "sdk_event_version", "max_id", "events"), map.keys)
        assertEquals("ok", map["status"])
        assertEquals(1, map["sdk_event_version"])
        assertTrue(map["sdk_event_version"] is Int)
        assertTrue(map["max_id"] is Long)
        assertEquals(2L, map["max_id"])
        val events = map["events"] as List<*>
        assertEquals(2, events.size)
        val keys = setOf("id", "started_at_ms", "method", "url", "status_code", "duration_ms", "error_type")
        for (raw in events) {
            val event = raw as Map<*, *>
            assertEquals(keys, event.keys)
            assertTrue(event["id"] is Long)
            assertTrue(event["started_at_ms"] is Long)
            assertTrue(event["method"] is String)
            assertTrue((event["url"] as String).length <= 2048)
            assertTrue(event["status_code"] == null || event["status_code"] is Int)
            assertTrue(event["error_type"] == null || event["error_type"] is String)
            assertTrue((event["duration_ms"] as Long) >= 0)
        }
        val ok = events[0] as Map<*, *>
        assertEquals(200, ok["status_code"])
        assertNull(ok["error_type"])
        val failed = events[1] as Map<*, *>
        assertTrue(failed.containsKey("status_code"))
        assertNull(failed["status_code"])
        assertEquals("receiveTimeout", failed["error_type"])
    }

    @Test
    fun toContractMap_notInitialized() = runBlocking {
        sdkInitialized = false

        val map = reader.read(afterId = 0, limit = 10).toContractMap()

        assertEquals(
            mapOf("status" to "notInitialized", "sdk_event_version" to 1, "max_id" to 0L, "events" to emptyList<Any>()),
            map,
        )
    }

    @Test
    fun sdkEventVersion_isOne() {
        assertEquals(1, NetworkEventsResult.SDK_EVENT_VERSION)
        assertEquals(1, NetworkEventsResult(NetworkEventsStatus.OK, emptyList(), 0).sdkEventVersion)
    }

    @Test
    fun statusWireNames() {
        assertEquals("ok", NetworkEventsStatus.OK.wire)
        assertEquals("notInitialized", NetworkEventsStatus.NOT_INITIALIZED.wire)
        assertNull(NetworkEventsStatus.STORE_ERROR.wire)
    }
}
