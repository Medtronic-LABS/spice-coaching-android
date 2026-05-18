package com.medtroniclabs.microcoaching.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.network.NetworkModule

/**
 * WorkManager worker that fetches updated scenarios and quiz questions from the backend.
 *
 * Calls `GET /scenarios/sync?since_version={cursor}` where the cursor is the last
 * successfully received bundle version (stored in [SyncPrefs.lastSyncVersion]).
 * The backend returns only records newer than the cursor, enabling incremental updates.
 *
 * On success: upserts rows to Room and advances the [SyncPrefs] version cursor.
 * On network failure: returns [Result.retry()]; cursor is NOT advanced, so the
 * next attempt re-fetches from the same version.
 *
 * Scheduled by [SyncCoordinator]:
 *   - Periodic: every 15 minutes when network is available
 *   - One-shot: immediately on connectivity restore (after outbound sync completes)
 */
class InboundSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!MicroCoachingSDK.isInitialized()) {
            Log.w(TAG, "SDK not initialized — retrying inbound sync later.")
            return Result.retry()
        }

        val sdk = MicroCoachingSDK.getInstance()
        val config = sdk.config

        if (config.backendUrl.isBlank()) {
            Log.d(TAG, "backendUrl not configured — skipping inbound sync.")
            return Result.success()
        }

        val db = MicroCoachingDatabase.getInstance(applicationContext)
        val syncPrefs = SyncPrefs(applicationContext)
        val apiService = NetworkModule.createApiService(config)
        val syncApi = SyncApi(
            apiService = apiService,
            db = db,
            sessionId = "inbound-sync",
            chwId = sdk.currentCHWId ?: "unknown",
        )

        // v3 sync — five independent resource pulls. Each is non-fatal so one
        // 404/500 doesn't block the others. Worker returns retry only if every
        // pull fails (network down).
        val modulesResult = syncApi.pullModules(syncPrefs.modulesWatermark)
        if (modulesResult.success) {
            modulesResult.newWatermark?.let { syncPrefs.modulesWatermark = it }
        } else {
            Log.w(TAG, "Modules sync failed (non-fatal): ${modulesResult.error}")
        }

        val gapsResult = syncApi.pullGaps(syncPrefs.gapsWatermark, chwId = sdk.currentCHWId)
        if (gapsResult.success) {
            gapsResult.newWatermark?.let { syncPrefs.gapsWatermark = it }
        } else {
            Log.w(TAG, "Gaps sync failed (non-fatal): ${gapsResult.error}")
        }

        val triggersResult = syncApi.pullTriggers(syncPrefs.triggersWatermark)
        if (triggersResult.success) {
            triggersResult.newWatermark?.let { syncPrefs.triggersWatermark = it }
        } else {
            Log.w(TAG, "Triggers sync failed (non-fatal): ${triggersResult.error}")
        }

        val configResult = syncApi.pullConfig()
        if (configResult.success) {
            configResult.newWatermark?.let { syncPrefs.configWatermark = it }
        } else {
            Log.w(TAG, "Config sync failed (non-fatal): ${configResult.error}")
        }

        val allFailed = !modulesResult.success && !gapsResult.success &&
            !triggersResult.success && !configResult.success
        if (allFailed) {
            Log.w(TAG, "All v3 sync pulls failed — retrying.")
            return Result.retry()
        }

        // Morning cards — non-fatal; on failure the previous cache stays intact.
        val morningResult = syncApi.pullMorningCards(
            chwId = sdk.currentCHWId,
            tenantId = config.tenantId.takeIf { it.isNotBlank() },
        )
        if (!morningResult.success) {
            Log.w(TAG, "Morning cards sync failed (non-fatal): ${morningResult.error}")
        }

        syncPrefs.lastInboundSyncAt = System.currentTimeMillis()
        Log.i(
            TAG,
            "Inbound sync complete. modules=${modulesResult.upsertedCount}, " +
                "gaps=${gapsResult.upsertedCount}, " +
                "triggers=${triggersResult.triggerCount} bindings=${triggersResult.bindingCount}, " +
                "config=${configResult.upsertedCount}, morningCards=${morningResult.count}.",
        )
        return Result.success()
    }

    companion object {
        const val TAG = "InboundSyncWorker"
        const val WORK_NAME = "micro_coaching_inbound_sync"
    }
}
