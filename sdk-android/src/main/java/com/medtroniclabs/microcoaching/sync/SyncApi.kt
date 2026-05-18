package com.medtroniclabs.microcoaching.sync

import android.util.Log
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity
import com.medtroniclabs.microcoaching.data.mapper.toConfigEntities
import com.medtroniclabs.microcoaching.data.mapper.toEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.network.SyncDefaults
import com.medtroniclabs.microcoaching.network.TelemetryBatch
import java.util.UUID

/**
 * Domain-level sync gateway used by [OutboundSyncWorker] and [InboundSyncWorker].
 *
 * Responsibilities:
 * - Read pending entities from Room and map them to API payloads ([SyncPayloadMapper])
 * - Call [CoachingApiService] and interpret the HTTP response
 * - Write sync-state updates back to Room on success or failure
 * - Record a `sync_attempt` [DigitalProficiencyEventEntity] for every push attempt (SDK-032)
 *
 * Mapping logic lives in [com.medtroniclabs.microcoaching.data.mapper]:
 *   Entity → Payload  :  SyncPayloadMapper.kt  (outbound)
 *   DTO    → Entity   :  ScenarioBundleMapper.kt (inbound)
 *
 * All functions are suspend — call from an IO dispatcher.
 */
class SyncApi(
    private val apiService: CoachingApiService,
    private val db: MicroCoachingDatabase,
    private val sessionId: String,
    private val chwId: String,
    private val sdkVersion: String = BuildConfig.SDK_VERSION,
) {

    // ── Outbound ──────────────────────────────────────────────────────────────

    /**
     * Push all pending coaching events, LLM traces, and digital events to the backend.
     *
     * On success: marks all sent rows as `synced` in Room.
     * On failure: increments retry counts; rows stay `pending` for the next attempt.
     * Always records a `sync_attempt` event regardless of outcome.
     *
     * @return [OutboundResult] with counts and any failure reason.
     */
    suspend fun pushPendingEvents(): OutboundResult {
        // chw_id is passed through verbatim — backend accepts whatever
        // identifier shape the host supplies (SPICE forwards its own integer
        // user id today; backend has been relaxed to handle non-UUID values).
        val events = db.coachingEventDao().getPending()
        val traces = db.llmTraceDao().getPending()
        val digitalEvents = db.digitalProficiencyEventDao().getPending()

        if (events.isEmpty() && traces.isEmpty() && digitalEvents.isEmpty()) {
            Log.d(TAG, "Nothing pending — skip outbound sync.")
            return OutboundResult(skipped = true)
        }

        Log.i(
            TAG,
            "Pushing pending events — coaching_events=${events.size} " +
                "llm_traces=${traces.size} digital_events=${digitalEvents.size}",
        )

        val allPayloads = events.map { it.toPayload() } +
            traces.map { it.toPayload() } +
            digitalEvents.map { it.toPayload() }

        val batch = TelemetryBatch(
            events = allPayloads,
            sdkVersion = sdkVersion,
            chwId = chwId,
        )

        return try {
            val response = apiService.pushTelemetry(batch)
            val now = System.currentTimeMillis()

            if (response.isSuccessful) {
                val body = response.body()!!
                val acceptedIds = body.accepted.toSet()
                val rejectedIds = body.rejected.toSet()

                val syncedEventIds   = events.map { it.eventId }.filter { it in acceptedIds }
                val syncedTraceIds   = traces.map { it.id }.filter { it in acceptedIds }
                val syncedDigitalIds = digitalEvents.map { it.id }.filter { it in acceptedIds }

                if (syncedEventIds.isNotEmpty())   db.coachingEventDao().markSynced(syncedEventIds, now)
                if (syncedTraceIds.isNotEmpty())    db.llmTraceDao().markSynced(syncedTraceIds, now)
                if (syncedDigitalIds.isNotEmpty())  db.digitalProficiencyEventDao().markSynced(syncedDigitalIds, now)

                if (rejectedIds.isNotEmpty()) {
                    db.coachingEventDao().incrementRetryCount(events.map { it.eventId }.filter { it in rejectedIds })
                    db.llmTraceDao().incrementRetryCount(traces.map { it.id }.filter { it in rejectedIds })
                    db.digitalProficiencyEventDao().incrementRetryCount(digitalEvents.map { it.id }.filter { it in rejectedIds })
                }

                recordSyncAttempt(success = true, networkState = "online")
                Log.i(
                    TAG,
                    "Outbound sync OK — sent: coaching_events=${events.size} " +
                        "llm_traces=${traces.size} digital_events=${digitalEvents.size} | " +
                        "accepted=${acceptedIds.size} rejected=${rejectedIds.size}",
                )
                OutboundResult(syncedCount = acceptedIds.size, failedCount = rejectedIds.size)
            } else {
                val errorMsg = "HTTP ${response.code()}"
                recordSyncAttempt(success = false, errorType = errorMsg, networkState = "online")
                Log.w(TAG, "Outbound sync server error: $errorMsg")
                OutboundResult(error = errorMsg)
            }
        } catch (e: Exception) {
            recordSyncAttempt(success = false, errorType = e.javaClass.simpleName, networkState = "offline")
            Log.w(TAG, "Outbound sync network error: ${e.message}")
            OutboundResult(error = e.message)
        }
    }

    // ── Inbound ───────────────────────────────────────────────────────────────

    /**
     * Fetch published modules updated after [sinceWatermark]. Backend requires
     * a non-null `since` query param; the SDK supplies [SyncDefaults.EPOCH_ISO]
     * on first sync (empty cache) so the device gets the full catalogue.
     *
     * Backend only ships **published** modules through this endpoint — there
     * is no `deprecated` flag and no pruning signal. Retired modules drop
     * silently from future bundles; the device keeps its cached copy unless
     * the caller explicitly deletes it.
     */
    suspend fun pullModules(sinceWatermark: String?): ModulesResult {
        return try {
            val localCount = db.moduleDao().countActive()
            val effectiveSince = when {
                localCount == 0 -> SyncDefaults.EPOCH_ISO
                sinceWatermark.isNullOrBlank() -> SyncDefaults.EPOCH_ISO
                else -> sinceWatermark
            }
            if (effectiveSince == SyncDefaults.EPOCH_ISO && sinceWatermark != null) {
                Log.i(TAG, "Modules cache empty — requesting full bundle (ignoring stored watermark $sinceWatermark).")
            }
            val response = apiService.pullModules(since = effectiveSince)
            val now = System.currentTimeMillis()

            if (response.isSuccessful) {
                val bundle = response.body()!!
                val rows = bundle.modules.map { it.toEntity(now) }
                if (rows.isNotEmpty()) {
                    db.moduleDao().upsertAll(rows)
                    // After upsert, drop any older versions of the same family
                    // so the cache only holds the latest published version.
                    rows.map { it.moduleFamilyId }.distinct().forEach { familyId ->
                        db.moduleDao().pruneOldVersions(familyId)
                    }
                }
                Log.i(
                    TAG,
                    "Modules sync OK: upserted=${rows.size} families=${bundle.moduleFamilies.size} " +
                        "server_time=${bundle.serverTimeUtc}",
                )
                ModulesResult(
                    upsertedCount = rows.size,
                    prunedCount = 0,
                    newWatermark = bundle.serverTimeUtc,
                )
            } else {
                val errorMsg = "HTTP ${response.code()}"
                Log.w(TAG, "Modules sync server error: $errorMsg")
                ModulesResult(error = errorMsg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Modules sync network error: ${e.message}")
            ModulesResult(error = e.message)
        }
    }

    /**
     * Fetch the behavioural-gap taxonomy. When [chwId] is a valid UUID, the
     * response also includes per-CHW gap state and module-completion rows,
     * which we upsert into the local mirror so CoachingCard UI reflects
     * server-known progress on first load.
     */
    suspend fun pullGaps(sinceWatermark: String?, chwId: String? = null): GapsResult {
        return try {
            val localCount = db.behaviouralGapDao().countActive()
            val effectiveSince = if (localCount > 0) sinceWatermark else null
            if (sinceWatermark != null && effectiveSince == null) {
                Log.i(TAG, "Gaps cache empty — requesting full bundle (ignoring stored watermark $sinceWatermark).")
            }
            val response = apiService.pullGaps(
                since = effectiveSince,
                chwId = chwId,
            )
            val now = System.currentTimeMillis()

            if (response.isSuccessful) {
                val bundle = response.body()!!
                val gapRows = bundle.behaviouralGaps.map { it.toEntity(now) }
                if (gapRows.isNotEmpty()) db.behaviouralGapDao().upsertAll(gapRows)

                // Resolve domain by gap_id so we can stamp ChwGapProfile with it.
                val domainByGapId = gapRows.associate { it.gapId to (it.domain ?: "unknown") }
                bundle.chwBehaviouralGapStates.forEach { state ->
                    val domain = domainByGapId[state.behaviouralGapId] ?: "unknown"
                    db.chwGapProfileDao().upsert(state.toEntity(domain))
                }
                bundle.chwModuleCompletions.forEach { completion ->
                    db.chwModuleCompletionDao().upsert(completion.toEntity())
                }

                Log.i(
                    TAG,
                    "Gaps sync OK: gaps=${gapRows.size} states=${bundle.chwBehaviouralGapStates.size} " +
                        "completions=${bundle.chwModuleCompletions.size} server_time=${bundle.serverTimeUtc}",
                )
                GapsResult(
                    upsertedCount = gapRows.size,
                    prunedCount = 0,
                    newWatermark = bundle.serverTimeUtc,
                )
            } else {
                val errorMsg = "HTTP ${response.code()}"
                Log.w(TAG, "Gaps sync server error: $errorMsg")
                GapsResult(error = errorMsg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gaps sync network error: ${e.message}")
            GapsResult(error = e.message)
        }
    }

    /**
     * Fetch trigger definitions + module-trigger bindings updated since
     * [sinceWatermark]. Backend requires non-null `since`; first sync uses
     * [SyncDefaults.EPOCH_ISO].
     */
    suspend fun pullTriggers(sinceWatermark: String?): TriggersResult {
        return try {
            val localCount = db.triggerDefinitionDao().countActive()
            val effectiveSince = when {
                localCount == 0 -> SyncDefaults.EPOCH_ISO
                sinceWatermark.isNullOrBlank() -> SyncDefaults.EPOCH_ISO
                else -> sinceWatermark
            }
            if (effectiveSince == SyncDefaults.EPOCH_ISO && sinceWatermark != null) {
                Log.i(TAG, "Triggers cache empty — requesting full bundle (ignoring stored watermark $sinceWatermark).")
            }
            val response = apiService.pullTriggers(since = effectiveSince)
            val now = System.currentTimeMillis()

            if (response.isSuccessful) {
                val bundle = response.body()!!
                val activeTriggers = bundle.triggers.filter { it.status != "deprecated" }.map { it.toEntity(now) }
                val deprecatedTriggerIds = bundle.triggers.filter { it.status == "deprecated" }.map { it.id }
                val bindings = bundle.bindings.map { it.toEntity(now) }

                if (activeTriggers.isNotEmpty()) db.triggerDefinitionDao().upsertAll(activeTriggers)
                if (deprecatedTriggerIds.isNotEmpty()) db.triggerDefinitionDao().deleteByIds(deprecatedTriggerIds)
                if (bindings.isNotEmpty()) db.moduleTriggerBindingDao().upsertAll(bindings)
                if (deprecatedTriggerIds.isNotEmpty()) {
                    val orphanedBindingIds = deprecatedTriggerIds.flatMap { tid ->
                        db.moduleTriggerBindingDao().getByTrigger(tid).map { it.bindingId }
                    }
                    if (orphanedBindingIds.isNotEmpty()) {
                        db.moduleTriggerBindingDao().deleteByIds(orphanedBindingIds)
                    }
                }

                Log.i(
                    TAG,
                    "Triggers sync OK: triggers=${activeTriggers.size} (pruned ${deprecatedTriggerIds.size}) " +
                        "bindings=${bindings.size} server_time=${bundle.serverTimeUtc}",
                )
                TriggersResult(
                    triggerCount = activeTriggers.size,
                    bindingCount = bindings.size,
                    prunedCount = deprecatedTriggerIds.size,
                    newWatermark = bundle.serverTimeUtc,
                )
            } else {
                val errorMsg = "HTTP ${response.code()}"
                Log.w(TAG, "Triggers sync server error: $errorMsg")
                TriggersResult(error = errorMsg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Triggers sync network error: ${e.message}")
            TriggersResult(error = e.message)
        }
    }

    /**
     * Fetch the config-threshold snapshot. Backend ships a single flat
     * `thresholds` dict — every entry is upserted as a global-scoped
     * [ConfigThresholdEntity] row.
     */
    suspend fun pullConfig(): ConfigResult {
        return try {
            val response = apiService.pullConfig()
            val now = System.currentTimeMillis()

            if (response.isSuccessful) {
                val bundle = response.body()!!
                val rows = bundle.thresholds.toConfigEntities(now)
                if (rows.isNotEmpty()) db.configThresholdDao().upsertAll(rows)
                Log.i(
                    TAG,
                    "Config sync OK: upserted=${rows.size} server_time=${bundle.serverTimeUtc}",
                )
                ConfigResult(
                    upsertedCount = rows.size,
                    newWatermark = bundle.serverTimeUtc,
                )
            } else {
                val errorMsg = "HTTP ${response.code()}"
                Log.w(TAG, "Config sync server error: $errorMsg")
                ConfigResult(error = errorMsg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Config sync network error: ${e.message}")
            ConfigResult(error = e.message)
        }
    }


    /**
     * Fetch the backend-prioritised morning-module list and atomically replace
     * the local [morning_card_cache] table. On network failure the previous
     * cache is left intact so the device can still surface a ranked list.
     *
     * @param chwId Integer CHW id as a string — forwarded as-is (backend accepts
     *   non-UUID values). Null to get recently-added modules without gap
     *   personalisation.
     * @param tenantId Optional tenant UUID filter.
     */
    suspend fun pullMorningCards(chwId: String?, tenantId: String?): MorningCardsResult {
        return try {
            val response = apiService.getMorningCards(chwId = chwId, tenantId = tenantId)
            if (response.isSuccessful) {
                val body = response.body()!!
                val now = System.currentTimeMillis()
                val entities = body.items.mapIndexed { idx, item ->
                    MorningCardCacheEntity(
                        moduleId = item.moduleId,
                        moduleFamilyId = item.moduleFamilyId,
                        source = item.source,
                        behaviouralGapId = item.behaviouralGapId,
                        rank = idx,
                        fetchedAt = now,
                    )
                }
                db.morningCardCacheDao().clearAll()
                if (entities.isNotEmpty()) db.morningCardCacheDao().upsertAll(entities)
                Log.i(TAG, "Morning cards sync OK: items=${entities.size} gap=${entities.count { it.source == "gap" }}")
                MorningCardsResult(count = entities.size)
            } else {
                val errorMsg = "HTTP ${response.code()}"
                Log.w(TAG, "Morning cards sync server error: $errorMsg")
                MorningCardsResult(error = errorMsg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Morning cards sync network error: ${e.message}")
            MorningCardsResult(error = e.message)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun recordSyncAttempt(
        success: Boolean,
        errorType: String? = null,
        networkState: String? = null,
    ) {
        try {
            db.digitalProficiencyEventDao().insert(
                DigitalProficiencyEventEntity(
                    id = UUID.randomUUID().toString(),
                    sdkVersion = sdkVersion,
                    sessionId = sessionId,
                    chwId = chwId,
                    eventType = "sync_attempt",
                    success = success,
                    errorType = errorType,
                    networkState = networkState,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record sync_attempt event: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SyncApi"
    }
}

// ── Result types ──────────────────────────────────────────────────────────────

data class OutboundResult(
    val syncedCount: Int = 0,
    val failedCount: Int = 0,
    val skipped: Boolean = false,
    val error: String? = null,
) {
    val success get() = error == null && !skipped
}

data class ModulesResult(
    val upsertedCount: Int = 0,
    val prunedCount: Int = 0,
    val newWatermark: String? = null,
    val error: String? = null,
) {
    val success get() = error == null
}

data class GapsResult(
    val upsertedCount: Int = 0,
    val prunedCount: Int = 0,
    val newWatermark: String? = null,
    val error: String? = null,
) {
    val success get() = error == null
}

data class TriggersResult(
    val triggerCount: Int = 0,
    val bindingCount: Int = 0,
    val prunedCount: Int = 0,
    val newWatermark: String? = null,
    val error: String? = null,
) {
    val success get() = error == null
}

data class ConfigResult(
    val upsertedCount: Int = 0,
    val newWatermark: String? = null,
    val error: String? = null,
) {
    val success get() = error == null
}

data class MorningCardsResult(
    val count: Int = 0,
    val error: String? = null,
) {
    val success get() = error == null
}
