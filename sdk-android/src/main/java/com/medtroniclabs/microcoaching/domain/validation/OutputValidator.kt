package com.medtroniclabs.microcoaching.domain.validation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates AI-generated content before it is displayed to the CHW.
 *
 * Applies to BOTH online (backend Gemini) AND edge (on-device Gemma) responses.
 * On failure, [FallbackSelector] serves the pre-authored Bangla card instead.
 *
 * Block-list rules follow DDD v2 Section 7.9.
 */
class OutputValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val failureReason: String? = null,
    )

    /** Validates a structured JSON coaching card response from the AI. */
    fun validate(jsonResponse: String): ValidationResult {
        if (jsonResponse.isBlank()) return ValidationResult(false, "empty_response")

        val obj = try {
            Json.parseToJsonElement(jsonResponse) as? JsonObject
                ?: return ValidationResult(false, "not_json_object")
        } catch (_: Exception) {
            return ValidationResult(false, "invalid_json")
        }

        val title = obj["title"]?.jsonPrimitive?.content ?: ""
        val body = obj["body"]?.jsonPrimitive?.content ?: ""
        val blocked = containsBlockedPhrase("$title $body".lowercase())
        return if (blocked != null) ValidationResult(false, "blocked:$blocked") else ValidationResult(true)
    }

    /** Validates a plain text fragment (e.g., edge-mode output before JSON wrapping). */
    fun validateText(text: String): ValidationResult {
        if (text.isBlank()) return ValidationResult(false, "empty_text")
        val blocked = containsBlockedPhrase(text.lowercase())
        return if (blocked != null) ValidationResult(false, "blocked:$blocked") else ValidationResult(true)
    }

    private fun containsBlockedPhrase(lowercaseText: String): String? {
        DIAGNOSTIC_PHRASES.forEach { if (lowercaseText.contains(it)) return "diagnostic:$it" }
        DRUG_NAMES.forEach { if (lowercaseText.contains(it)) return "drug:$it" }
        DOSAGE_PATTERNS.forEach { if (lowercaseText.contains(it)) return "dosage:$it" }
        return null
    }

    companion object {
        private val DIAGNOSTIC_PHRASES = listOf(
            "you have ", "you are diagnosed", "your diagnosis is", "you suffer from",
            "patient has been diagnosed", "you are suffering from",
            "আপনার আছে", "রোগ নির্ণয়", "আপনার রোগ হয়েছে",
        )
        private val DRUG_NAMES = listOf(
            "metformin", "insulin", "amlodipine", "enalapril", "losartan",
            "aspirin", "nifedipine", "atenolol", "glibenclamide", "lisinopril",
            "hydrochlorothiazide", "furosemide", "methyldopa", "labetalol",
            "glimepiride", "ramipril", "valsartan", "bisoprolol",
        )
        private val DOSAGE_PATTERNS = listOf(
            " mg", " mcg", " ml", " tablet", " tablets",
            " dose ", " doses", " unit ", " units", " capsule", " capsules",
            "মিগ্রা", "মিলি", "ট্যাবলেট",
        )
    }
}
