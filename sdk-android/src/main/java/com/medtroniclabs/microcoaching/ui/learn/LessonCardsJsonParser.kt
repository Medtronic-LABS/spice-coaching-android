package com.medtroniclabs.microcoaching.ui.learn

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "LessonCardsJsonParser"

/**
 * Parse the raw `cards_json` blob stored on `module_cache.cards_json` into a
 * typed [LessonCard] list. Sibling to [parseInlineQuiz].
 *
 * Both Bangla and English fields are always extracted so the composable can
 * select the correct language at render time without re-parsing.
 *
 * The card JSON schema (from the v3.3 backend) ships each card as:
 * ```json
 * {
 *   "title_bn": "...", "title_en": "...",
 *   "body_bn": "...",  "body_en": "...",
 *   "card_family_id": "uuid",
 *   "thresholds": [...],        // clinical reference values (deferred)
 *   "source_block_ids": [...]   // backend provenance (ignored client-side)
 * }
 * ```
 *
 * Malformed rows are logged and skipped — empty list is the worst case.
 */
internal fun parseLessonCards(cardsJson: String): List<LessonCard> {
    val arr = try {
        Json.parseToJsonElement(cardsJson).jsonArray
    } catch (e: Exception) {
        Log.w(TAG, "cards_json is not a JSON array (len=${cardsJson.length}): ${e.message}")
        return emptyList()
    }

    return arr.mapIndexedNotNull { idx, el ->
        runCatching {
            val obj = el.jsonObject
            LessonCard(
                titleBn = obj["title_bn"]?.jsonPrimitive?.cardContent() ?: "",
                titleEn = obj["title_en"]?.jsonPrimitive?.cardContent(),
                bodyBn = obj["body_bn"]?.jsonPrimitive?.cardContent() ?: "",
                bodyEn = obj["body_en"]?.jsonPrimitive?.cardContent(),
                cardFamilyId = obj["card_family_id"]?.jsonPrimitive?.cardContent(),
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to parse card #$idx: ${e.message}")
        }.getOrNull()
    }
}

private fun JsonPrimitive.cardContent(): String? =
    if (this is JsonNull) null else content
