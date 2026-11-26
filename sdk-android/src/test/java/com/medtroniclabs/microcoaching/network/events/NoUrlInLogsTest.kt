package com.medtroniclabs.microcoaching.network.events

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source scan: the files that handle presigned URLs and storage responses must
 * not put a URL, a response body, an exception message or a throwable (whose
 * message is printed with the stack) into a log call. Presigned URLs carry
 * signatures and release builds keep these logs.
 *
 * Allowed in a log call: fixed text, a status code, the exception class name
 * and `UrlSanitizer.sanitize(url)`.
 */
class NoUrlInLogsTest {

    private val root = "src/main/java/com/medtroniclabs/microcoaching"

    private val scanned = listOf(
        "$root/ai/download/ResumableHttpDownloader.kt",
        "$root/network/MediaUrlResolver.kt",
        "$root/data/asset/AssetCache.kt",
    )

    private val logStart = Regex("""\b(?:Log|Timber)\.[divwe]\(""")

    // Each rule is applied to a log call after UrlSanitizer.sanitize(...) is removed.
    private val forbidden = listOf(
        "URL or src value" to Regex("""\$\{?(?:url|src|renewed|hfUrl|presignedUrl)\b|\.url\b|\bpresignedUrl\b"""),
        "exception or response message" to Regex("""\.message\b"""),
        "response or error body" to Regex("""\bbody=|errorBody|response\.body|\.string\(\)"""),
        "response headers" to Regex("""\bheaders\b"""),
        "throwable argument" to Regex(""",\s*(?:it|e|cause|throwable|t)\s*,?\s*\)\s*$"""),
    )

    @Test
    fun logCallsDoNotLeakUrlsBodiesOrMessages() {
        val violations = mutableListOf<String>()
        scanned.forEach { path ->
            val file = File(path)
            assertTrue("missing $path (cwd=${File(".").absolutePath})", file.exists())
            val text = file.readText()
            logStart.findAll(text).forEach { match ->
                val call = callText(text, match.range.last)
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                val checked = call.replace(Regex("""UrlSanitizer\.sanitize\([^)]*\)"""), "")
                forbidden.forEach { (label, rule) ->
                    if (rule.containsMatchIn(checked)) {
                        violations += "${file.name}:$line $label: ${call.replace(Regex("""\s+"""), " ")}"
                    }
                }
            }
        }
        assertTrue("log calls leak sensitive data:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    /** Text of the call from `Log.x(` to its balanced closing parenthesis. */
    private fun callText(text: String, openIndex: Int): String {
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(openIndex, i + 1)
            }
            i++
        }
        return text.substring(openIndex)
    }
}
