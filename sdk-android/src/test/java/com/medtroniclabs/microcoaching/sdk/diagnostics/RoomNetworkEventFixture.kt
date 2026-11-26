package com.medtroniclabs.microcoaching.sdk.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.dao.NetworkEventDao
import com.medtroniclabs.microcoaching.data.db.entity.NetworkEventEntity
import com.medtroniclabs.microcoaching.network.events.NetworkEventRecorder
import com.medtroniclabs.microcoaching.network.events.NetworkEventStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * An in-memory Room database whose executors run inline, so every store call
 * completes on the calling thread and the tests stay deterministic, plus the
 * helpers to bind [NetworkEventRecorder] to it.
 */
internal class RoomNetworkEventFixture {

    val db: MicroCoachingDatabase = Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MicroCoachingDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(Runnable::run)
        .setTransactionExecutor(Runnable::run)
        .build()

    val dao: NetworkEventDao get() = db.networkEventDao()

    fun bind(
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        store: () -> NetworkEventStore = { NetworkEventStore.of(db) },
    ) = NetworkEventRecorder.bindForTesting(dispatcher, store) {}

    suspend fun seed(count: Int, errorEvery: Int = 0) = dao.insertAll(
        (1..count).map { i ->
            val failed = errorEvery > 0 && i % errorEvery == 0
            NetworkEventEntity(
                startedAtMs = 1_700_000_000_000L + i,
                method = "GET",
                url = "https://example.test/items/{id}",
                statusCode = if (failed) null else 200,
                durationMs = i.toLong(),
                errorType = if (failed) "receiveTimeout" else null,
            )
        },
    )

    fun close() = db.close()
}
