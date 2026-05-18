package com.medtroniclabs.microcoaching.sync

import android.content.Context

/**
 * Lightweight SharedPreferences wrapper for sync state.
 *
 * Stores ISO 8601 watermarks for each v3 sync resource so [InboundSyncWorker]
 * fetches only the delta on subsequent calls. Not part of the Room schema —
 * sync metadata, not content.
 */
class SyncPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Epoch millis of the last successful outbound sync. 0 = never synced. */
    var lastOutboundSyncAt: Long
        get() = prefs.getLong(KEY_LAST_OUTBOUND_SYNC_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_OUTBOUND_SYNC_AT, value).apply()

    /** Epoch millis of the last successful inbound sync. 0 = never synced. */
    var lastInboundSyncAt: Long
        get() = prefs.getLong(KEY_LAST_INBOUND_SYNC_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_INBOUND_SYNC_AT, value).apply()

    /**
     * ISO 8601 server watermark from the last `GET /sync/modules` response.
     * Forwarded as the `since` query param on the next call so the backend
     * returns only modules updated after this point.
     * `null` (the default) on first sync triggers a full bundle.
     */
    var modulesWatermark: String?
        get() = prefs.getString(KEY_MODULES_WATERMARK, null)
        set(value) = prefs.edit().putString(KEY_MODULES_WATERMARK, value).apply()

    /**
     * ISO 8601 server watermark from the last `GET /sync/gaps` response.
     * Same semantics as [modulesWatermark].
     */
    var gapsWatermark: String?
        get() = prefs.getString(KEY_GAPS_WATERMARK, null)
        set(value) = prefs.edit().putString(KEY_GAPS_WATERMARK, value).apply()

    /** ISO 8601 server watermark from the last `GET /sync/triggers` response. */
    var triggersWatermark: String?
        get() = prefs.getString(KEY_TRIGGERS_WATERMARK, null)
        set(value) = prefs.edit().putString(KEY_TRIGGERS_WATERMARK, value).apply()

    /** ISO 8601 server watermark from the last `GET /sync/config` response. */
    var configWatermark: String?
        get() = prefs.getString(KEY_CONFIG_WATERMARK, null)
        set(value) = prefs.edit().putString(KEY_CONFIG_WATERMARK, value).apply()

    // ── Config thresholds (formerly delivered via ScenarioSyncBundle; now defaults
    //    until the SDK consumes /config/sync — see Phase 3+ in
    //    docs/spice-2.0/04-integration-timeline.md). ──

    /** Max number of morning briefing cards to show. Default: 5. */
    var morningCardsMax: Int
        get() = prefs.getInt(KEY_MORNING_CARDS_MAX, 5)
        set(value) = prefs.edit().putInt(KEY_MORNING_CARDS_MAX, value).apply()

    /** Correct-answer count before a gap scenario is resolved. Default: 3. */
    var gapResolveThreshold: Int
        get() = prefs.getInt(KEY_GAP_RESOLVE_THRESHOLD, 3)
        set(value) = prefs.edit().putInt(KEY_GAP_RESOLVE_THRESHOLD, value).apply()

    /** Wrong-answer count before a soft-trigger fires. Default: 2. */
    var softTriggerWrongCountThreshold: Int
        get() = prefs.getInt(KEY_SOFT_TRIGGER_WRONG_COUNT, 2)
        set(value) = prefs.edit().putInt(KEY_SOFT_TRIGGER_WRONG_COUNT, value).apply()

    fun reset() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_NAME = "micro_coaching_sync"
        private const val KEY_LAST_OUTBOUND_SYNC_AT = "last_outbound_sync_at"
        private const val KEY_LAST_INBOUND_SYNC_AT = "last_inbound_sync_at"
        private const val KEY_MODULES_WATERMARK = "modules_watermark"
        private const val KEY_GAPS_WATERMARK = "gaps_watermark"
        private const val KEY_TRIGGERS_WATERMARK = "triggers_watermark"
        private const val KEY_CONFIG_WATERMARK = "config_watermark"
        private const val KEY_MORNING_CARDS_MAX = "morning_cards_max"
        private const val KEY_GAP_RESOLVE_THRESHOLD = "gap_resolve_threshold"
        private const val KEY_SOFT_TRIGGER_WRONG_COUNT = "soft_trigger_wrong_count_threshold"
    }
}
