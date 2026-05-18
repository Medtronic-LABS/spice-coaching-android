package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * Local cache of a published module shipped by the v3 backend `/sync/modules`
 * endpoint (see `ModuleSyncPayload` in the deployed OpenAPI spec).
 *
 * Backend only sends **published** modules through this route, so lifecycle
 * state is not modelled here. When a module is retired backend-side it stops
 * appearing in future sync responses; pruning is by absence, not by flag.
 *
 * Cards and quiz are stored as raw JSON. Cards are unstructured by spec; quiz
 * JSON is parsed into typed UI models at render time (LearnViewModel).
 */
@Entity(
    tableName = "module_cache",
    indices = [
        Index(value = ["module_family_id"]),
        Index(value = ["domain"]),
    ],
)
data class ModuleEntity(

    /** Module version UUID (backend `id`). Primary key — one row per published version. */
    @PrimaryKey
    @ColumnInfo(name = "module_id")
    val moduleId: String,

    /** Stable family identifier — survives across version bumps. */
    @ColumnInfo(name = "module_family_id")
    val moduleFamilyId: String,

    @ColumnInfo(name = "version")
    val version: Int,

    @ColumnInfo(name = "title_bn")
    val titleBn: String,

    @ColumnInfo(name = "title_en")
    val titleEn: String? = null,

    @ColumnInfo(name = "description_bn")
    val descriptionBn: String? = null,

    @ColumnInfo(name = "description_en")
    val descriptionEn: String? = null,

    @ColumnInfo(name = "domain")
    val domain: String,

    @ColumnInfo(name = "sub_domain")
    val subDomain: String? = null,

    /** "refresher" | "content_update" | "digital_proficiency". */
    @ColumnInfo(name = "module_type")
    val moduleType: String,

    @ColumnInfo(name = "tenant_id")
    val tenantId: String? = null,

    @ColumnInfo(name = "estimated_minutes")
    val estimatedMinutes: Int,

    @ColumnInfo(name = "difficulty_level")
    val difficultyLevel: String,

    /** Per-module quiz pass override (0.0–1.0). When null, SDK config default governs. */
    @ColumnInfo(name = "pass_threshold_override")
    val passThresholdOverride: Float? = null,

    @ColumnInfo(name = "clinically_reviewed")
    val clinicallyReviewed: Boolean,

    @ColumnInfo(name = "published_at_iso")
    val publishedAtIso: String? = null,

    @ColumnInfo(name = "updated_at_iso")
    val updatedAtIso: String,

    /** JSON array of card objects — opaque to Room, parsed at read time. */
    @ColumnInfo(name = "cards_json")
    val cardsJson: String = "[]",

    /** JSON array of typed quiz rows — opaque to Room, parsed at read time. */
    @ColumnInfo(name = "quiz_json")
    val quizJson: String = "[]",

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long = System.currentTimeMillis(),
) {
    @get:Ignore
    val questionCount: Int
        get() = runCatching { Json.parseToJsonElement(quizJson).jsonArray.size }.getOrDefault(0)

    /** Number of lesson cards in [cardsJson]. Mirrors [questionCount] pattern. */
    @get:Ignore
    val cardCount: Int
        get() = runCatching { Json.parseToJsonElement(cardsJson).jsonArray.size }.getOrDefault(0)
}
